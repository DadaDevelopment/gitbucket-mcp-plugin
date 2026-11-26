package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.ObjectMapper
import gitbucket.core.model.Milestone
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.Date

@RunWith(classOf[JUnitRunner])
class MilestoneJsonSpec extends AnyFunSuite with Matchers {
  private val mapper = new ObjectMapper()

  test("node includes milestone details, state, and issue counts") {
    val dueDate = Date.from(Instant.parse("2026-12-01T00:00:00Z"))
    val closedDate = Date.from(Instant.parse("2026-11-15T00:00:00Z"))
    val milestone = Milestone(
      "alice", "project", 5, "v1", Some("First release"), Some(dueDate), Some(closedDate)
    )

    val node = MilestoneJson.node(mapper, milestone, openIssues = 2, closedIssues = 3)

    node.get("number").asInt() shouldBe 5
    node.get("title").asText() shouldBe "v1"
    node.get("description").asText() shouldBe "First release"
    node.get("state").asText() shouldBe "closed"
    node.get("due_date").asText() shouldBe "2026-12-01T00:00:00Z"
    node.get("closed_at").asText() shouldBe "2026-11-15T00:00:00Z"
    node.get("open_issues").asInt() shouldBe 2
    node.get("closed_issues").asInt() shouldBe 3
  }

  test("node represents open milestones with absent optional fields and zero counts") {
    val milestone = Milestone("alice", "project", 6, "v2", None, None, None)

    val node = MilestoneJson.node(mapper, milestone, openIssues = 0, closedIssues = 0)

    node.get("state").asText() shouldBe "open"
    node.get("description").isNull shouldBe true
    node.get("due_date").isNull shouldBe true
    node.get("closed_at").isNull shouldBe true
    node.get("open_issues").asInt() shouldBe 0
    node.get("closed_issues").asInt() shouldBe 0
  }

  test("parseDueDate accepts ISO dates and offset timestamps") {
    MilestoneJson.parseDueDate("2026-12-01").toInstant.toString shouldBe "2026-12-01T00:00:00Z"
    MilestoneJson.parseDueDate("2026-12-01T09:00:00+09:00").toInstant.toString shouldBe
      "2026-12-01T00:00:00Z"
  }

  test("parseDueDate rejects malformed and impossible dates") {
    an[IllegalArgumentException] should be thrownBy MilestoneJson.parseDueDate("not-a-date")
    an[IllegalArgumentException] should be thrownBy MilestoneJson.parseDueDate("2026-02-30")
    an[IllegalArgumentException] should be thrownBy MilestoneJson.parseDueDate("2026-12-01T09:00:00")
  }

  test("create milestone rejects missing title, blank title, and invalid due date before database access") {
    val tool = new CreateMilestoneTool

    an[IllegalArgumentException] should be thrownBy tool.execute(
      mapper.readTree("""{"owner":"alice","repo":"project"}"""),
      null, null, mapper
    )
    an[IllegalArgumentException] should be thrownBy tool.execute(
      mapper.readTree("""{"owner":"alice","repo":"project","title":"  "}"""),
      null, null, mapper
    )
    an[IllegalArgumentException] should be thrownBy tool.execute(
      mapper.readTree("""{"owner":"alice","repo":"project","title":"v1","due_date":"invalid"}"""),
      null, null, mapper
    )
  }

  test("get and update milestone reject non-positive numbers before database access") {
    val args = mapper.readTree("""{"owner":"alice","repo":"project","number":0}""")

    an[IllegalArgumentException] should be thrownBy new GetMilestoneTool().execute(args, null, null, mapper)
    an[IllegalArgumentException] should be thrownBy new UpdateMilestoneTool().execute(args, null, null, mapper)
  }

  test("list milestones rejects unsupported state before database access") {
    val args = mapper.readTree("""{"owner":"alice","repo":"project","state":"pending"}""")

    an[IllegalArgumentException] should be thrownBy new ListMilestonesTool().execute(args, null, null, mapper)
  }

  test("update milestone rejects unsupported state before database access") {
    val args = mapper.readTree("""{"owner":"alice","repo":"project","number":1,"state":"pending"}""")

    an[IllegalArgumentException] should be thrownBy new UpdateMilestoneTool().execute(args, null, null, mapper)
  }
}
