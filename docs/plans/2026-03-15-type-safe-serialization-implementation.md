# Type-Safe Serialization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add zio-blocks Schema-based serialization to zio-nats, enabling type-safe publish/subscribe for arbitrary data types.

**Architecture:** Create a SerializationFormat trait with JSON as default. Add type-safe overloads to Nats and JetStream that accept `T: Schema` and serialize/deserialize automatically. Use Scala 2/3 source directories for Subject opaque types.

**Tech Stack:** zio-blocks-schema 0.0.29, Scala 2.13.18 / Scala 3.8.1, sbt

---

## Phase 1: Serialization Infrastructure

### Task 1: Create SerializationFormat trait

**Files:**
- Create: `zio-nats/src/main/scala/zio/nats/serialization/SerializationFormat.scala`

**Step 1: Write the failing test**

```scala
// zio-nats-test/src/test/scala/zio/nats/serialization/SerializationFormatSpec.scala
package zio.nats.serialization

import zio.test._
import zio.test.Assertion._
import zio.blocks.schema._
import zio.Chunk

case class Person(name: String, age: Int)
object Person {
  implicit val schema: Schema[Person] = Schema.derived
}

object SerializationFormatSpec extends ZIOSpecDefault {
  def spec = suite("SerializationFormat")(
    test("json format encodes and decodes") {
      val format = SerializationFormat.json
      val person = Person("Alice", 30)
      for {
        encoded <- ZIO.fromEither(format.encode(person))
        decoded <- ZIO.fromEither(format.decode[Person](encoded))
      } yield assert(decoded)(equalTo(person))
    }
  )
}
```

**Step 2: Run test to verify it fails**

Run: `sbt "++3.8.1; zioNatsTest/testOnly zio.nats.serialization.SerializationFormatSpec"`
Expected: FAIL - SerializationFormat not found

**Step 3: Write minimal implementation**

```scala
// zio-nats/src/main/scala/zio/nats/serialization/SerializationFormat.scala
package zio.nats.serialization

import zio.Chunk
import zio.blocks.schema.Schema

trait SerializationFormat {
  def encode[T: Schema](value: T): Either[Throwable, Chunk[Byte]]
  def decode[T: Schema](bytes: Chunk[Byte]): Either[Throwable, T]
}

object SerializationFormat {
  val json: SerializationFormat = JsonFormat
  val messagePack: SerializationFormat = MessagePackFormat
}
```

**Step 4: Run test to verify it passes**

Run: `sbt "++3.8.1; zioNatsTest/testOnly zio.nats.serialization.SerializationFormatSpec"`
Expected: PASS

**Step 5: Commit**

```bash
git add zio-nats/src/main/scala/zio/nats/serialization/SerializationFormat.scala
git commit -m "feat: add SerializationFormat trait"
```

---

### Task 2: Implement JSON Format

**Files:**
- Create: `zio-nats/src/main/scala/zio/nats/serialization/JsonFormat.scala`

**Step 1: Write the failing test**

```scala
// Add to SerializationFormatSpec.scala
test("json format handles nested case classes") {
  case class Address(street: String, city: String)
  case class User(name: String, address: Address)
  
  implicit val addressSchema: Schema[Address] = Schema.derived
  implicit val userSchema: Schema[User] = Schema.derived
  
  val format = SerializationFormat.json
  val user = User("Bob", Address("123 Main", "NYC"))
  
  for {
    encoded <- ZIO.fromEither(format.encode(user))
    decoded <- ZIO.fromEither(format.decode[User](encoded))
  } yield assert(decoded)(equalTo(user))
}
```

**Step 2: Run test to verify it fails**

Expected: FAIL - JsonFormat not found

**Step 3: Write minimal implementation**

```scala
// zio-nats/src/main/scala/zio/nats/serialization/JsonFormat.scala
package zio.nats.serialization

import zio.Chunk
import zio.blocks.schema._
import zio.schema.codec.JsonCodec

final case class JsonFormat() extends SerializationFormat {
  def encode[T: Schema](value: T): Either[Throwable, Chunk[Byte]] = {
    val codec = JsonCodec.derived[T]
    codec.encode(value).map(b => Chunk.fromArray(b.toArray))
  }
  
  def decode[T: Schema](bytes: Chunk[Byte]): Either[Throwable, T] = {
    val codec = JsonCodec.derived[T]
    codec.decode(bytes.toArray)
  }
}

val JsonFormat: SerializationFormat = JsonFormat()
```

