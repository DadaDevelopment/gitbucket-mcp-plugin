package com.newsrx.mcpplugin.tools

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.newsrx.mcpplugin.PluginDescriptor
import com.newsrx.mcpplugin.oauth.McpScope
import com.newsrx.mcpplugin.mcp.ToolDef
import gitbucket.core.model.Account
import javax.servlet.http.HttpServletRequest

/**
 * Who the server validated this call as.
 *
 * A client cannot otherwise learn the identity its own credentials resolve
 * to: a pull request's `author` is whoever opened it, and a separately
 * configured REST credential says nothing about the account behind the MCP
 * connector's header. Without this tool an agent can only guess, and a guess
 * about identity is exactly what must not appear in a review report.
 *
 * The account here is the one `MCPController.authenticate` already validated
 * for this request, so this is a read of server state, not a second check.
 */
class WhoAmITool extends ToolDef {
  val name = "whoami"
  val requiredScope = McpScope.Read
  val description =
    "Return the GitBucket account this MCP connection is authenticated as, plus the self-reported plugin descriptor"

  def inputSchema(mapper: ObjectMapper) = objectSchema(mapper, required = Seq())

  def execute(args: JsonNode, account: Account, request: HttpServletRequest, mapper: ObjectMapper): JsonNode = {
    val n = mapper.createObjectNode()
    n.put("login", account.userName)
    n.put("full_name", account.fullName)
    n.put("email", account.mailAddress)
    n.put("is_admin", account.isAdmin)
    n.put("is_group_account", account.isGroupAccount)
    n.put("is_removed", account.isRemoved)

    val plugin = mapper.createObjectNode()
    plugin.put("plugin_id", PluginDescriptor.get("pluginId"))
    plugin.put("plugin_name", PluginDescriptor.get("pluginName"))
    plugin.put("plugin_version", PluginDescriptor.get("pluginVersion"))
    plugin.put("plugin_provider", PluginDescriptor.get("pluginProvider"))
    // The descriptor is a string inside the JAR. It is not a build attestation
    // and must not be used to identify which source revision produced the JAR.
    plugin.put("self_reported", true)
    n.set[ObjectNode]("plugin", plugin)

    jsonContent(mapper, n)
  }
}
