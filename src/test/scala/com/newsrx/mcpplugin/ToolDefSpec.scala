package com.newsrx.mcpplugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.mcp.{ToolDef, ToolRegistry}
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

@RunWith(classOf[JUnitRunner])
class ToolDefSpec extends AnyFunSuite with Matchers {

  val mapper = new ObjectMapper()

  // ── ToolRegistry ──────────────────────────────────────────────────────────

  test("ToolRegistry contains all expected tools") {
    val names = ToolRegistry.all(mapper).map(_.name).toSet
    // PR tools
    names should contain("list_pull_requests")
    names should contain("get_pull_request")
    names should contain("add_pr_comment")
    names should contain("close_pull_request")
    names should contain("reopen_pull_request")
    names should contain("get_pr_diff")
    // Issue tools
    names should contain("list_issues")
    names should contain("get_issue")
    names should contain("create_issue")
    names should contain("close_issue")
    names should contain("reopen_issue")
    names should contain("add_issue_comment")
    // Repo tools
    names should contain("list_repositories")
    names should contain("get_repository")
  }

  test("All tool names are unique") {
    val names = ToolRegistry.all(mapper).map(_.name)
    names.size shouldBe names.distinct.size
  }

  test("All tools have non-empty descriptions") {
    ToolRegistry.all(mapper).foreach { tool =>
      withClue(s"tool ${tool.name}") {
        tool.description should not be empty
      }
    }
  }

  test("ToolRegistry.find returns the tool by name") {
    ToolRegistry.find("list_pull_requests", mapper).map(_.name) shouldBe Some("list_pull_requests")
    ToolRegistry.find("nonexistent", mapper)                      shouldBe None
  }

  // ── listResult ────────────────────────────────────────────────────────────

  test("listResult produces valid tools/list response") {
    val result = ToolRegistry.listResult(mapper)
    result.has("tools") shouldBe true
    result.get("tools").isArray shouldBe true
    result.get("tools").size should be > 0
  }

  test("each tool in listResult has name, description and inputSchema") {
    val tools = ToolRegistry.listResult(mapper).get("tools")
    tools.forEach { tool =>
      withClue(s"tool ${tool.get("name")}") {
        tool.has("name")        shouldBe true
        tool.has("description") shouldBe true
        tool.has("inputSchema") shouldBe true
        tool.get("inputSchema").get("type").asText() shouldBe "object"
      }
    }
  }

  // ── inputSchema structure ─────────────────────────────────────────────────

  test("every tool inputSchema is an object schema") {
    ToolRegistry.all(mapper).foreach { tool =>
      val schema = tool.inputSchema(mapper)
      withClue(s"tool ${tool.name}") {
        schema.get("type").asText() shouldBe "object"
        schema.has("properties")    shouldBe true
      }
    }
  }

  test("PR tools require owner and repo") {
    val prTools = Seq("list_pull_requests", "get_pull_request", "add_pr_comment",
                      "close_pull_request", "reopen_pull_request", "get_pr_diff")
    prTools.foreach { toolName =>
      val tool     = ToolRegistry.find(toolName, mapper).get
      val schema   = tool.inputSchema(mapper)
      val required = schema.get("required")
      withClue(s"tool $toolName") {
        required.isArray shouldBe true
        val reqSet = (0 until required.size).map(required.get(_).asText()).toSet
        reqSet should contain("owner")
        reqSet should contain("repo")
      }
    }
  }

  test("get_pull_request requires number") {
    val tool   = ToolRegistry.find("get_pull_request", mapper).get
    val schema = tool.inputSchema(mapper)
    val reqSet = (0 until schema.get("required").size)
      .map(schema.get("required").get(_).asText()).toSet
    reqSet should contain("number")
  }

  test("create_issue requires title") {
    val tool   = ToolRegistry.find("create_issue", mapper).get
    val schema = tool.inputSchema(mapper)
    val reqSet = (0 until schema.get("required").size)
      .map(schema.get("required").get(_).asText()).toSet
    reqSet should contain("title")
  }

  test("add_pr_comment requires body") {
    val tool   = ToolRegistry.find("add_pr_comment", mapper).get
    val schema = tool.inputSchema(mapper)
    val reqSet = (0 until schema.get("required").size)
      .map(schema.get("required").get(_).asText()).toSet
    reqSet should contain("body")
  }

  // ── ToolDef helpers (via an anonymous inline tool) ────────────────────────

  private object TestTool extends ToolDef {
    val name        = "test_tool"
    val description = "tool for testing helpers"
    // Every tool must declare the scope it needs; the member is abstract so
    // that a new one cannot silently inherit the weaker requirement.
    val requiredScope = com.newsrx.mcpplugin.oauth.McpScope.Read

    def inputSchema(mapper: ObjectMapper) = objectSchema(mapper, Seq("x"),
      "x" -> strNode(mapper, "required string"))

    def execute(args: com.fasterxml.jackson.databind.JsonNode, account: gitbucket.core.model.Account,
                request: javax.servlet.http.HttpServletRequest, mapper: ObjectMapper) =
      mapper.createObjectNode()
  }

  test("str() extracts required string argument") {
    val args = mapper.readTree("""{"owner":"alice"}""")
    TestTool.str(args, "owner") shouldBe "alice"
  }

  test("str() throws when argument is missing") {
    val args = mapper.readTree("{}")
    an[IllegalArgumentException] should be thrownBy TestTool.str(args, "owner")
  }

  test("strOpt() returns None when argument is absent") {
    val args = mapper.readTree("{}")
    TestTool.strOpt(args, "state") shouldBe None
  }

  test("strOpt() returns None for empty string") {
    val args = mapper.readTree("""{"state":""}""")
    TestTool.strOpt(args, "state") shouldBe None
  }

  test("strOpt() returns Some for non-empty string") {
    val args = mapper.readTree("""{"state":"open"}""")
    TestTool.strOpt(args, "state") shouldBe Some("open")
  }

  test("int() extracts integer argument") {
    val args = mapper.readTree("""{"number":5}""")
    TestTool.int(args, "number") shouldBe 5
  }

  test("int() throws when argument is missing") {
    val args = mapper.readTree("{}")
    an[IllegalArgumentException] should be thrownBy TestTool.int(args, "number")
  }

  test("intOpt() returns default when argument is absent") {
    val args = mapper.readTree("{}")
    TestTool.intOpt(args, "limit", 30) shouldBe 30
  }

  test("bool() returns default false when absent") {
    val args = mapper.readTree("{}")
    TestTool.bool(args, "flag") shouldBe false
  }

  test("bool() reads true correctly") {
    val args = mapper.readTree("""{"flag":true}""")
    TestTool.bool(args, "flag") shouldBe true
  }

  test("textContent wraps string in MCP content array") {
    val result = TestTool.textContent(mapper, "hello world")
    result.has("content") shouldBe true
    val content = result.get("content")
    content.isArray shouldBe true
    content.get(0).get("type").asText() shouldBe "text"
    content.get(0).get("text").asText() shouldBe "hello world"
  }

  test("jsonContent serializes node as text in content array") {
    val data   = mapper.createObjectNode()
    data.put("key", "value")
    val result  = TestTool.jsonContent(mapper, data)
    val text    = result.get("content").get(0).get("text").asText()
    val parsed  = mapper.readTree(text)
    parsed.get("key").asText() shouldBe "value"
  }
}