**Step 4: Run test to verify it passes**

Expected: PASS

**Step 5: Commit**

```bash
git add zio-nats/src/main/scala/zio/nats/serialization/
git commit -m "feat: add JsonFormat implementation"
```

---

### Task 3: Update NatsConfig to include format

**Files:**
- Modify: `zio-nats/src/main/scala/zio/nats/config/NatsConfig.scala`

**Step 1: Write the failing test**

```scala
// Add to existing config test
test("NatsConfig includes format") {
  val config = NatsConfig(url = "nats://localhost:4222")
  assert(config.format)(equalTo(SerializationFormat.json))
}
```

**Step 2: Run test to verify it fails**

Expected: FAIL - format not found in NatsConfig

**Step 3: Write minimal implementation**

```scala
// In NatsConfig.scala, add:
import zio.nats.serialization.SerializationFormat

case class NatsConfig(
  url: String = "localhost:4222",
  name: Option[String] = None,
  // ... existing fields
  format: SerializationFormat = SerializationFormat.json
)
```

**Step 4: Run test to verify it passes**

Expected: PASS

**Step 5: Commit**

```bash
git add zio-nats/src/main/scala/zio/nats/config/NatsConfig.scala
git commit -m "feat: add format to NatsConfig"
```

---

## Phase 2: Type-Safe Nats API

### Task 4: Add type-safe publish to Nats trait

**Files:**
- Modify: `zio-nats/src/main/scala/zio/nats/Nats.scala`

**Step 1: Write the failing test**

```scala
// zio-nats-test/src/test/scala/zio/nats/TypeSafePubSubSpec.scala
package zio.nats

import zio.test._
import zio.test.Assertion._
import zio.blocks.schema._
import zio.nats.subject.Subject

case class TestData(value: String)
object TestData {
  implicit val schema: Schema[TestData] = Schema.derived
}

object TypeSafePubSubSpec extends ZIOSpecDefault {
  def spec = suite("Type-Safe PubSub")(
    test("publish and subscribe with typed data") {
      for {
        nats <- ZIO.service[Nats]
        received <- zio.Promise.make[Nothing, TestData]
        _ <- nats.subscribe[TestData](Subject("typed-test"))
          .foreach { data =>
            received.succeed(data)
          }
          .fork
        _ <- ZIO.sleep(100.millis)
        _ <- nats.publish(Subject("typed-test"), TestData("hello"))
        result <- received.await
      } yield assert(result)(equalTo(TestData("hello")))
    }.provideShared(NatsTestLayers.nats)
  )
}
```

**Step 2: Run test to verify it fails**

Expected: FAIL - subscribe[TestData] not found

**Step 3: Write minimal implementation**

```scala
// Add to Nats.scala:
import zio.blocks.schema.Schema
import zio.nats.serialization.SerializationFormat

trait Nats {
  // Existing:
  def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, Unit]
  
  // NEW: Type-safe overload
  def publish[T: Schema](subject: Subject, data: T): IO[NatsError, Unit] =
    for {
      config <- ZIO.service[NatsConfig]
      bytes <- ZIO.fromEither(config.format.encode(data)).mapError(e => NatsError.SerializationError(e.getMessage))
      _     <- publish(subject, bytes)
    } yield ()
  
  // Existing:
  def subscribe(subject: Subject): ZStream[Nats, NatsError, NatsMessage]
  
  // NEW: Type-safe overload
  def subscribe[T: Schema](subject: Subject): ZStream[Nats, NatsError, T] =
    ZStream.serviceWithService[Nats, Nats](nats =>
      for {
        config <- ZIO.service[NatsConfig]
        message <- nats.subscribe(subject)
        t <- ZStream.fromZIO(
          ZIO.fromEither(config.format.decode[T](message.data))
            .mapError(e => NatsError.SerializationError(e.getMessage))
        )
      } yield t
    )
}
```

