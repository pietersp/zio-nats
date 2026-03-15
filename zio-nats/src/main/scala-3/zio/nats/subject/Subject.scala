package zio.nats.subject

opaque type Subject = String

object Subject {
  def apply(s: String): Subject = s

  extension (s: Subject) def value: String = s
}
