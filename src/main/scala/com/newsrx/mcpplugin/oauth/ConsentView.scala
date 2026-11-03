package com.newsrx.mcpplugin.oauth

/**
 * The consent screen.
 *
 * Rendered here rather than through a template because every value on it
 * comes from somewhere untrusted — the client's own metadata document, and
 * query parameters — and the escaping has to be unmissable. A template would
 * put the escaping decision one indirection away from the data.
 *
 * The client name is attacker-controlled: whoever publishes the metadata
 * document chooses it. It is escaped, and it is shown next to the client id
 * so a name claiming to be something it is not can be seen for what it is.
 */
object ConsentView {

  def escape(raw: String): String =
    Option(raw).getOrElse("")
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#x27;")

  private def scopeLine(scope: String): String = scope match {
    case "mcp:read" => "Read your repositories, issues and pull requests"
    case "mcp:write" => "Create and change issues, comments and pull requests"
    case other => escape(other)
  }

  def render(client: OAuthClient, userName: String, scopes: Set[String],
             redirectUri: String, state: Option[String], csrfToken: String,
             formAction: String): String = {
    val items = McpScope.all.map(_.name).filter(scopes.contains).map { s =>
      s"""<li><strong>${escape(s)}</strong> — ${escape(scopeLine(s))}</li>"""
    }.mkString("\n")

    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<title>Authorize application</title>
       |<style>
       |body{font-family:system-ui,sans-serif;max-width:34rem;margin:4rem auto;padding:0 1rem;line-height:1.5}
       |.client{background:#f6f8fa;border:1px solid #d0d7de;border-radius:6px;padding:1rem;margin:1rem 0}
       |.id{font-family:ui-monospace,monospace;font-size:.85em;color:#57606a;word-break:break-all}
       |ul{padding-left:1.25rem}
       |button{font:inherit;padding:.5rem 1rem;border-radius:6px;border:1px solid #d0d7de;cursor:pointer}
       |.primary{background:#1f883d;color:#fff;border-color:#1f883d}
       |</style>
       |</head>
       |<body>
       |<h1>Authorize application</h1>
       |<div class="client">
       |<div><strong>${escape(client.clientName)}</strong></div>
       |<div class="id">${escape(client.clientId)}</div>
       |</div>
       |<p>Signed in as <strong>${escape(userName)}</strong>. This application is asking to:</p>
       |<ul>
       |$items
       |</ul>
       |<p>It will act as you, and only within what your GitBucket account may already do.</p>
       |<form method="post" action="${escape(formAction)}">
       |<input type="hidden" name="client_id" value="${escape(client.clientId)}">
       |<input type="hidden" name="redirect_uri" value="${escape(redirectUri)}">
       |<input type="hidden" name="scope" value="${escape(McpScope.render(scopes))}">
       |<input type="hidden" name="state" value="${escape(state.getOrElse(""))}">
       |<input type="hidden" name="csrf_token" value="${escape(csrfToken)}">
       |<button type="submit" name="decision" value="deny">Cancel</button>
       |<button type="submit" name="decision" value="allow" class="primary">Authorize</button>
       |</form>
       |</body>
       |</html>""".stripMargin
  }
}
