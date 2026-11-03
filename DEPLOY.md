# Installing on a GitBucket instance

Generic runbook. It names no particular instance: substitute your own host,
account and `GITBUCKET_HOME`. For what the plugin requires, which GitBucket
versions have been tested and what has not been tried, see
[README.md](README.md) and [docs/authentication.md](docs/authentication.md);
this page is only the mechanics of getting the JAR in place.

## 1. Build

```bash
./gradlew shadowJar
```

Produces `build/libs/gitbucket-mcp-plugin-<version>.jar`.

## 2. Install

Copy the JAR into the target GitBucket's plugin directory:

```bash
cp build/libs/gitbucket-mcp-plugin-<version>.jar "$GITBUCKET_HOME/plugins/"
```

GitBucket runs a `PluginWatchThread` over that directory and reloads on file
creation, so a restart is not always required:

```
INFO  g.core.plugin.PluginWatchThread - ENTRY_CREATE: gitbucket-mcp-plugin-<version>.jar
INFO  g.core.plugin.PluginWatchThread - Reloading plugins...
INFO  g.core.plugin.PluginRegistry - Initialize gitbucket-mcp-plugin-<version>.jar
INFO  g.core.plugin.PluginWatchThread - Reloading finished.
```

Prefer a restart when **replacing** an installed version: the plugin's
database migration runs from the loaded `Plugin` class's declared versions,
and `itest/upgrade_gate.py` is what exercises install, replacement, migration
and restart as a sequence.

### Replacing: remove the old JAR, do not rename the new one

Leave two JARs declaring the same plugin id in that directory and GitBucket
loads **one** of them. Which one is not something to leave to chance, and
neither a restart nor a new filename settles it. From GitBucket's own scan:

- candidates are ordered by version **descending**, and that version is parsed
  from the **file name**, not from the packaged descriptor;
- a file name the version pattern does not match counts as `0.0.0`, so
  `gitbucket-mcp-plugin-0.1.0-new.jar` sorts *below* the
  `gitbucket-mcp-plugin-0.1.0.jar` it was meant to supersede;
- the first candidate for a given plugin id wins and the rest are skipped,
  logged as `Plugin <id> is duplicated. <jar> is available.`

So: **delete the previous JAR** rather than renaming around it, and read that
log line as the signal that one was left behind. Two builds of this plugin can
declare the same version — the version is not an identity — so equal
declared versions make the choice arbitrary rather than newest-wins.

`$GITBUCKET_HOME/plugins/.installed/` is GitBucket's own copy of what it
loaded. It is rebuilt on each scan, so it is a witness and never the place to
install into.

## 3. Verify

```bash
curl http://<host>/plugin-mcp/health
# {"status":"ok","protocol":"MCP","transport":"Streamable HTTP"}
```

Then the MCP handshake. HTTP Basic takes a GitBucket account's own
credentials and needs no configuration:

```bash
curl -u <user>:<password> \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
  http://<host>/plugin-mcp/
```

`tools/list` then enumerates the tools, each with the scope it requires.

`serverInfo.version` in that reply comes from the descriptor packaged in the
installed JAR rather than from a literal in the source, which is worth having
— but it reports the **declared version only**, and that is not the artifact.
Two builds with different contents and different behaviour can declare the
same version, and this plugin's have: every build in this contribution
declares `0.1.0`.

**Artifact identity is the file hash.** Compare the hash of the JAR you placed
in the plugins directory with the hash of the build you chose:

```bash
sha256sum "$GITBUCKET_HOME/plugins/gitbucket-mcp-plugin-<version>.jar"
# must equal the hash recorded for the build you intended to install
```

Use the handshake, `serverInfo.version` and `/health` as **smoke checks** —
they tell you something is up and answering — and the hash for *what* is
running.

For the browser authorization flow, set the external base URL — OAuth stays
off until you do, and nothing else changes:

```
-Dgitbucket.mcp.oauth.baseUrl=https://<your-gitbucket>
```

[docs/authentication.md](docs/authentication.md) covers what that value
governs and why it is not taken from a request header.

## Before deploying anywhere shared

- Identify the exact artifact by hash, as above, after copying it onto the
  target — not by its declared version and not by its file name.
- Run the gates against a **disposable** instance, never a shared one: they
  create accounts and repositories and deliberately attempt refused writes.
  See [itest/README.md](itest/README.md).
- Untested configurations are listed in [README.md](README.md) — among them
  TLS termination by a reverse proxy, GitBucket's LDAP authentication, and
  interaction with plugins beyond the bundled ones. Untested is not the same
  as known-broken, but it is not evidence either.
