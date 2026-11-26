package com.newsrx.mcpplugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.newsrx.mcpplugin.mcp.ToolRegistry
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

@RunWith(classOf[JUnitRunner])
class ToolArgParseSpec extends AnyFunSuite with Matchers {

  val mapper = new ObjectMapper()

  private def requiredFields(toolName: String): Set[String] = {
    val schema = ToolRegistry.find(toolName, mapper).get.inputSchema(mapper)
    val req    = schema.get("required")
    if (req == null || !req.isArray) Set.empty
    else (0 until req.size).map(req.get(_).asText()).toSet
  }

  private def propertyNames(toolName: String): Set[String] = {
    val schema = ToolRegistry.find(toolName, mapper).get.inputSchema(mapper)
    val props  = schema.get("properties")
    if (props == null) Set.empty
    else {
      val it = props.fieldNames()
      val buf = scala.collection.mutable.Set.empty[String]
      while (it.hasNext) buf += it.next()
      buf.toSet
    }
  }

  // ── list_pull_requests ────────────────────────────────────────────────────

  test("list_pull_requests has owner + repo as required; state + limit as optional") {
    requiredFields("list_pull_requests") shouldBe Set("owner", "repo")
    propertyNames("list_pull_requests") should contain allOf ("owner", "repo", "state", "limit")
  }

  test("list_pull_requests state property is an enum of open/closed/all") {
    val schema = ToolRegistry.find("list_pull_requests", mapper).get.inputSchema(mapper)
    val state  = schema.get("properties").get("state")
    val values = (0 until state.get("enum").size).map(state.get("enum").get(_).asText()).toSet
    values shouldBe Set("open", "closed", "all")
  }

  // ── get_pull_request ──────────────────────────────────────────────────────

  test("get_pull_request requires owner, repo and number") {
    requiredFields("get_pull_request") shouldBe Set("owner", "repo", "number")
  }

  test("get_pull_request number property has type integer") {
    val schema = ToolRegistry.find("get_pull_request", mapper).get.inputSchema(mapper)
    schema.get("properties").get("number").get("type").asText() shouldBe "integer"
  }

  // ── add_pr_comment ────────────────────────────────────────────────────────

  test("add_pr_comment requires owner, repo, number and body") {
    requiredFields("add_pr_comment") should contain allOf ("owner", "repo", "number", "body")
  }

  // ── close_pull_request ────────────────────────────────────────────────────

  test("close_pull_request requires owner, repo and number; reason is optional") {
    requiredFields("close_pull_request") shouldBe Set("owner", "repo", "number")
    propertyNames("close_pull_request") should contain("reason")
  }

  // ── get_pr_diff ───────────────────────────────────────────────────────────

  test("get_pr_diff requires owner, repo and number") {
    requiredFields("get_pr_diff") shouldBe Set("owner", "repo", "number")
  }

  // ── list_issues ───────────────────────────────────────────────────────────

  test("list_issues has owner + repo as required") {
    requiredFields("list_issues") shouldBe Set("owner", "repo")
  }

  test("list_issues state is an enum") {
    val schema = ToolRegistry.find("list_issues", mapper).get.inputSchema(mapper)
    val state  = schema.get("properties").get("state")
    state.has("enum") shouldBe true
  }

  // ── create_issue ──────────────────────────────────────────────────────────

  test("create_issue requires owner, repo and title") {
    requiredFields("create_issue") shouldBe Set("owner", "repo", "title")
    propertyNames("create_issue") should contain("body")
  }

  // ── close_issue ───────────────────────────────────────────────────────────

  test("close_issue requires owner, repo and number") {
    requiredFields("close_issue") shouldBe Set("owner", "repo", "number")
  }

  // ── add_issue_comment ─────────────────────────────────────────────────────

  test("add_issue_comment requires owner, repo, number and body") {
    requiredFields("add_issue_comment") should contain allOf ("owner", "repo", "number", "body")
  }

  // ── Milestone tools ───────────────────────────────────────────────────────

  test("get_milestone requires owner, repo and number") {
    requiredFields("get_milestone") shouldBe Set("owner", "repo", "number")
  }

  test("list_milestones has owner and repo required with open/closed/all state") {
    requiredFields("list_milestones") shouldBe Set("owner", "repo")
    val schema = ToolRegistry.find("list_milestones", mapper).get.inputSchema(mapper)
    val state = schema.get("properties").get("state")
    val values = (0 until state.get("enum").size).map(state.get("enum").get(_).asText()).toSet
    values shouldBe Set("open", "closed", "all")
  }

  test("create_milestone requires owner, repo and title") {
    requiredFields("create_milestone") shouldBe Set("owner", "repo", "title")
    propertyNames("create_milestone") should contain allOf ("description", "due_date")
  }

  test("update_milestone requires owner, repo and number with optional fields") {
    requiredFields("update_milestone") shouldBe Set("owner", "repo", "number")
    propertyNames("update_milestone") should contain allOf ("title", "description", "due_date", "state")
  }

  test("update_milestone state is an open/closed enum") {
    val schema = ToolRegistry.find("update_milestone", mapper).get.inputSchema(mapper)
    val state = schema.get("properties").get("state")
    val values = (0 until state.get("enum").size).map(state.get("enum").get(_).asText()).toSet
    values shouldBe Set("open", "closed")
  }

  // ── list_repositories ─────────────────────────────────────────────────────

  test("list_repositories requires owner") {
    requiredFields("list_repositories") shouldBe Set("owner")
    propertyNames("list_repositories") should contain("without_forked")
  }

  test("list_repositories without_forked has type boolean") {
    val schema = ToolRegistry.find("list_repositories", mapper).get.inputSchema(mapper)
    schema.get("properties").get("without_forked").get("type").asText() shouldBe "boolean"
  }

  // ── get_repository ────────────────────────────────────────────────────────

  test("get_repository requires owner and repo") {
    requiredFields("get_repository") shouldBe Set("owner", "repo")
  }

  // ── Schema completeness ───────────────────────────────────────────────────

  test("all required fields are declared as properties too") {
    ToolRegistry.all(mapper).foreach { tool =>
      val schema   = tool.inputSchema(mapper)
      val required = schema.get("required")
      val props    = schema.get("properties")
      if (required != null && required.isArray && props != null) {
        (0 until required.size).foreach { i =>
          val field = required.get(i).asText()
          withClue(s"tool ${tool.name} required field '$field' not in properties") {
            props.has(field) shouldBe true
          }
        }
      }
    }
  }

  test("all property nodes have a type field") {
    ToolRegistry.all(mapper).foreach { tool =>
      val schema = tool.inputSchema(mapper)
      val props  = schema.get("properties")
      if (props != null) {
        val it = props.fields()
        while (it.hasNext) {
          val entry = it.next()
          withClue(s"tool ${tool.name} property '${entry.getKey}' missing type") {
            entry.getValue.has("type") shouldBe true
          }
        }
      }
    }
  }
}
