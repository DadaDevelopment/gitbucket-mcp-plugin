package com.newsrx.mcpplugin

import com.newsrx.mcpplugin.mcp.Timestamps
import java.util.{Date, TimeZone}
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Controls for the published timestamp format.
 *
 * The regression these exist for: every date went through
 * `java.util.Date.toString`, which renders in the JVM's default zone with
 * locale-dependent names ("Fri Oct 23 15:38:41 EEST 2026"). It read as a
 * plausible timestamp, so a non-empty-string assertion passed, while being
 * unparseable for a client and incomparable with GitBucket's own ISO 8601
 * output. The live gate refused to compare it once it ran on a host in
 * Europe/Kiev.
 *
 * The default zone is changed inside these cases on purpose: a formatter that
 * depends on it must fail here.
 */
@RunWith(classOf[JUnitRunner])
class TimestampsSpec extends AnyFunSuite with Matchers {

  private def withZone[A](id: String)(body: => A): A = {
    val previous = TimeZone.getDefault
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(id))
      body
    } finally TimeZone.setDefault(previous)
  }

  private val epoch = new Date(0L)
  private val instant = new Date(1792759121000L) // 2026-10-23T12:38:41Z

  test("the epoch formats as an ISO 8601 instant in UTC") {
    Timestamps.iso8601(epoch) shouldBe "1970-01-01T00:00:00Z"
  }

  test("a known instant formats to the second, with a Z suffix") {
    Timestamps.iso8601(instant) shouldBe "2026-10-23T12:38:41Z"
  }

  test("the output does not depend on the JVM's default time zone") {
    // The whole point: Date.toString would answer differently in each of
    // these, which is what made the field unusable.
    val renderings = Seq("UTC", "Europe/Kiev", "America/Los_Angeles", "Asia/Kolkata", "Pacific/Chatham")
      .map(z => withZone(z)(Timestamps.iso8601(instant)))
    renderings.distinct shouldBe Seq("2026-10-23T12:38:41Z")
  }

  test("the output does not depend on the JVM's default locale") {
    val previous = java.util.Locale.getDefault
    try {
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("uk-UA"))
      Timestamps.iso8601(instant) shouldBe "2026-10-23T12:38:41Z"
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ja-JP"))
      Timestamps.iso8601(instant) shouldBe "2026-10-23T12:38:41Z"
    } finally java.util.Locale.setDefault(previous)
  }

  test("every output matches the shape GitBucket's own API publishes") {
    val pattern = """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""".r
    Seq(epoch, instant, new Date(), new Date(253402300799000L)).foreach { d =>
      withClue(s"$d: ") { pattern.findFirstIn(Timestamps.iso8601(d)) should not be empty }
    }
  }

  test("a date before the epoch still formats as an instant") {
    Timestamps.iso8601(new Date(-1000L)) shouldBe "1969-12-31T23:59:59Z"
  }
}
