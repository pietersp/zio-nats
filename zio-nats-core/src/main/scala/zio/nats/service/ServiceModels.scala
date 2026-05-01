package zio.nats.service

import io.nats.service.{
  Endpoint => JEndpoint,
  PingResponse => JPingResponse,
  InfoResponse => JInfoResponse,
  StatsResponse => JStatsResponse,
  EndpointStats => JEndpointStats
}
import zio.nats.{Headers, Subject}

import scala.jdk.CollectionConverters._

// ---------------------------------------------------------------------------
// ServiceRequest
// ---------------------------------------------------------------------------

/**
 * An incoming service request with the payload already decoded as `A`.
 *
 * Provides access to request metadata (subject, headers) alongside the decoded
 * value. Used with [[ServiceEndpoint.handleWith]] or
 * [[ServiceEndpoint.handleWithZIO]] when the handler needs more than just the
 * payload.
 *
 * For the common case where only the payload is needed, use
 * [[ServiceEndpoint.handle]] or [[ServiceEndpoint.handleZIO]] instead — they
 * receive `A` directly.
 *
 * @tparam A
 *   The decoded payload type.
 */
final case class ServiceRequest[+A](
  /** The decoded request payload. */
  value: A,
  /** The subject this request was sent to. */
  subject: Subject,
  /** Request headers (empty if no headers were sent). */
  headers: Headers
)

// ---------------------------------------------------------------------------
// Discovery response models
// ---------------------------------------------------------------------------

/**
 * Ping response from a running NATS microservice.
 *
 * Returned by [[ServiceDiscovery.ping]] methods. Contains the minimal identity
 * information for a service instance.
 */
final case class PingResponse(
  /** Unique instance ID, stable for the lifetime of the service. */
  id: String,
  /** Service name as registered. */
  name: String,
  /** Service version string. */
  version: String,
  /** Key-value metadata registered with the service. */
  metadata: Map[String, String]
)

object PingResponse {
  private[nats] def fromJava(j: JPingResponse): PingResponse =
    PingResponse(
      id = j.getId,
      name = j.getName,
      version = j.getVersion,
      metadata = Option(j.getMetadata).fold(Map.empty[String, String])(_.asScala.toMap)
    )
}

/**
 * Info response from a running NATS microservice.
 *
 * Returned by [[ServiceDiscovery.info]] methods. Extends [[PingResponse]] with
 * description and a list of registered endpoints.
 */
final case class InfoResponse(
  id: String,
  name: String,
  version: String,
  description: Option[String],
  metadata: Map[String, String],
  endpoints: List[EndpointInfo]
)

object InfoResponse {
  private[nats] def fromJava(j: JInfoResponse): InfoResponse =
    InfoResponse(
      id = j.getId,
      name = j.getName,
      version = j.getVersion,
      description = Option(j.getDescription).filter(_.nonEmpty),
      metadata = Option(j.getMetadata).fold(Map.empty[String, String])(_.asScala.toMap),
      endpoints = Option(j.getEndpoints)
        .fold(List.empty[EndpointInfo])(_.asScala.toList.map(EndpointInfo.fromJava))
    )
}

/**
 * Describes a single registered endpoint as seen in an [[InfoResponse]].
 */
final case class EndpointInfo(
  name: String,
  subject: String,
  queueGroup: String,
  metadata: Map[String, String]
)

object EndpointInfo {

  /** Build from jnats [[io.nats.service.Endpoint]]. */
  private[nats] def fromJava(j: JEndpoint): EndpointInfo =
    EndpointInfo(
      name = j.getName,
      subject = Option(j.getSubject).getOrElse(j.getName),
      queueGroup = Option(j.getQueueGroup).getOrElse(""),
      metadata = Option(j.getMetadata).fold(Map.empty[String, String])(_.asScala.toMap)
    )
}

/**
 * Statistics response from a running NATS microservice.
 *
 * Returned by [[ServiceDiscovery.stats]] methods. Extends [[PingResponse]] with
 * start time and per-endpoint counters.
 */
final case class StatsResponse(
  id: String,
  name: String,
  version: String,
  metadata: Map[String, String],
  started: java.time.ZonedDateTime,
  endpoints: List[EndpointStats]
)

object StatsResponse {
  private[nats] def fromJava(j: JStatsResponse): StatsResponse =
    StatsResponse(
      id = j.getId,
      name = j.getName,
      version = j.getVersion,
      metadata = Option(j.getMetadata).fold(Map.empty[String, String])(_.asScala.toMap),
      started = j.getStarted,
      endpoints = Option(j.getEndpointStatsList)
        .fold(List.empty[EndpointStats])(_.asScala.toList.map(EndpointStats.fromJava))
    )
}

/**
 * Per-endpoint statistics for a running NATS microservice.
 *
 * Included in [[StatsResponse]] and returned by [[NatsService.endpointStats]].
 */
final case class EndpointStats(
  name: String,
  subject: String,
  queueGroup: String,
  /** Total number of requests received. */
  numRequests: Long,
  /** Total number of requests that resulted in an error response. */
  numErrors: Long,
  /** Cumulative processing time for all requests, in nanoseconds. */
  processingTimeNanos: Long,
  /** Average processing time per request, in nanoseconds. */
  averageProcessingTimeNanos: Long,
  /** The last error message, if any. */
  lastError: Option[String],
  started: java.time.ZonedDateTime
)

object EndpointStats {
  private[nats] def fromJava(j: JEndpointStats): EndpointStats =
    EndpointStats(
      name = j.getName,
      subject = Option(j.getSubject).getOrElse(j.getName),
      queueGroup = Option(j.getQueueGroup).getOrElse(""),
      numRequests = j.getNumRequests,
      numErrors = j.getNumErrors,
      processingTimeNanos = j.getProcessingTime,
      averageProcessingTimeNanos = j.getAverageProcessingTime,
      lastError = Option(j.getLastError).filter(_.nonEmpty),
      started = j.getStarted
    )
}
