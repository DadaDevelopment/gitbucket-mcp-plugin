package com.newsrx.mcpplugin.oauth

/**
 * Where this server thinks it is.
 *
 * Every identifier an OAuth deployment publishes — the issuer, the endpoints,
 * the `resource` a token is bound to — is derived from one externally
 * configured base URL, and from nothing else.
 *
 * Specifically not from the request. `Host` and `X-Forwarded-Host` are set by
 * whoever is talking to the server, and behind a reverse proxy they are set by
 * whoever is talking to the proxy. A resource identifier that follows the
 * request header is not an audience binding: a token minted for one `Host`
 * would be accepted by the same server asked under another, which is the whole
 * property audience binding exists to provide. So the base URL is
 * configuration, it is validated once, and a deployment that has not set it
 * does not serve OAuth at all rather than guessing.
 */
final case class OAuthConfig(
  /** External base URL of this GitBucket, no trailing slash, e.g. `https://git.example.com`. */
  baseUrl: String,
  accessTokenLifetimeSeconds: Int = 3600,
  refreshTokenLifetimeSeconds: Int = 30 * 24 * 3600,
  authorizationCodeLifetimeSeconds: Int = 60
) {
  /** The MCP resource identifier, and the `resource`/audience value in tokens. */
  val resource: String = s"$baseUrl/plugin-mcp/"

  /** This server is its own issuer; RFC 8414 derives the metadata path from it. */
  val issuer: String = baseUrl

  val authorizationEndpoint: String = s"$baseUrl/plugin-mcp/oauth/authorize"
  val tokenEndpoint: String = s"$baseUrl/plugin-mcp/oauth/token"
  val revocationEndpoint: String = s"$baseUrl/plugin-mcp/oauth/revoke"
}

object OAuthConfig {

  /** Property and environment names a deployment can set. */
  val BaseUrlProperty = "gitbucket.mcp.oauth.baseUrl"
  val BaseUrlEnv = "GITBUCKET_MCP_OAUTH_BASE_URL"

  /**
   * Build a configuration from an explicitly supplied base URL.
   *
   * Returns the reason on the left rather than a boolean, because "OAuth is
   * off because the base URL is `http://`" is something an administrator needs
   * to be told, and a silently disabled authentication method is how a
   * deployment ends up serving an endpoint nobody can authenticate to.
   */
  def fromBaseUrl(raw: String): Either[String, OAuthConfig] = {
    val trimmed = Option(raw).map(_.trim).getOrElse("")
    if (trimmed.isEmpty)
      Left(s"not configured: set $BaseUrlProperty or $BaseUrlEnv to this server's external base URL")
    else if (!trimmed.startsWith("https://") && !isLoopback(trimmed))
      // Plain HTTP is refused except on loopback, where it is the only way to
      // run the test suite. A bearer token on a cleartext connection is a
      // bearer token for whoever is on the path.
      Left(s"must be an https:// URL (or http://localhost for testing), got: $trimmed")
    else if (trimmed.endsWith("/"))
      Left(s"must not end with a slash, got: $trimmed")
    else if (trimmed.contains("?") || trimmed.contains("#"))
      Left(s"must not carry a query or fragment, got: $trimmed")
    else
      Right(OAuthConfig(trimmed))
  }

  private def isLoopback(url: String): Boolean =
    url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")

  /** Read the configured base URL from the system property, then the environment. */
  def configuredBaseUrl(): Option[String] =
    Option(System.getProperty(BaseUrlProperty))
      .orElse(Option(System.getenv(BaseUrlEnv)))
      .map(_.trim)
      .filter(_.nonEmpty)
}
