package zio.nats.testkit

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/** A testcontainers definition for a NATS server with JetStream support. */
final case class NatsContainer(
  override val underlyingUnsafeContainer: org.testcontainers.containers.GenericContainer[_]
) extends GenericContainer(underlyingUnsafeContainer) {

  /** NATS client URL for this container. */
  def clientUrl: String =
    s"nats://${container.getHost}:${container.getMappedPort(NatsContainer.ClientPort)}"

  /** HTTP monitoring URL for this container. */
  def monitoringUrl: String =
    s"http://${container.getHost}:${container.getMappedPort(NatsContainer.MonitoringPort)}"
}

object NatsContainer {
  val ClientPort: Int      = 4222
  val MonitoringPort: Int  = 8222
  val DefaultImage: String = "nats:latest"

  /** Create a NatsContainer with JetStream enabled by default.
    *
    * @param imageName  Docker image to use (default: nats:latest)
    * @param enableJetStream  Pass the -js flag to enable JetStream
    */
  def apply(
    imageName: String = DefaultImage,
    enableJetStream: Boolean = true
  ): NatsContainer = {
    val command   = if (enableJetStream) "--js" else ""
    val container = new org.testcontainers.containers.GenericContainer(imageName)
    container.withExposedPorts(ClientPort, MonitoringPort)
    container.withCommand(command)
    container.waitingFor(
      Wait.forLogMessage(".*Server is ready.*", 1)
        .withStartupTimeout(java.time.Duration.ofSeconds(60))
    )
    new NatsContainer(container)
  }
}
