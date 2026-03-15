package zio.nats.subject

opaque type Subject = String

object Subject {
  def apply(s: String): Subject = s
  
  extension (s: Subject) def value: String = s
  
  def fromString(s: String): Subject = s
}

given Conversion[String, Subject] = Subject(_)
