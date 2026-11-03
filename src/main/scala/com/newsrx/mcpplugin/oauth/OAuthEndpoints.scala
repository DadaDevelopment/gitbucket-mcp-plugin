package com.newsrx.mcpplugin.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import gitbucket.core.controller.ControllerBase
import gitbucket.core.servlet.Database
import java.sql.Connection
import java.time.Instant
import javax.servlet.http.HttpSession

/** A started authorization, held in the user's session until they decide. */
private[oauth] final case class PendingAuthorization(
  clientId: String,
  clientName: String,
  redirectUri: String,
  scopes: Set[String],
  state: Option[String],
  codeChallenge: String,
  resource: Option[String]
)

/**
 * The authorization server's HTTP surface.
 *
 * Mixed into the MCP controller so it shares one route table and one database
 * session, which is what lets a code be consumed and its token issued inside
 * the same transaction.
 *
 * The pending request lives in the user's HTTP session rather than in hidden
 * form fields. A hidden `code_challenge` would be a value the browser could
 * be made to change between the consent screen and the decision, and the
 * whole point of PKCE is that the challenge is fixed when authorization
 * starts. The form carries only an opaque key and a CSRF token.
 */
trait OAuthEndpoints extends ControllerBase {
  self: { def mapper: ObjectMapper } =>

  private val PendingPrefix = "mcp-oauth-pending:"
  private val CsrfAttribute = "mcp-oauth-csrf"

  /** Resolved once per request; absent means OAuth is not configured. */
  protected def oauthConfig: Option[OAuthConfig] =
    OAuthConfig.configuredBaseUrl().flatMap(OAuthConfig.fromBaseUrl(_).toOption)

  protected def oauthConn: Connection = Database.getSession(request).conn

  protected def now(): Instant = Instant.now()

  /** Overridable so tests can drive the fetcher without a network. */
  protected def fetchClient(clientId: String): Either[ClientMetadataError, OAuthClient] =
    ClientMetadata.fetch(mapper, clientId)

  // ── discovery ─────────────────────────────────────────────────────────────

  get("/.well-known/oauth-protected-resource") {
    withConfig { config =>
      contentType = "application/json; charset=UTF-8"
      mapper.writeValueAsString(Metadata.protectedResource(mapper, config))
    }
  }

  get("/.well-known/oauth-authorization-server") {
    withConfig { config =>
      contentType = "application/json; charset=UTF-8"
      mapper.writeValueAsString(Metadata.authorizationServer(mapper, config))
    }
  }

  // ── authorize ─────────────────────────────────────────────────────────────

  get("/plugin-mcp/oauth/authorize") {
    withConfig { config =>
      context.loginAccount match {
        // Not signed in: GitBucket's own login, then back here. The plugin
        // never sees a password, and the account that consents is whoever
        // GitBucket says is signed in.
        case None =>
          redirect(s"/signin?redirect=${urlEncode(request.getRequestURI + queryString)}")
        case Some(account) =>
          startAuthorization(config, account.userName)
      }
    }
  }

  post("/plugin-mcp/oauth/authorize") {
    withConfig { config =>
      context.loginAccount match {
        case None => halt(401, "not signed in")
        case Some(account) => completeAuthorization(config, account.userName)
      }
    }
  }

  private def startAuthorization(config: OAuthConfig, userName: String): Any = {
    val clientId = param("client_id")
    val redirectUri = param("redirect_uri")
    val responseType = param("response_type")
    val challenge = param("code_challenge")
    val method = param("code_challenge_method")
    val resource = optionalParam("resource")
    val state = optionalParam("state")

    // Errors before a validated redirect_uri cannot be sent to the client:
    // redirecting to an unvalidated URI is how an authorization server
    // becomes an open redirector.
    if (clientId.isEmpty || redirectUri.isEmpty)
      return badRequest("client_id and redirect_uri are required")

    fetchClient(clientId) match {
      case Left(err) => badRequest(s"client_id rejected: ${err.message}")
      case Right(client) if !client.permits(redirectUri) =>
        badRequest("redirect_uri is not registered for this client")
      case Right(client) =>
        // From here the redirect URI is trusted, so protocol errors go back
        // to the client as RFC 6749 requires.
        if (responseType != "code")
          errorRedirect(config, redirectUri, state, "unsupported_response_type")
        else if (method != "S256")
          errorRedirect(config, redirectUri, state, "invalid_request")
        else if (challenge.isEmpty)
          errorRedirect(config, redirectUri, state, "invalid_request")
        else if (resource.exists(_ != config.resource))
          errorRedirect(config, redirectUri, state, "invalid_target")
        else {
          val requested = McpScope.parseRequested(param("scope"))
          val scopes = if (requested.isEmpty) McpScope.default else requested
          val key = Secrets.newCredential()
          val pending = PendingAuthorization(client.clientId, client.clientName, redirectUri,
            scopes, state, challenge, resource)
          httpSession.setAttribute(PendingPrefix + key, pending)
          contentType = "text/html; charset=UTF-8"
          ConsentView.render(client, userName, scopes, redirectUri, state,
            csrfToken(), s"${config.baseUrl}/plugin-mcp/oauth/authorize?k=${urlEncode(key)}")
        }
    }
  }

