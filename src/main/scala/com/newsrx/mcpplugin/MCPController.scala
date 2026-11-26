package com.newsrx.mcpplugin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.mcp.{JsonRpc, ToolRegistry}
import com.newsrx.mcpplugin.tools.SessionSupport
import gitbucket.core.controller.ControllerBase
import gitbucket.core.model.Account
import gitbucket.core.service.{AccountService, RepositoryService, SystemSettingsService}
import gitbucket.core.servlet.Database
import java.util.Base64
import javax.servlet.http.HttpServletResponse

class MCPController
    extends ControllerBase
    with AccountService
    with RepositoryService
    with SystemSettingsService
    with SessionSupport {

  // ── Routes ────────────────────────────────────────────────────────────────

  post("/plugin-mcp/") {
    contentType = "application/json; charset=UTF-8"
    withAuth { account =>
      handleJsonRpc(account)
    }
  }

  // Some MCP clients POST without trailing slash
  post("/plugin-mcp") {
    contentType = "application/json; charset=UTF-8"
    withAuth { account =>
      handleJsonRpc(account)
    }
  }

  get("/plugin-mcp/sse") {
    withAuth { account =>
      contentType = "text/event-stream; charset=UTF-8"
      response.setHeader("Cache-Control", "no-cache")
      response.setHeader("X-Accel-Buffering", "no")
      // Send an endpoint event as required by the MCP SSE transport spec so
      // that clients know where to POST their requests.
      val endpointUrl = s"${baseUrl(request)}/plugin-mcp/"
      s"event: endpoint\ndata: $endpointUrl\n\n"
    }
  }

  get("/plugin-mcp/health") {
    contentType = "application/json; charset=UTF-8"
    """{"status":"ok","protocol":"MCP","transport":"Streamable HTTP"}"""
  }

  // ── JSON-RPC dispatch ─────────────────────────────────────────────────────

  private def handleJsonRpc(account: Account): String = {
    val body = request.body
    if (body.isEmpty) {
      return JsonRpc.errorResponse(mapper, None, JsonRpc.InvalidRequest, "empty request body")
    }

    JsonRpc.parseRequest(mapper, body) match {
      case Left(err) =>
        JsonRpc.errorResponse(mapper, None, JsonRpc.ParseError, err)

      case Right(req) =>
        req.method match {
          case "initialize" =>
            JsonRpc.successResponse(mapper, req.id, buildCapabilities())

          case "initialized" | "notifications/initialized" =>
            // Notification — no response needed; send empty result
            JsonRpc.successResponse(mapper, req.id, mapper.createObjectNode())

          case "ping" =>
            JsonRpc.successResponse(mapper, req.id, mapper.createObjectNode())

          case "tools/list" =>
            JsonRpc.successResponse(mapper, req.id, ToolRegistry.listResult(mapper))

          case "tools/call" =>
            handleToolCall(account, req)

          case "resources/list" =>
            // Resources not implemented — return empty list
            val root = mapper.createObjectNode()
            root.set[ObjectNode]("resources", mapper.createArrayNode())
            JsonRpc.successResponse(mapper, req.id, root)

          case "prompts/list" =>
            // Prompts not implemented — return empty list
            val root = mapper.createObjectNode()
            root.set[ObjectNode]("prompts", mapper.createArrayNode())
            JsonRpc.successResponse(mapper, req.id, root)

          case unknown =>
            JsonRpc.errorResponse(
              mapper, req.id, JsonRpc.MethodNotFound,
              s"unknown method: $unknown"
            )
        }
    }
  }

  private def handleToolCall(account: Account, req: JsonRpc.Request): String = {
    val params = req.params.getOrElse(mapper.createObjectNode())
    val toolName = Option(params.get("name")).map(_.asText("")).getOrElse("")
    if (toolName.isEmpty) {
      return JsonRpc.errorResponse(
        mapper, req.id, JsonRpc.InvalidParams, "missing 'name' in params"
      )
    }

    ToolRegistry.find(toolName, mapper) match {
      case None =>
        JsonRpc.errorResponse(
          mapper, req.id, JsonRpc.MethodNotFound,
          s"unknown tool: $toolName"
        )
      case Some(tool) =>
        val args = Option(params.get("arguments")).getOrElse(mapper.createObjectNode())
        try {
          val result = tool.execute(args, account, request, mapper)
          JsonRpc.successResponse(mapper, req.id, result)
        } catch {
          case e: NoSuchElementException =>
            JsonRpc.errorResponse(mapper, req.id, JsonRpc.McpError - 1, e.getMessage)
          case e: IllegalArgumentException =>
            JsonRpc.errorResponse(mapper, req.id, JsonRpc.InvalidParams, e.getMessage)
          case e: Exception =>
            JsonRpc.errorResponse(
              mapper, req.id, JsonRpc.InternalError,
              s"tool execution failed: ${e.getMessage}"
            )
        }
    }
  }

  // ── MCP capabilities ──────────────────────────────────────────────────────

  private def buildCapabilities() = {
    val root         = mapper.createObjectNode()
    val capabilities = mapper.createObjectNode()

    val toolsCap = mapper.createObjectNode()
    toolsCap.put("listChanged", false)
    capabilities.set[ObjectNode]("tools", toolsCap)

    val resourcesCap = mapper.createObjectNode()
    resourcesCap.put("subscribe", false)
    resourcesCap.put("listChanged", false)
    capabilities.set[ObjectNode]("resources", resourcesCap)

    val promptsCap = mapper.createObjectNode()
    promptsCap.put("listChanged", false)
    capabilities.set[ObjectNode]("prompts", promptsCap)

    val serverInfo = mapper.createObjectNode()
    serverInfo.put("name",    "gitbucket-mcp-plugin")
    serverInfo.put("version", "0.1.2")

    root.set[ObjectNode]("capabilities", capabilities)
    root.set[ObjectNode]("serverInfo",   serverInfo)
    root.put("protocolVersion", "2025-03-26")
    root
  }

  // ── Authentication ────────────────────────────────────────────────────────

  private def withAuth(f: Account => String): String = {
    authenticate() match {
      case None =>
        response.setHeader("WWW-Authenticate", "Basic realm=\"GitBucket MCP\"")
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
        contentType = "application/json; charset=UTF-8"
        JsonRpc.errorResponse(
          mapper, None, JsonRpc.McpError, "authentication required"
        )
      case Some(account) =>
        f(account)
    }
  }

  /** Extract and validate Basic auth credentials. */
  private def authenticate(): Option[Account] =
    Option(request.getHeader("Authorization")).flatMap { header =>
      if (!header.startsWith("Basic ")) return None
      try {
        val decoded = new String(Base64.getDecoder.decode(header.drop(6).trim), "UTF-8")
        val colon   = decoded.indexOf(':')
        if (colon < 0) return None
        val username = decoded.substring(0, colon)
        val password = decoded.substring(colon + 1)
        implicit val session = Database.getSession(request)
        val settings = loadSystemSettings()
        authenticate(settings, username, password)
      } catch {
        case _: Exception => None
      }
    }
}
