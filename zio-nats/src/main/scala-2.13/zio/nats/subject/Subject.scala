package zio.nats.subject

class Subject private (val value: String) extends AnyVal {
  override def toString: String = value
}

object Subject {
  def apply(s: String): Subject = new Subject(s)

  /**
   * Parse a subject string, returning Left if it is null or empty.
   *
   * Use this for user-supplied input that may be invalid.
   */
  def parse(s: String): Either[String, Subject] =
    if (s == null || s.isEmpty) Left(s"Invalid subject: '$s'")
    else Right(new Subject(s))

  /**
   * Create a Subject without validation.
   *
   * Prefer [[parse]] for untrusted input. Use this only when the value is
   * statically known to be valid (e.g. constants).
   */
  def unsafe(s: String): Subject = new Subject(s)
}
