package zio.nats.subject

class Subject private (val value: String) extends AnyVal {
  override def toString: String = value
}

object Subject {
  def apply(s: String): Subject = new Subject(s)
}
