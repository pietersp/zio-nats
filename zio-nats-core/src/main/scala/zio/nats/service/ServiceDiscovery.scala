package zio.nats.service

import io.nats.service.{Discovery => JDiscovery}
import zio._
import zio.nats.{Nats, NatsError}

import scala.jdk.CollectionConverters._

// ---------------------------------------------------------------------------
// ServiceDiscovery trait — public API
// ---------------------------------------------------------------------------

/**
 * Client for discovering NATS microservices running on the cluster.
 *
 * Sends requests to the well-known `$SRV.*` subjects and collects responses
 * from all running services (or a named subset). Uses jnats'
 * [[io.nats.service.Discovery]] under the hood.
 *
 * All methods block the calling thread until either the max-wait timeout
 * expires or `maxResults` responses have been collected. Wrapped in
 * `ZIO.attemptBlocking` so they run on the blocking thread pool.
 *
 * ==Example==
 * {{{
 * for {
 *   discovery <- ServiceDiscovery.make(maxWait = 3.seconds, maxResults = 50)
 *   services  <- discovery.ping()
 *   _         <- ZIO.foreach(services)(s => Console.printLine(s"${s.name} v${s.version} [${s.id}]"))
 * } yield ()
 * }}}
 */
trait ServiceDiscovery {

  // --- Ping ---

  /**
   * Ping all running service instances. Returns one [[PingResponse]] per
   * instance.
   */
  def ping(): IO[NatsError, List[PingResponse]]

  /** Ping all instances of the service named `serviceName`. */
  def ping(serviceName: String): IO[NatsError, List[PingResponse]]

  /**
   * Ping a specific service instance by name and ID.
   *
   * Returns [[None]] if the instance does not respond within the configured
   * timeout.
   */
  def ping(serviceName: String, serviceId: String): IO[NatsError, Option[PingResponse]]

  // --- Info ---

  /** Get info from all running service instances. */
  def info(): IO[NatsError, List[InfoResponse]]

  /** Get info from all instances of the named service. */
  def info(serviceName: String): IO[NatsError, List[InfoResponse]]

  /** Get info from a specific service instance by name and ID. */
  def info(serviceName: String, serviceId: String): IO[NatsError, Option[InfoResponse]]

  // --- Stats ---

  /** Get stats from all running service instances. */
  def stats(): IO[NatsError, List[StatsResponse]]

  /** Get stats from all instances of the named service. */
  def stats(serviceName: String): IO[NatsError, List[StatsResponse]]

  /** Get stats from a specific service instance by name and ID. */
  def stats(serviceName: String, serviceId: String): IO[NatsError, Option[StatsResponse]]
}

// ---------------------------------------------------------------------------
// ServiceDiscovery companion — factory methods
// ---------------------------------------------------------------------------

object ServiceDiscovery {

  /**
   * Create a [[ServiceDiscovery]] client backed by the given [[Nats]]
   * connection.
   *
   * @param maxWait
   *   Maximum time to wait for responses per discovery call (default 5
   *   seconds).
   * @param maxResults
   *   Maximum number of responses to collect per discovery call (default 10).
   */
  def make(
    maxWait: Duration = 5.seconds,
    maxResults: Int = 10
  ): ZIO[Nats, NatsError, ServiceDiscovery] =
    ZIO.serviceWithZIO[Nats] { nats =>
      nats.underlying.flatMap(conn =>
        ZIO
          .attempt(new JDiscovery(conn, maxWait.toMillis, maxResults))
          .mapBoth(NatsError.fromThrowable, new ServiceDiscoveryLive(_))
      )
    }

  /**
   * [[ZLayer]] variant of [[make]] with default timeout and result count.
   *
   * Provide different defaults by calling [[make]] directly.
   */
  val live: ZLayer[Nats, NatsError, ServiceDiscovery] =
    ZLayer.fromZIO(make())
}

// ---------------------------------------------------------------------------
// ServiceDiscoveryLive — private implementation
// ---------------------------------------------------------------------------

/** jnats-backed implementation of [[ServiceDiscovery]]. */
private[nats] final class ServiceDiscoveryLive(discovery: JDiscovery) extends ServiceDiscovery {

  private def discover[T](block: => T): IO[NatsError, T] =
    ZIO.attemptBlocking(block).mapError(NatsError.fromThrowable)

  def ping(): IO[NatsError, List[PingResponse]] =
    discover(discovery.ping().asScala.toList.map(PingResponse.fromJava))

  def ping(serviceName: String): IO[NatsError, List[PingResponse]] =
    discover(discovery.ping(serviceName).asScala.toList.map(PingResponse.fromJava))

  def ping(serviceName: String, serviceId: String): IO[NatsError, Option[PingResponse]] =
    discover(Option(discovery.ping(serviceName, serviceId)).map(PingResponse.fromJava))

  def info(): IO[NatsError, List[InfoResponse]] =
    discover(discovery.info().asScala.toList.map(InfoResponse.fromJava))

  def info(serviceName: String): IO[NatsError, List[InfoResponse]] =
    discover(discovery.info(serviceName).asScala.toList.map(InfoResponse.fromJava))

  def info(serviceName: String, serviceId: String): IO[NatsError, Option[InfoResponse]] =
    discover(Option(discovery.info(serviceName, serviceId)).map(InfoResponse.fromJava))

  def stats(): IO[NatsError, List[StatsResponse]] =
    discover(discovery.stats().asScala.toList.map(StatsResponse.fromJava))

  def stats(serviceName: String): IO[NatsError, List[StatsResponse]] =
    discover(discovery.stats(serviceName).asScala.toList.map(StatsResponse.fromJava))

  def stats(serviceName: String, serviceId: String): IO[NatsError, Option[StatsResponse]] =
    discover(Option(discovery.stats(serviceName, serviceId)).map(StatsResponse.fromJava))
}
