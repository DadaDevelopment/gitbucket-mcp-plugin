package com.newsrx.mcpplugin

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * What GitBucket actually loads.
 *
 * GitBucket loads the root-package class named `Plugin` by name. Everything
 * the plugin declares — its schema versions, the paths that work without a
 * session, its controllers — is read from that object and from nowhere else.
 *
 * These cases are deliberately about the entry point rather than about
 * `MCPPlugin`: a second plugin class that looks authoritative but is never
 * loaded is exactly how a migration can be declared, committed, tested and
 * still never run.
 */
@RunWith(classOf[JUnitRunner])
class PluginEntryPointSpec extends AnyFunSuite with Matchers {

  private val loaded = Class.forName("Plugin").getDeclaredConstructor().newInstance()
    .asInstanceOf[gitbucket.core.plugin.Plugin]

  test("the class GitBucket loads is this plugin") {
    loaded.pluginId shouldBe "mcp-plugin"
  }

  test("the loaded class declares the OAuth schema migration") {
    // Solidbase migrates up to the last declared version and GitBucket then
    // requires the recorded version to equal it, so the migration has to be
    // declared on this class to run at all.
    loaded.versions.map(_.getVersion) should contain("1.0")
    loaded.versions.last.getVersion shouldBe "1.0"
    loaded.versions.last.getMigrations should not be empty
  }

  test("the loaded class opens the paths a client needs before it has a token") {
    val paths = loaded.anonymousAccessiblePaths(null, null, null)
    paths should contain allOf (
      "/.well-known/oauth-protected-resource",
      "/.well-known/oauth-authorization-server",
      "/plugin-mcp/oauth/token",
      "/plugin-mcp/oauth/revoke")
    // The consent screen is the one endpoint that must know who is signed in.
    paths should not contain "/plugin-mcp/oauth/authorize"
  }
}
