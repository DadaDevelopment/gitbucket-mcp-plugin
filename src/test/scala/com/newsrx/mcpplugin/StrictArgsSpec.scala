package com.newsrx.mcpplugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.newsrx.mcpplugin.mcp.StrictArgs
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Controls for the argument contract of the mutating tools.
 *
 * These run offline with no GitBucket instance: the code under test is pure
 * Jackson handling, and it is the same code the tools call. Each case below
 * corresponds to a way the previous coercing helpers would have destroyed or
 * weakened a write.
 */
@RunWith(classOf[JUnitRunner])
class StrictArgsSpec extends AnyFunSuite with Matchers {

  private val mapper = new ObjectMapper()

  private def args(json: String) = mapper.readTree(json)

  // ── omitted vs intentionally empty ────────────────────────────────────────

  test("an omitted key is absent, not empty") {
    StrictArgs.textOpt(args("""{"title":"t"}"""), "body") shouldBe None
  }

  test("an explicitly empty string is supplied, not absent") {
    // The distinction that matters: clearing a body must reach the tool as a
    // request to clear it, while omitting it must leave the stored text alone.
    StrictArgs.textOpt(args("""{"body":""}"""), "body") shouldBe Some("")
  }

  test("a normal string round-trips unchanged") {
    StrictArgs.textOpt(args("""{"body":"hello"}"""), "body") shouldBe Some("hello")
  }

  // ── wrong JSON types are refused before any write ─────────────────────────

  test("an object body is refused instead of becoming empty text") {
    // The regression this whole helper exists for: `asText("")` turned `{}`
    // into "" and the tool then erased a real description.
    val thrown = the[IllegalArgumentException] thrownBy
      StrictArgs.textOpt(args("""{"body":{}}"""), "body")
    thrown.getMessage should include("must be a JSON string")
    thrown.getMessage should include("an object")
  }

  test("an array body is refused") {
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.textOpt(args("""{"body":[1,2]}"""), "body")
  }

  test("a numeric body is refused rather than stringified") {
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.textOpt(args("""{"body":42}"""), "body")
  }

  test("a boolean body is refused rather than stringified") {
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.textOpt(args("""{"body":true}"""), "body")
  }

  test("an explicit null body is refused, not treated as absent") {
    // Treating null as absent would silently turn "clear this" or a client
    // bug into "change nothing", which is a different operation either way.
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.textOpt(args("""{"body":null}"""), "body")
  }

  // ── Unicode and newlines survive ──────────────────────────────────────────

  test("unicode and newlines are preserved byte for byte") {
    val text = "перший рядок\nдругий\t— ok ✅\r\nкінець"
    val node = mapper.createObjectNode()
    node.put("body", text)
    StrictArgs.textOpt(node, "body") shouldBe Some(text)
  }

  // ── requiredText ──────────────────────────────────────────────────────────

  test("requiredText refuses an absent key") {
    // The exception is bound to a value first: chaining `getMessage` into the
    // infix `thrownBy` expression does not parse.
    val thrown = the[IllegalArgumentException] thrownBy
      StrictArgs.requiredText(args("""{}"""), "body")
    thrown.getMessage should include("missing required argument")
  }

  test("requiredText refuses an empty string") {
    // Blanking a comment is a deletion wearing an edit's clothes.
    val thrown = the[IllegalArgumentException] thrownBy
      StrictArgs.requiredText(args("""{"body":""}"""), "body")
    thrown.getMessage should include("must not be empty")
  }

  test("requiredText refuses a wrong type") {
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.requiredText(args("""{"body":{}}"""), "body")
  }

  test("requiredText accepts real text") {
    StrictArgs.requiredText(args("""{"body":"text"}"""), "body") shouldBe "text"
  }

  // ── commit id precondition ────────────────────────────────────────────────

  test("an absent precondition stays absent") {
    StrictArgs.commitIdOpt(args("""{}"""), "expected_head_sha") shouldBe None
  }

  test("a full 40-character hexadecimal id is accepted") {
    val sha = "0123456789abcdef0123456789abcdef01234567"
    sha.length shouldBe 40
    StrictArgs.commitIdOpt(args(s"""{"expected_head_sha":"$sha"}"""), "expected_head_sha") shouldBe Some(sha)
  }

  test("an abbreviated id is refused rather than compared as a prefix") {
    // A prefix never equals a stored full id, so accepting one would make the
    // precondition always fail — or, worse, be quietly dropped.
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.commitIdOpt(args("""{"expected_head_sha":"1f822ae"}"""), "expected_head_sha")
  }

  test("a non-hexadecimal id of the right length is refused") {
    val forty = "z" * 40
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.commitIdOpt(args(s"""{"expected_head_sha":"$forty"}"""), "expected_head_sha")
  }

  test("an empty precondition is refused, not discarded") {
    // The old `strOpt` dropped "" and performed an unguarded write.
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.commitIdOpt(args("""{"expected_head_sha":""}"""), "expected_head_sha")
  }

  test("an object precondition is refused, not discarded") {
    an[IllegalArgumentException] should be thrownBy
      StrictArgs.commitIdOpt(args("""{"expected_head_sha":{}}"""), "expected_head_sha")
  }
}
