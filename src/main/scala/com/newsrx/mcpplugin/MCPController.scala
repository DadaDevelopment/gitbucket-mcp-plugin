package com.newsrx.mcpplugin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.mcp.{JsonRpc, ToolRegistry}
import com.newsrx.mcpplugin.oauth._
import com.newsrx.mcpplugin.tools.{AuthorizationError, SessionSupport}
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
    with SessionSupport
    with OAuthEndpoints {

  // ── Routes ────────────────────────────────────────────────────────────────

  post("/plugin-mcp/") {
    contentType = "application/json; charset=UTF-8"
    withAuth { caller =>
      handleJsonRpc(caller)
    }
  }

  // Some MCP clients POST without trailing slash
  post("/plugin-mcp") {
    contentType = "application/json; charset=UTF-8"
    withAuth { caller =>
      handleJsonRpc(caller)
    }
  }

  get("/plugin-mcp/sse") {
    withAuth { caller =>
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

  private def handleJsonRpc(caller: AuthenticatedCaller): String = {
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
            handleToolCall(caller, req)

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

  private def handleToolCall(caller: AuthenticatedCaller, req: JsonRpc.Request): String = {
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
      case Some(tool) if !caller.permits(tool.requiredScope) =>
        // Distinct from an authentication failure, and distinct in the HTTP
        // status too: the credential is good, the consent is not wide enough.
        // A client that is told "authenticate again" for this will loop.
        oauthConfig.foreach { config =>
          response.setHeader("WWW-Authenticate",
            Metadata.challenge(config, Some(ChallengeError.InsufficientScope)))
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN)
        JsonRpc.errorResponse(mapper, req.id, JsonRpc.McpError - 3,
          s"insufficient_scope: $toolName requires ${tool.requiredScope.name}")

      case Some(tool) =>
        val args = Option(params.get("arguments")).getOrElse(mapper.createObjectNode())
        try {
          val result = tool.execute(args, caller.account, request, mapper)
          JsonRpc.successResponse(mapper, req.id, result)
        } catch {
          // A denial is its own outcome, not an internal failure: reporting it
          // as InternalError would tell a caller the server broke when in fact
          // the server refused.
          case e: AuthorizationError =>
            JsonRpc.errorResponse(mapper, req.id, JsonRpc.McpError - 2, e.getMessage)
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
    // Reported from the descriptor that ships in the JAR instead of a second
    // hardcoded literal: the two disagreed in v0.1.1.
    serverInfo.put("version", PluginDescriptor.get("pluginVersion"))

    root.set[ObjectNode]("capabilities", capabilities)
    root.set[ObjectNode]("serverInfo",   serverInfo)
    root.put("protocolVersion", "2025-03-26")
    root
  }

  // ── Authentication ────────────────────────────────────────────────────────

  private def withAuth(f: AuthenticatedCaller => String): String = {
    authenticateCaller() match {
      case Right(caller) => f(caller)
      case Left(error) =>
        contentType = "application/json; charset=UTF-8"
        response.setStatus(error.status)
        response.setHeader("WWW-Authenticate", challengeHeader(error))
        JsonRpc.errorResponse(mapper, None,
          if (error == ChallengeError.None_) JsonRpc.McpError else JsonRpc.McpError - 4,
          if (error == ChallengeError.None_) "authentication required" else error.description)
    }
  }

  /**
   * The challenge offered when authentication fails.
   *
   * Both schemes are offered when OAuth is configured, so a Basic client is
   * not pushed into a browser flow it cannot perform and an OAuth client can
   * still discover where to authorize.
   */
  private def challengeHeader(error: ChallengeError): String = {
    val basic = "Basic realm=\"GitBucket MCP\""
    oauthConfig match {
      case Some(config) =>
        val bearer = Metadata.challenge(config,
          if (error == ChallengeError.None_) None else Some(error))
        s"$bearer, $basic"
      case None => basic
    }
  }

  /**
   * Resolve the caller from the Authorization header.
   *
   * One header, one scheme, one answer. A Bearer token that does not resolve
   * is an error and never a reason to look somewhere else: falling back to
   * the browser session would mean a stale or stolen token silently acting as
   * whoever happens to be signed in, and falling back to Basic would let a
   * client downgrade itself out of its own scope restrictions.
   */
  private def authenticateCaller(): Either[ChallengeError, AuthenticatedCaller] =
    Option(request.getHeader("Authorization")).map(_.trim).filter(_.nonEmpty) match {
      case None => Left(ChallengeError.None_)
      case Some(header) if header.regionMatches(true, 0, "Bearer ", 0, 7) =>
        bearerCaller(header.substring(7).trim)
      case Some(header) if header.regionMatches(true, 0, "Basic ", 0, 6) =>
        basicCaller(header.substring(6).trim)
      case Some(_) => Left(ChallengeError.InvalidRequest)
    }

  private def bearerCaller(token: String): Either[ChallengeError, AuthenticatedCaller] =
    oauthConfig match {
      case None => Left(ChallengeError.InvalidToken)
      case Some(_) if token.isEmpty => Left(ChallengeError.InvalidRequest)
      case Some(config) =>
        OAuthStore.resolveAccessToken(oauthConn, token, now()) match {
          case Left(_) => Left(ChallengeError.InvalidToken)
          // The grant records which resource it was minted for, and that is
          // only a fact in a table until it is compared with the resource this
          // server actually is. A token outlives the request that created it:
          // a reconfigured deployment, or a neighbouring one, hands over a
          // perfectly valid unexpired credential for somewhere else. Refused
          // here, before any tool is chosen, and reported exactly like any
          // other unusable token so it reveals nothing.
          case Right(grant) if grant.resource != config.resource =>
            Left(ChallengeError.InvalidToken)
          case Right(grant) =>
            implicit val s = Database.getSession(request)
            // The account is resolved now, not trusted from the grant row: an
            // account removed or disabled since consent must stop working
            // here, without waiting for the grant to be revoked.
            getAccountByUserName(grant.userName, includeRemoved = false) match {
              case Some(account) => Right(AuthenticatedCaller.oauth(account, grant))
              case None => Left(ChallengeError.InvalidToken)
            }
        }
    }

  private def basicCaller(encoded: String): Either[ChallengeError, AuthenticatedCaller] =
    try {
      val decoded = new String(Base64.getDecoder.decode(encoded), "UTF-8")
      val colon = decoded.indexOf(':')
      if (colon < 0) Left(ChallengeError.InvalidRequest)
      else {
        implicit val session = Database.getSession(request)
        val settings = loadSystemSettings()
        authenticate(settings, decoded.substring(0, colon), decoded.substring(colon + 1)) match {
          case Some(account) => Right(AuthenticatedCaller.basic(account))
          case None => Left(ChallengeError.InvalidToken)
        }
      }
    } catch {
      case _: Exception => Left(ChallengeError.InvalidRequest)
    }
}
