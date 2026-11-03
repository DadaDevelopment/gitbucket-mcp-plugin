package com.newsrx.mcpplugin

import gitbucket.core.plugin.{Plugin, PluginRegistry}
import gitbucket.core.service.SystemSettingsService.SystemSettings
import io.github.gitbucket.solidbase.migration.LiquibaseMigration
import io.github.gitbucket.solidbase.model.Version
import javax.servlet.ServletContext

class MCPPlugin extends Plugin {
  override val pluginId   = "mcp-plugin"
  override val pluginName = "MCP Plugin"
  override val description = "MCP (Model Context Protocol) server for GitBucket"
  // Schema versions, which are not the plugin version. 1.0 is the first one
  // to carry a migration: the OAuth grant, code, refresh and token tables.
  // Nothing is added here to change what an admin page displays — a version
  // without a real schema change would make Solidbase look for a migration
  // that does not exist.
  override val versions = List(
    new Version("0.1.0"),
    new Version("1.0", new LiquibaseMigration("update/mcp-plugin_1.0.xml"))
  )

  /**
   * Paths that must work without a GitBucket session.
   *
   * Discovery is read before a client has any credential at all, and the
   * token and revocation endpoints are called by the client rather than by a
   * browser. `/plugin-mcp/oauth/authorize` is deliberately absent: it is the
   * one endpoint that must know who is signed in.
   */
  override def anonymousAccessiblePaths(
    registry: PluginRegistry,
    context: ServletContext,
    settings: SystemSettings
  ): Seq[String] = Seq(
    "/.well-known/oauth-protected-resource",
    "/.well-known/oauth-authorization-server",
    "/plugin-mcp/oauth/token",
    "/plugin-mcp/oauth/revoke",
    "/plugin-mcp/health"
  )

  override def controllers(
    registry: PluginRegistry,
    context: ServletContext,
    settings: SystemSettings
  ): Seq[(String, MCPController)] =
    Seq("/*" -> new MCPController())
}
