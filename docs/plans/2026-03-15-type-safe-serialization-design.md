# Type-Safe Serialization Design

## Goal

Add zio-blocks Schema-based serialization to zio-nats, enabling type-safe publish/subscribe for arbitrary data types. The API should be simple for end users while supporting both automatic derivation and manual codecs.

## Requirements

1. **Automatic Schema Derivation**: Users can just define case classes and get schemas automatically
2. **Manual Codecs**: Users can register custom codecs if needed
3. **Configurable Format**: Default to JSON, but support MessagePack, etc.
4. **All APIs Typed**: publish, subscribe, request, JetStream operations
5. **Scala 2/3 Compatible**: Use `given` for Scala 3, `implicit` for Scala 2.13
6. **Opaque Types for Scala 3**: Subject, StreamName, ConsumerName as opaque types

## Design

### 1. Serialization Format

```scala
package zio.nats.serialization

trait SerializationFormat {
  def encode[T: Schema](value: T): Chunk[Byte]
  def decode[T: Schema](bytes: Chunk[Byte]): Either[Throwable, T]
}

object SerializationFormat {
  val json: SerializationFormat = JsonSerializationFormat
  val messagePack: SerializationFormat = MessagePackSerializationFormat
  
  // Default format stored in config
}

case class NatsConfig(
  // ... existing fields
  format: SerializationFormat = SerializationFormat.json
)
```

### 2. Core NATS API - Type-Safe Publish

```scala
package zio.nats

trait Nats {
  // Existing raw API (kept for binary protocols)
  def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, Unit]
  
  // NEW: Type-safe publish with automatic serialization
  def publish[T: Schema](subject: Subject, data: T): IO[NatsError, Unit]
  
  // Type-safe request/reply
  def request[T: Schema, R: Schema](
    subject: Subject,
    data: T,
    timeout: Duration = 2.seconds
  ): IO[NatsError, NatsMessage]
}
```

### 3. Core NATS API - Type-Safe Subscribe

```scala
trait Nats {
  // Existing raw API
  def subscribe(subject: Subject): ZStream[Nats, NatsError, NatsMessage]
  
  // NEW: Type-safe subscribe - deserializes to type T
  def subscribe[T: Schema](subject: Subject): ZStream[Nats, NatsError, T]
}
```

### 4. JetStream API - Type-Safe

```scala
package zio.nats.jetstream

trait JetStream {
  // Type-safe publish
  def publish[T: Schema](subject: Subject, data: T): IO[NatsError, PublishAck]
  
  // Type-safe publish with headers
  def publish[T: Schema](
    subject: Subject,
    data: T,
    headers: Map[String, List[String]]
  ): IO[NatsError, PublishAck]
  
  // Type-safe async publish
  def publishAsync[T: Schema](subject: Subject, data: T): IO[NatsError, Task[PublishAck]]
  
  // Type-safe subscribe
  def subscribe[T: Schema](
    subject: Subject,
    consumer: Option[ConsumerName] = None
  ): ZStream[JetStream, NatsError, ConsumerMessage[T]]
}
```

### 5. Subject Type - Scala 2/3 Compatible

```
src/
  main/
    scala/
      zio/nats/
        subject/
          Subject.scala        # Common code
    scala-2.13/
      zio/nats/
        subject/
          Subject.scala        # Type alias / regular class
    scala-3/
      zio/nats/
        subject/
          Subject.scala        # Opaque type + extension methods
```

**Common (Subject.scala):**
```scala
package zio.nats.subject

object Subject {
  def apply(s: String): Subject = Subject.make(s)
  
  // For Scala 2: type alias or class
  // For Scala 3: opaque type
  
  extension (s: Subject) def value: String
}
```

### 6. User Usage Example

```scala
// User defines their data types
case class Person(name: String, age: Int)
case class Order(id: String, amount: Double)

// Automatic derivation - just add Schema.derived
object Person {
  implicit val schema: Schema[Person] = Schema.derived
}

object Order {
  implicit val schema: Schema[Order] = Schema.derived
}

// Now type-safe operations work:
object MyApp {
  import zio.nats._
  import zio.nats.subject.Subject
  import zio.nats.serialization._
  
  def program = {
    val subject = Subject("users")
    
    // Type-safe publish
    Nats.live.provide(
      nats.publish(subject, Person("Alice", 30))
    )
    
    // Type-safe subscribe
    Nats.live.provide(
      nats.subscribe[Person](subject).runForeach { person =>
        ZIO.debug(s"Got: ${person.name}")
      }
    )
    
    // Type-safe request/reply
    Nats.live.provide(
      for {
        reply <- nats.request[Person, Person](subject, Person("Bob", 25))
      } yield reply
    )
  }
}
```

### 7. Configuration

```scala
case class NatsConfig(
  url: String = "localhost:4222",
  name: Option[String] = None,
  // ... other fields
  format: SerializationFormat = SerializationFormat.json
)
```

### 8. Implementation Strategy

1. **Add serialization module** - Create `zio-nats-serialization` subproject or package
2. **Define SerializationFormat trait** - With JSON as default
3. **Update NatsConfig** - Add format field
4. **Update Nats trait** - Add type-safe overloads
5. **Update JetStream** - Add type-safe overloads
6. **Create Subject for Scala 2/3** - Using source directories
7. **Add tests** - Type-safe publish/subscribe tests
8. **Update docs** - Usage examples

## Components

| Component                            | Responsibility                |
|--------------------------------------|-------------------------------|
| `SerializationFormat`                | Trait for encode/decode       |
| `JsonFormat`                         | JSON implementation           |
| `MessagePackFormat`                  | MessagePack implementation    |
| `Nats.publish[T: Schema]`            | Type-safe publish             |
| `Nats.subscribe[T: Schema]`          | Type-safe subscribe           |
| `Nats.request[T: Schema, R: Schema]` | Type-safe request/reply       |
| `JetStream.publish[T: Schema]`       | Type-safe JetStream publish   |
| `JetStream.subscribe[T: Schema]`     | Type-safe JetStream subscribe |

## Error Handling

- **Serialization failure**: Returns `Left(SerializationError(...))` wrapped in `IO[NatsError, ?]`
- **Deserialization failure**: Returns `Left(DeserializationError(...))` in the stream
- Schema resolution failure: Compile-time error via implicit not found

## Testing

1. Test JSON round-trip for various types
2. Test schema derivation for nested case classes
3. Test manual codec registration
4. Test error cases (invalid JSON, missing schema)
5. Test Scala 2/3 compatibility
