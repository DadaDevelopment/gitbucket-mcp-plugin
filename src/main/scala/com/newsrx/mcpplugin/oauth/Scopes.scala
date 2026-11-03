package com.newsrx.mcpplugin.oauth

/**
 * The scopes this authorization server issues.
 *
 * Two, deliberately. A scope list that mirrors the tool list looks precise and
 * is unusable: a client has to request twenty of them, a user has to read
 * twenty lines of consent, and nobody refuses one. The distinction that
 * actually changes what can happen to a repository is read versus write.
 */
sealed abstract class McpScope(val name: String) {

  /** Whether a grant carrying `granted` is sufficient for this requirement. */
  def satisfiedBy(granted: Set[String]): Boolean
}

object McpScope {

  /** Read-only tools. */
  case object Read extends McpScope("mcp:read") {
    def satisfiedBy(granted: Set[String]): Boolean =
      granted.contains(Read.name) || granted.contains(Write.name)
  }

  /**
   * Mutating tools.
   *
   * `mcp:write` implies `mcp:read`: a client that may change an issue can
   * already read it back, and pretending otherwise would only teach clients to
   * request both every time.
   */
  case object Write extends McpScope("mcp:write") {
    def satisfiedBy(granted: Set[String]): Boolean = granted.contains(Write.name)
  }

  val all: Seq[McpScope] = Seq(Read, Write)

  private val byName: Map[String, McpScope] = all.map(s => s.name -> s).toMap

  def byScopeName(name: String): Option[McpScope] = byName.get(name)

  /**
   * Parse a space-delimited scope string, keeping only scopes this server
   * issues.
   *
   * RFC 6749 allows a server to issue a narrower scope than was requested, and
   * that is what happens here: an unknown scope is dropped rather than
   * refused, so a client asking for `mcp:read openid` gets `mcp:read` and a
   * working connection instead of an error it cannot act on. The granted set
   * is returned to the client in the token response, so the narrowing is
   * visible rather than silent.
   */
  def parseRequested(raw: String): Set[String] =
    Option(raw).getOrElse("").split("\\s+").filter(_.nonEmpty).filter(byName.contains).toSet

  /** The default when a client requests no scope at all: the weaker one. */
  val default: Set[String] = Set(Read.name)

  /** Render a granted set in the canonical order, for a token response. */
  def render(granted: Set[String]): String =
    all.map(_.name).filter(granted.contains).mkString(" ")
}
