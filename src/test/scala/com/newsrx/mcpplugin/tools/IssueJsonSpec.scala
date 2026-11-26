package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.ObjectMapper
import gitbucket.core.model.{Issue, IssueAssignee, IssueComment, Label, Milestone, Priority}
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.Date

@RunWith(classOf[JUnitRunner])
class IssueJsonSpec extends AnyFunSuite with Matchers {
  private val mapper = new ObjectMapper()
  private val created = new Date(0L)
  private val updated = new Date(1000L)

  test("issueNode includes Issue fields, comments, and related metadata") {
    val issue = Issue(
      "alice", "project", 12, "alice", Some(3), Some(4),
      "A task", Some("Issue body"), closed = false, created, updated, isPullRequest = false
    )
    val comment = IssueComment(
      "alice", "project", 12, 7, "comment", "bob", "Comment body", created, updated
    )
    val label = Label("alice", "project", 2, "bug", "ff0000")
    val assignee = IssueAssignee("alice", "project", 12, "carol")
    val milestone = Milestone("alice", "project", 3, "v1", Some("First release"), Some(updated), None)
    val priority = Priority("alice", "project", 4, "High", Some("Urgent"), false, 1, "ff0000")

    val node = IssueJson.issueNode(
      mapper, issue, Seq(comment), Seq(label), Seq(assignee), Some(milestone), Some(priority)
    )

    node.get("owner").asText() shouldBe "alice"
    node.get("repo").asText() shouldBe "project"
    node.get("number").asInt() shouldBe 12
    node.get("closed").asBoolean() shouldBe false
    node.get("milestone_id").asInt() shouldBe 3
    node.get("priority_id").asInt() shouldBe 4
    node.get("comment_count").asInt() shouldBe 1
    node.get("comments").get(0).get("body").asText() shouldBe "Comment body"
    node.get("comments").get(0).get("author").asText() shouldBe "bob"
    node.get("labels").get(0).get("name").asText() shouldBe "bug"
    node.get("assignees").get(0).asText() shouldBe "carol"
    node.get("milestone").get("title").asText() shouldBe "v1"
    node.get("priority").get("name").asText() shouldBe "High"
  }

  test("issueNode returns stable empty values when optional related data is absent") {
    val issue = Issue(
      "alice", "project", 12, "alice", None, None,
      "A task", None, closed = true, created, updated, isPullRequest = false
    )

    val node = IssueJson.issueNode(mapper, issue, Seq.empty, Seq.empty, Seq.empty, None, None)

    node.get("body").asText() shouldBe ""
    node.get("milestone_id").isNull shouldBe true
    node.get("priority_id").isNull shouldBe true
    node.get("comments").isEmpty shouldBe true
    node.get("labels").isEmpty shouldBe true
    node.get("assignees").isEmpty shouldBe true
    node.get("milestone").isNull shouldBe true
    node.get("priority").isNull shouldBe true
  }
}