  private def completeAuthorization(config: OAuthConfig, userName: String): Any = {
    val key = param("k")
    val attribute = PendingPrefix + key
    val pending = Option(httpSession.getAttribute(attribute)).collect {
      case p: PendingAuthorization => p
    }

    // One use: the pending request is removed whatever the decision, so a
    // resubmitted form cannot mint a second code.
    httpSession.removeAttribute(attribute)

    pending match {
      case None => badRequest("no authorization in progress")
      case Some(p) =>
        if (!Secrets.constantTimeEquals(param("csrf_token"), csrfToken()))
          badRequest("invalid CSRF token")
        else if (param("decision") != "allow")
          errorRedirect(config, p.redirectUri, p.state, "access_denied")
        else {
          val conn = oauthConn
          val issuedAt = now()
          val grantId = OAuthStore.createGrant(conn, userName, p.clientId, config.resource,
            p.scopes, issuedAt)
          val code = OAuthStore.issueCode(conn, grantId, p.codeChallenge, p.redirectUri,
            issuedAt.plusSeconds(config.authorizationCodeLifetimeSeconds))
          val params = Seq(
            Some("code=" + urlEncode(code)),
            // RFC 9207: name the server that answered, so a response cannot be
            // taken from one authorization server and replayed to a client
            // that expected another.
            Some("iss=" + urlEncode(config.issuer)),
            p.state.map(s => "state=" + urlEncode(s))
          ).flatten.mkString("&")
          redirect(appendQuery(p.redirectUri, params))
        }
    }
  }

  // ── token ─────────────────────────────────────────────────────────────────

  post("/plugin-mcp/oauth/token") {
    withConfig { config =>
      contentType = "application/json; charset=UTF-8"
      response.setHeader("Cache-Control", "no-store")
      val conn = oauthConn
      param("grant_type") match {
        case "authorization_code" => exchangeCode(config, conn)
        case "refresh_token" => exchangeRefresh(config, conn)
        case "" => tokenError(400, "invalid_request", "grant_type is required")
        case other => tokenError(400, "unsupported_grant_type", s"unsupported grant_type")
      }
    }
  }

  private def exchangeCode(config: OAuthConfig, conn: Connection): String = {
    val code = param("code")
    val clientId = param("client_id")
    val redirectUri = param("redirect_uri")
    val verifier = param("code_verifier")
    val resource = optionalParam("resource")
    if (code.isEmpty || clientId.isEmpty || redirectUri.isEmpty || verifier.isEmpty)
      return tokenError(400, "invalid_request", "code, client_id, redirect_uri and code_verifier are required")
    if (resource.exists(_ != config.resource))
      return tokenError(400, "invalid_target", "resource does not match this server")

    OAuthStore.redeemCode(conn, code, clientId, redirectUri, Some(config.resource), verifier, now()) match {
      case Left(failure) => codeFailure(failure)
      case Right(grant) => issueTokens(config, conn, grant)
    }
  }

  private def exchangeRefresh(config: OAuthConfig, conn: Connection): String = {
    val refresh = param("refresh_token")
    val clientId = param("client_id")
    if (refresh.isEmpty || clientId.isEmpty)
      return tokenError(400, "invalid_request", "refresh_token and client_id are required")

    val moment = now()
    val requested = Option(param("resource")).filter(_.nonEmpty)
    OAuthStore.rotateRefreshToken(conn, refresh, clientId,
      moment.plusSeconds(config.refreshTokenLifetimeSeconds), moment,
      resource = Some(config.resource), requestedResource = requested) match {
      case Left(failure) => refreshFailure(failure)
      case Right((grant, replacement)) =>
        val access = OAuthStore.issueAccessToken(conn, grant.grantId,
          moment.plusSeconds(config.accessTokenLifetimeSeconds), moment)
        tokenResponse(config, access, Some(replacement), grant.scopes)
    }
  }