**Step 4: Run test to verify it passes**

Expected: PASS

**Step 5: Commit**

```bash
git add zio-nats/src/main/scala/zio/nats/Nats.scala
git commit -m "feat: add type-safe publish and subscribe to Nats"
```

---

### Task 5: Add type-safe request/reply

**Files:**
- Modify: `zio-nats/src/main/scala/zio/nats/Nats.scala`

**Step 1: Write the failing test**

```scala
test("request/reply with typed data") {
  for {
    nats <- ZIO.service[Nats]
    _ <- nats.subscribe[String](Subject("echo")).foreach { s =>
      nats.publish(Subject("echo"), s"echo: $s")
    }
    response <- nats.request[String, String](Subject("echo"), "hello", 2.seconds)
  } yield assert(new String(response.data.toArray))(equalTo("echo: hello"))
}.provideShared(NatsTestLayers.nats)
```

**Step 2: Run test to verify it fails**

Expected: FAIL - request[String, String] not found

**Step 3: Write minimal implementation**

```scala
// Add to Nats trait:
def request[T: Schema, R: Schema](
  subject: Subject,
  data: T,
  timeout: Duration = 2.seconds
): IO[NatsError, NatsMessage] =
  for {
    config <- ZIO.service[NatsConfig]
    bytes <- ZIO.fromEither(config.format.encode(data))
      .mapError(e => NatsError.SerializationError(e.getMessage))
    reply <- request(subject, bytes, timeout)
  } yield reply

def request[T: Schema](
  subject: Subject,
  data: T,
  timeout: Duration = 2.seconds
): IO[NatsError, NatsMessage] = request[T, Nothing](subject, data, timeout)
```

**Step 4: Run test to verify it passes**

Expected: PASS

**Step 5: Commit**

```bash
git commit -m "feat: add type-safe request to Nats"
```

---

## Phase 3: JetStream Type-Safe API

### Task 6: Add type-safe JetStream publish/subscribe

**Files:**
- Modify: `zio-nats/src/main/scala/zio/nats/jetstream/JetStream.scala`

**Step 1: Write the failing test**

```scala
// Add to JetStreamSpec.scala
test("publish and subscribe with typed data") {
  for {
    jsm <- ZIO.service[JetStreamManagement]
    _ <- jsm.create(
      StreamConfiguration.builder()
        .name("typed-stream")
        .subjects("typed.>")
        .storageType(StorageType.Memory)
        .build()
    )
    js <- ZIO.service[JetStream]
    // Publish typed data
    ack <- js.publish(Subject("typed.data"), TestData("typed hello"))
    // Subscribe and receive
    received <- js.subscribe[TestData](Subject("typed.data"))
      .take(1)
      .runCollect
  } yield {
    assert(ack.getSeqNo)(equalTo(1L))
    assert(received.head.value)(equalTo("typed hello"))
  }
}.provideShared(NatsTestLayers.nats, JetStreamManagement.live)
```

**Step 2: Run test to verify it fails**

Expected: FAIL - publish[Subject, TestData] not found

**Step 3: Write minimal implementation**

```scala
// Add to JetStream trait:
import zio.blocks.schema.Schema
import zio.nats.serialization.SerializationFormat

// Existing:
def publish(subject: Subject, data: Chunk[Byte]): IO[NatsError, PublishAck]

// NEW:
def publish[T: Schema](subject: Subject, data: T): IO[NatsError, PublishAck] =
  for {
    config <- ZIO.service[NatsConfig]
    bytes <- ZIO.fromEither(config.format.encode(data))
      .mapError(e => NatsError.SerializationError(e.getMessage))
    ack <- publish(subject, bytes)
  } yield ack

// Existing:
def subscribe(subject: Subject): ZStream[JetStream, NatsError, ConsumerMessage]

// NEW:
def subscribe[T: Schema](subject: Subject): ZStream[JetStream, NatsError, T] =
  subscribe(subject).mapZIO { msg =>
    ZIO.fromEither(config.format.decode[T](msg.data))
      .mapError(e => NatsError.SerializationError(e.getMessage))
  }
```

