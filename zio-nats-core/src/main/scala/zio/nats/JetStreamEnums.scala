package zio.nats

// ---------------------------------------------------------------------------
// JetStream policy / option enums
//
// Each enum mirrors its io.nats.client.api counterpart and exposes a
// private[nats] toJava conversion so that the public API stays free of
// raw jnats types.
// ---------------------------------------------------------------------------

/**
 * Acknowledgement policy for a JetStream consumer.
 *
 *   - `Explicit` — every message must be explicitly acknowledged (default).
 *   - `All` — acknowledging a message implicitly acknowledges all previous
 *     messages.
 *   - `None` — no acknowledgement required; messages are considered
 *     acknowledged immediately on delivery.
 */
enum AckPolicy:
  case Explicit, All, None

  private[nats] def toJava: io.nats.client.api.AckPolicy = this match
    case Explicit => io.nats.client.api.AckPolicy.Explicit
    case All      => io.nats.client.api.AckPolicy.All
    case None     => io.nats.client.api.AckPolicy.None

/**
 * Delivery start policy for a JetStream consumer.
 *
 *   - `All` — start at the first available message (default).
 *   - `Last` — start at the last available message.
 *   - `New` — deliver only new messages published after the consumer is
 *     created.
 *   - `ByStartSequence` — start at a specific stream sequence number.
 *   - `ByStartTime` — start at a specific wall-clock time.
 *   - `LastPerSubject` — start at the last message for each subject.
 */
enum DeliverPolicy:
  case All, Last, New, ByStartSequence, ByStartTime, LastPerSubject

  private[nats] def toJava: io.nats.client.api.DeliverPolicy = this match
    case All             => io.nats.client.api.DeliverPolicy.All
    case Last            => io.nats.client.api.DeliverPolicy.Last
    case New             => io.nats.client.api.DeliverPolicy.New
    case ByStartSequence => io.nats.client.api.DeliverPolicy.ByStartSequence
    case ByStartTime     => io.nats.client.api.DeliverPolicy.ByStartTime
    case LastPerSubject  => io.nats.client.api.DeliverPolicy.LastPerSubject

/**
 * Message replay policy for a JetStream consumer.
 *
 *   - `Instant` — deliver messages as fast as possible (default).
 *   - `Original` — replay messages at the rate they were originally published.
 */
enum ReplayPolicy:
  case Instant, Original

  private[nats] def toJava: io.nats.client.api.ReplayPolicy = this match
    case Instant  => io.nats.client.api.ReplayPolicy.Instant
    case Original => io.nats.client.api.ReplayPolicy.Original

/**
 * Discard policy for a JetStream stream when storage limits are reached.
 *
 *   - `Old` — discard the oldest messages to make room (default).
 *   - `New` — reject new messages when limits are reached.
 */
enum DiscardPolicy:
  case Old, New

  private[nats] def toJava: io.nats.client.api.DiscardPolicy = this match
    case Old => io.nats.client.api.DiscardPolicy.Old
    case New => io.nats.client.api.DiscardPolicy.New

/**
 * Retention policy for a JetStream stream.
 *
 *   - `Limits` — retain messages up to the configured storage limits (default).
 *   - `Interest` — retain messages only while there are active consumers with
 *     an interest in them.
 *   - `WorkQueue` — retain a message only until it has been consumed by any one
 *     consumer (work-queue semantics).
 */
enum RetentionPolicy:
  case Limits, Interest, WorkQueue

  private[nats] def toJava: io.nats.client.api.RetentionPolicy = this match
    case Limits    => io.nats.client.api.RetentionPolicy.Limits
    case Interest  => io.nats.client.api.RetentionPolicy.Interest
    case WorkQueue => io.nats.client.api.RetentionPolicy.WorkQueue

/**
 * Compression option for a JetStream stream.
 *
 *   - `None` — no compression (default).
 *   - `S2` — S2 compression (fast, moderate ratio).
 */
enum CompressionOption:
  case None, S2

  private[nats] def toJava: io.nats.client.api.CompressionOption = this match
    case None => io.nats.client.api.CompressionOption.None
    case S2   => io.nats.client.api.CompressionOption.S2

/**
 * Priority policy for a JetStream consumer.
 *
 *   - `None` — no priority (default).
 *   - `Overflow` — pin to a priority group; overflow to normal consumers.
 *   - `Prioritized` — deliver to priority groups in order.
 *   - `PinnedClient` — pin messages to a specific client in the group.
 */
enum PriorityPolicy:
  case None, Overflow, Prioritized, PinnedClient

  private[nats] def toJava: io.nats.client.api.PriorityPolicy = this match
    case None         => io.nats.client.api.PriorityPolicy.None
    case Overflow     => io.nats.client.api.PriorityPolicy.Overflow
    case Prioritized  => io.nats.client.api.PriorityPolicy.Prioritized
    case PinnedClient => io.nats.client.api.PriorityPolicy.PinnedClient