  private def issueTokens(config: OAuthConfig, conn: Connection, grant: ResolvedGrant): String = {
    val moment = now()
    val access = OAuthStore.issueAccessToken(conn, grant.grantId,
      moment.plusSeconds(config.accessTokenLifetimeSeconds), moment)
    val refresh = OAuthStore.setRefreshToken(conn, grant.grantId,
      moment.plusSeconds(config.refreshTokenLifetimeSeconds), moment)
    tokenResponse(config, access, Some(refresh), grant.scopes)
  }

  private def tokenResponse(config: OAuthConfig, access: String, refresh: Option[String],
                            scopes: Set[String]): String = {
    val n = mapper.createObjectNode()
    n.put("access_token", access)
    n.put("token_type", "Bearer")
    n.put("expires_in", config.accessTokenLifetimeSeconds)
    // Returned so a narrowed grant is visible to the client rather than
    // discovered later as a refusal.
    n.put("scope", McpScope.render(scopes))
    refresh.foreach(n.put("refresh_token", _))
    mapper.writeValueAsString(n)
  }

  /**
   * A failed code exchange.
   *
   * `invalid_grant` for everything the caller could have got wrong about the
   * code itself, which is what RFC 6749 §5.2 specifies and also avoids
   * telling a prober which part they got right. A binding failure is still a
   * binding failure in the store — it does not consume the code and does not
   * revoke — so the uniform wire error does not become a uniform effect.
   */
  private def codeFailure(failure: GrantFailure): String = failure match {
    case GrantFailure.ResourceMismatch => tokenError(400, "invalid_target", "resource does not match")
    case _ => tokenError(400, "invalid_grant", "the authorization code is not valid")
  }

  private def refreshFailure(failure: GrantFailure): String =
    tokenError(400, "invalid_grant", "the refresh token is not valid")

  private def tokenError(status: Int, code: String, description: String): String = {
    response.setStatus(status)
    val n = mapper.createObjectNode()
    n.put("error", code)
    n.put("error_description", description)
    mapper.writeValueAsString(n)
  }

  // ── revoke ────────────────────────────────────────────────────────────────

  post("/plugin-mcp/oauth/revoke") {
    withConfig { _ =>
      contentType = "application/json; charset=UTF-8"
      val token = param("token")
      if (token.nonEmpty) OAuthStore.revokeByToken(oauthConn, token, now())
      // RFC 7009 §2.2: an unknown token is a successful revocation. Saying
      // otherwise turns this endpoint into an oracle for whether a token
      // exists.
      response.setStatus(200)
      "{}"
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private def withConfig(f: OAuthConfig => Any): Any = oauthConfig match {
    case Some(config) => f(config)
    case None =>
      response.setStatus(404)
      contentType = "application/json; charset=UTF-8"
      """{"error":"oauth_not_configured"}"""
  }

  protected def httpSession: HttpSession = request.getSession(true)

  /** A per-session CSRF token, created on first use. */
  protected def csrfToken(): String = {
    val s = httpSession
    Option(s.getAttribute(CsrfAttribute)).map(_.toString).getOrElse {
      val token = Secrets.newCredential()
      s.setAttribute(CsrfAttribute, token)
      token
    }
  }

  private def param(name: String): String =
    Option(request.getParameter(name)).map(_.trim).getOrElse("")

  private def optionalParam(name: String): Option[String] =
    Option(request.getParameter(name)).map(_.trim).filter(_.nonEmpty)

  private def queryString: String =
    Option(request.getQueryString).filter(_.nonEmpty).map("?" + _).getOrElse("")

  private def badRequest(message: String): Any = {
    response.setStatus(400)
    contentType = "text/plain; charset=UTF-8"
    message
  }

  private def errorRedirect(config: OAuthConfig, redirectUri: String,
                            state: Option[String], error: String): Any = {
    val params = Seq(
      Some("error=" + urlEncode(error)),
      Some("iss=" + urlEncode(config.issuer)),
      state.map(s => "state=" + urlEncode(s))
    ).flatten.mkString("&")
    redirect(appendQuery(redirectUri, params))
  }

  private def appendQuery(uri: String, query: String): String =
    if (uri.contains("?")) s"$uri&$query" else s"$uri?$query"

  private def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")
}
