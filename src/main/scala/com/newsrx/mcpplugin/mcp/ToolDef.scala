package com.newsrx.mcpplugin.mcp

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.oauth.McpScope
import gitbucket.core.model.Account
import javax.servlet.http.HttpServletRequest

/** A single MCP tool. */
trait ToolDef {
  def name: String
  def description: String

  /**
   * The OAuth scope a caller needs to invoke this tool.
   *
   * Abstract rather than defaulting to [[McpScope.Read]]: a default would be
   * the weaker requirement, so a new mutating tool that forgot to declare
   * would be callable with a read-only grant and nothing would say so. This
   * way it does not compile.
   *
   * It is one value feeding two places — the advertised policy in
   * `tools/list` and the check in the dispatcher — so the two cannot drift.
   */
  def requiredScope: McpScope

  /** JSON Schema for the `arguments` object. */
  def inputSchema(mapper: ObjectMapper): ObjectNode

  /**
   * Execute the tool.
   * @param args  the `arguments` node from the tools/call request
   * @param account authenticated GitBucket account
   * @param request current HTTP request (gives access to DB session via TransactionFilter)
   * @return result node placed under `content[0].text` in the MCP response
   */
  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode

  // ── Schema helpers ────────────────────────────────────────────────────────

  /** Build a simple object schema with typed properties and required list. */
  def objectSchema(
    mapper: ObjectMapper,
    required: Seq[String],
    props: (String, ObjectNode)*
  ): ObjectNode = {
    val s = mapper.createObjectNode()
    s.put("type", "object")
    val propsNode = mapper.createObjectNode()
    props.foreach { case (name, node) => propsNode.set[ObjectNode](name, node) }
    s.set[ObjectNode]("properties", propsNode)
    val reqArray = mapper.createArrayNode()
    required.foreach(reqArray.add)
    s.set[ObjectNode]("required", reqArray)
    s
  }

  def strNode(mapper: ObjectMapper, description: String): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("type", "string")
    n.put("description", description)
    n
  }

  def intNode(mapper: ObjectMapper, description: String): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("type", "integer")
    n.put("description", description)
    n
  }

  def boolNode(mapper: ObjectMapper, description: String): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("type", "boolean")
    n.put("description", description)
    n
  }

  def strEnum(mapper: ObjectMapper, description: String, values: String*): ObjectNode = {
    val n = mapper.createObjectNode()
    n.put("type", "string")
    n.put("description", description)
    val arr = mapper.createArrayNode()
    values.foreach(arr.add)
    n.set[ObjectNode]("enum", arr)
    n
  }

  // ── Argument accessors ────────────────────────────────────────────────────

  def str(args: JsonNode, key: String): String =
    Option(args.get(key)).map(_.asText("")).getOrElse(
      throw new IllegalArgumentException(s"missing required argument: $key")
    )

  def strOpt(args: JsonNode, key: String): Option[String] =
    Option(args.get(key)).map(_.asText("")).filter(_.nonEmpty)

  def int(args: JsonNode, key: String): Int =
    Option(args.get(key)).map(_.asInt(-1)).filter(_ >= 0).getOrElse(
      throw new IllegalArgumentException(s"missing or invalid argument: $key")
    )

  def intOpt(args: JsonNode, key: String, default: Int): Int =
    Option(args.get(key)).map(_.asInt(default)).getOrElse(default)

  def bool(args: JsonNode, key: String, default: Boolean = false): Boolean =
    Option(args.get(key)).map(_.asBoolean(default)).getOrElse(default)

  // ── Result helpers ────────────────────────────────────────────────────────

  /** Wrap a result string as a MCP text content response. */
  def textContent(mapper: ObjectMapper, text: String): JsonNode = {
    val root    = mapper.createObjectNode()
    val content = mapper.createArrayNode()
    val item    = mapper.createObjectNode()
    item.put("type", "text")
    item.put("text", text)
    content.add(item)
    root.set[ObjectNode]("content", content)
    root
  }

  def jsonContent(mapper: ObjectMapper, data: JsonNode): JsonNode = {
    val root    = mapper.createObjectNode()
    val content = mapper.createArrayNode()
    val item    = mapper.createObjectNode()
    item.put("type", "text")
    item.put("text", mapper.writeValueAsString(data))
    content.add(item)
    root.set[ObjectNode]("content", content)
    root
  }
}

/** Registry of all available MCP tools. */
object ToolRegistry {
  def all(mapper: ObjectMapper): Seq[ToolDef] = {
    import com.newsrx.mcpplugin.tools._
    Seq(
      // Pull Request tools
      new ListPullRequestsTool,
      new GetPullRequestTool,
      new AddPrCommentTool,
      new ClosePullRequestTool,
      new ReopenPullRequestTool,
      new GetPrDiffTool,
      new GetThreadCommentsTool,
      new UpdatePullRequestTool,
      new MarkReadyForReviewTool,
      // Issue tools
      new ListIssuesTool,
      new GetIssueTool,
      new CreateIssueTool,
      new CloseIssueTool,
      new ReopenIssueTool,
      new AddIssueCommentTool,
      new UpdateOwnCommentTool,
      // Repository tools
      new ListRepositoriesTool,
      new GetRepositoryTool,
      // Identity
      new WhoAmITool
    )
  }

  /** Build the tools/list result node. */
  def listResult(mapper: ObjectMapper): JsonNode = {
    val root  = mapper.createObjectNode()
    val tools = mapper.createArrayNode()
    all(mapper).foreach { tool =>
      val t = mapper.createObjectNode()
      t.put("name", tool.name)
      t.put("description", tool.description)
      t.set[ObjectNode]("inputSchema", tool.inputSchema(mapper))
      tools.add(t)
    }
    root.set[ObjectNode]("tools", tools)
    root
  }

  def find(name: String, mapper: ObjectMapper): Option[ToolDef] =
    all(mapper).find(_.name == name)
}