**Step 4: Run test to verify it passes**

Expected: PASS

**Step 5: Commit**

```bash
git add zio-nats/src/main/scala/zio/nats/jetstream/JetStream.scala
git commit -m "feat: add type-safe publish and subscribe to JetStream"
```

---

## Phase 4: Subject Opaque Types (Scala 3)

### Task 7: Create Scala 3 opaque Subject type

**Files:**
- Create: `zio-nats/src/main/scala-3/zio/nats/subject/Subject.scala`

**Step 1: Write the failing test**

```scala
// Verify Scala 3 compilation
test("Subject is opaque in Scala 3") {
  val s = Subject("test")
  // In Scala 3, Subject is opaque - .value is only accessible via extension
  assert(s.value)(equalTo("test"))
}
```

**Step 2: Run test to verify it fails**

Expected: FAIL or compile error depending on setup

**Step 3: Write minimal implementation**

```scala
// zio-nats/src/main/scala-3/zio/nats/subject/Subject.scala
package zio.nats.subject

opaque type Subject = String

object Subject {
  def apply(s: String): Subject = s
  
  extension (s: Subject) def value: String = s
  
  // For interop with existing code expecting String
  def unwrap(s: Subject): String = s
}

given Conversion[String, Subject] = Subject(_)
```

**Step 4: Run test to verify it passes**

Expected: PASS

**Step 5: Commit**

```bash
git add zio-nats/src/main/scala-3/
git commit -m "feat: add Scala 3 opaque Subject type"
```

---

### Task 8: Create Scala 2.13 Subject type alias

**Files:**
- Create: `zio-nats/src/main/scala-2.13/zio/nats/subject/Subject.scala`

**Step 1: Write minimal implementation**

```scala
// zio-nats/src/main/scala-2.13/zio/nats/subject/Subject.scala
package zio.nats.subject

type Subject = String

object Subject {
  def apply(s: String): Subject = s
  def value(s: Subject): String = s
  def unwrap(s: Subject): String = s
}

implicit def stringToSubject(s: String): Subject = s
```

**Step 2: Run Scala 2.13 compilation**

Run: `sbt "++2.13.18; zioNats/compile"`
Expected: PASS

**Step 3: Commit**

```bash
git add zio-nats/src/main/scala-2.13/
git commit -m "feat: add Scala 2.13 Subject type alias"
```

---

## Phase 5: Final Verification

### Task 9: Run all tests on both Scala versions

**Step 1: Run Scala 2.13 tests**

```bash
sbt "++2.13.18; zioNatsTest/test"
```

Expected: All 22+ tests pass

**Step 2: Run Scala 3 tests**

```bash
sbt "++3.8.1; zioNatsTest/test"
```

Expected: All 22+ tests pass

**Step 3: Commit**

```bash
git commit -m "chore: all tests pass on Scala 2.13 and 3.8.1"
```

---

## Summary of Files to Create/Modify

### New Files
- `zio-nats/src/main/scala/zio/nats/serialization/SerializationFormat.scala`
- `zio-nats/src/main/scala/zio/nats/serialization/JsonFormat.scala`
- `zio-nats/src/main/scala-3/zio/nats/subject/Subject.scala`
- `zio-nats/src/main/scala-2.13/zio/nats/subject/Subject.scala`
- `zio-nats-test/src/test/scala/zio/nats/serialization/SerializationFormatSpec.scala`
- `zio-nats-test/src/test/scala/zio/nats/TypeSafePubSubSpec.scala`

### Modified Files
- `zio-nats/src/main/scala/zio/nats/config/NatsConfig.scala`
- `zio-nats/src/main/scala/zio/nats/Nats.scala`
- `zio-nats/src/main/scala/zio/nats/jetstream/JetStream.scala`

## Verification Commands

```bash
# Scala 2.13
sbt "++2.13.18; zioNatsTest/test"

# Scala 3
sbt "++3.8.1; zioNatsTest/test"
```

Expected: All tests pass on both versions
