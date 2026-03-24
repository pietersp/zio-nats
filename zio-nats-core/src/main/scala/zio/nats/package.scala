package zio

/**
 * Package-level conveniences for zio-nats.
 *
 * Import zio.nats._ to get all services plus these helpers.
 */
package object nats {

  import zio.stream.ZStream

  /** Type alias: a ZIO effect that requires the core Nats service. */
  type NatsIO[+A] = ZIO[Nats, NatsError, A]

  /** Type alias: a ZIO effect that requires JetStream. */
  type JetStreamIO[+A] = ZIO[JetStream, NatsError, A]

  // ---------------------------------------------------------------------------
  // Envelope / ObjectData convenience extensions
  // ---------------------------------------------------------------------------

  /**
   * Strips the [[Envelope]] wrapper from a ZIO effect, returning only the
   * decoded payload and discarding the raw [[NatsMessage]] metadata.
   *
   * {{{
   * Nats.request[Query, Response](subject, query).payload  // ZIO[Nats, NatsError, Response]
   * }}}
   */
  extension [R, E, A](zio: ZIO[R, E, Envelope[A]]) def payload: ZIO[R, E, A] = zio.map(_.value)

  /**
   * Strips the [[Envelope]] wrapper from every element of a ZStream, emitting
   * only the decoded payloads and discarding the raw [[NatsMessage]] metadata.
   *
   * {{{
   * Nats.subscribe[Event](subject).payload  // ZStream[Nats, NatsError, Event]
   * }}}
   */
  extension [R, E, A](stream: ZStream[R, E, Envelope[A]]) def payload: ZStream[R, E, A] = stream.map(_.value)

  /**
   * Strips the [[ObjectData]] wrapper from a ZIO effect, returning only the
   * decoded payload and discarding the [[ObjectSummary]] metadata.
   *
   * {{{
   * os.get[MyData]("config.json").payload  // ZIO[Any, NatsError, MyData]
   * }}}
   */
  extension [R, E, A](zio: ZIO[R, E, ObjectData[A]])
    @scala.annotation.targetName("objectDataPayload")
    def payload: ZIO[R, E, A] = zio.map(_.value)

  // --- Connection config re-exports ---
  type NatsConfig = config.NatsConfig; val NatsConfig = config.NatsConfig
  type NatsAuth   = config.NatsAuth; val NatsAuth     = config.NatsAuth
  type NatsTls    = config.NatsTls; val NatsTls       = config.NatsTls

  // --- JetStream config re-exports ---
  type StreamConfig          = jetstream.StreamConfig; val StreamConfig     = jetstream.StreamConfig
  type ConsumerConfig        = jetstream.ConsumerConfig; val ConsumerConfig = jetstream.ConsumerConfig
  type OrderedConsumerConfig = jetstream.OrderedConsumerConfig;
  val OrderedConsumerConfig = jetstream.OrderedConsumerConfig
  type MirrorConfig   = jetstream.MirrorConfig; val MirrorConfig     = jetstream.MirrorConfig
  type SourceConfig   = jetstream.SourceConfig; val SourceConfig     = jetstream.SourceConfig
  type ExternalConfig = jetstream.ExternalConfig; val ExternalConfig = jetstream.ExternalConfig
  // --- KV config re-exports ---
  type KeyValueConfig  = kv.KeyValueConfig; val KeyValueConfig   = kv.KeyValueConfig
  type RepublishConfig = kv.RepublishConfig; val RepublishConfig = kv.RepublishConfig
  // --- ObjectStore config re-exports ---
  type ObjectStoreConfig = objectstore.ObjectStoreConfig; val ObjectStoreConfig = objectstore.ObjectStoreConfig

  // ---- JetStream re-exports (users don't need zio.nats.jetstream._) ----
  type JetStream           = jetstream.JetStream; val JetStream                     = jetstream.JetStream
  type JetStreamManagement = jetstream.JetStreamManagement; val JetStreamManagement = jetstream.JetStreamManagement
  type Consumer            = jetstream.Consumer
  type OrderedConsumer     = jetstream.OrderedConsumer
  type JetStreamMessage    = jetstream.JetStreamMessage
  type JsEnvelope[A]       = jetstream.JsEnvelope[A]; val JsEnvelope                = jetstream.JsEnvelope
  type PublishAck          = jetstream.PublishAck; val PublishAck                   = jetstream.PublishAck
  type PublishOptions      = jetstream.PublishOptions; val PublishOptions           = jetstream.PublishOptions
  type JsPublishParams     = jetstream.JsPublishParams; val JsPublishParams         = jetstream.JsPublishParams
  type FetchOptions        = jetstream.FetchOptions; val FetchOptions               = jetstream.FetchOptions
  type ConsumeOptions      = jetstream.ConsumeOptions; val ConsumeOptions           = jetstream.ConsumeOptions
  type StreamSummary       = jetstream.StreamSummary; val StreamSummary             = jetstream.StreamSummary
  type ConsumerSummary     = jetstream.ConsumerSummary; val ConsumerSummary         = jetstream.ConsumerSummary
  type PurgeSummary        = jetstream.PurgeSummary; val PurgeSummary               = jetstream.PurgeSummary
  type ConsumerPauseInfo   = jetstream.ConsumerPauseInfo; val ConsumerPauseInfo     = jetstream.ConsumerPauseInfo
  type MessageInfo         = jetstream.MessageInfo
  type AccountStatistics   = jetstream.AccountStatistics

  // ---- Key-Value re-exports (users don't need zio.nats.kv._) ----
  type KeyValue             = kv.KeyValue; val KeyValue                         = kv.KeyValue
  type KeyValueManagement   = kv.KeyValueManagement; val KeyValueManagement     = kv.KeyValueManagement
  type KeyValueEntry        = kv.KeyValueEntry; val KeyValueEntry               = kv.KeyValueEntry
  type KvEnvelope[A]        = kv.KvEnvelope[A]; val KvEnvelope                  = kv.KvEnvelope
  type KvEvent[A]           = kv.KvEvent[A]; val KvEvent                        = kv.KvEvent
  type KeyValueOperation    = kv.KeyValueOperation; val KeyValueOperation       = kv.KeyValueOperation
  type KeyValueWatchOptions = kv.KeyValueWatchOptions; val KeyValueWatchOptions = kv.KeyValueWatchOptions
  type KeyValueBucketStatus = kv.KeyValueBucketStatus; val KeyValueBucketStatus = kv.KeyValueBucketStatus

  // ---- Object Store re-exports (users don't need zio.nats.objectstore._) ----
  type ObjectStore           = objectstore.ObjectStore; val ObjectStore = objectstore.ObjectStore
  type ObjectStoreManagement = objectstore.ObjectStoreManagement;
  val ObjectStoreManagement = objectstore.ObjectStoreManagement
  type ObjectData[A]           = objectstore.ObjectData[A]; val ObjectData    = objectstore.ObjectData
  type ObjectSummary           = objectstore.ObjectSummary; val ObjectSummary = objectstore.ObjectSummary
  type ObjectMeta              = objectstore.ObjectMeta; val ObjectMeta       = objectstore.ObjectMeta
  type ObjectStoreBucketStatus = objectstore.ObjectStoreBucketStatus;
  val ObjectStoreBucketStatus = objectstore.ObjectStoreBucketStatus
  type ObjectStoreWatchOptions = objectstore.ObjectStoreWatchOptions;
  val ObjectStoreWatchOptions = objectstore.ObjectStoreWatchOptions

  // ---- Service framework re-exports (users don't need zio.nats.service._) ----
  type ServiceConfig         = service.ServiceConfig; val ServiceConfig       = service.ServiceConfig
  type ServiceGroup          = service.ServiceGroup; val ServiceGroup         = service.ServiceGroup
  type QueueGroupPolicy      = service.QueueGroupPolicy; val QueueGroupPolicy = service.QueueGroupPolicy
  type ServiceErrorMapper[E] = service.ServiceErrorMapper[E]
  val ServiceErrorMapper = service.ServiceErrorMapper
  type NamedEndpoint                 = service.NamedEndpoint
  type EndpointIn[In]                = service.EndpointIn[In]
  type ServiceEndpoint[In, Err, Out] = service.ServiceEndpoint[In, Err, Out]
  val ServiceEndpoint = service.ServiceEndpoint
  type BoundEndpoint      = service.BoundEndpoint
  type ServiceRequest[+A] = service.ServiceRequest[A]; val ServiceRequest  = service.ServiceRequest
  type NatsService        = service.NatsService
  type ServiceDiscovery   = service.ServiceDiscovery; val ServiceDiscovery = service.ServiceDiscovery
  type PingResponse       = service.PingResponse; val PingResponse         = service.PingResponse
  type InfoResponse       = service.InfoResponse; val InfoResponse         = service.InfoResponse
  type StatsResponse      = service.StatsResponse; val StatsResponse       = service.StatsResponse
  type EndpointStats      = service.EndpointStats; val EndpointStats       = service.EndpointStats
  type EndpointInfo       = service.EndpointInfo; val EndpointInfo         = service.EndpointInfo
}
