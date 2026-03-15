package zio.nats.subject

class Subject(val value: String) extends AnyRef {
  override def equals(other: Any): Boolean = other match {
    case that: Subject => this.value == that.value
    case _ => false
  }
  override def hashCode: Int = value.hashCode
  override def toString: String = s"Subject($value)"
}

object Subject {
  def apply(s: String): Subject = new Subject(s)
}
