package zio.nats.subject

opaque type Subject = String

object Subject {
  def apply(s: String): Subject = s

  /**
   * Parse a subject string, returning Left if it is null or blank.
   *
   * Use this for user-supplied input that may be invalid.
   */
  def parse(s: String): Either[String, Subject] =
    if (s == null || s.isBlank) Left(s"Invalid subject: '$s'")
    else Right(s)

  /**
   * Create a Subject without validation.
   *
   * Prefer [[parse]] for untrusted input. Use this only when the value is
   * statically known to be valid (e.g. constants).
   */
  def unsafe(s: String): Subject = s

  extension (s: Subject) def value: String = s
}
