# gitbucket-mcp-plugin

[![Unit Tests](https://github.com/DADA-TUDA/gitbucket-mcp-plugin/actions/workflows/test.yml/badge.svg)](https://github.com/DADA-TUDA/gitbucket-mcp-plugin/actions/workflows/test.yml)
[![Integration Tests](https://github.com/DADA-TUDA/gitbucket-mcp-plugin/actions/workflows/integration.yml/badge.svg)](https://github.com/DADA-TUDA/gitbucket-mcp-plugin/actions/workflows/integration.yml)
[![Latest Release](https://img.shields.io/github/v/release/DADA-TUDA/gitbucket-mcp-plugin)](https://github.com/DADA-TUDA/gitbucket-mcp-plugin/releases/latest)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

A [GitBucket](https://github.com/gitbucket/gitbucket) plugin that embeds a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server directly inside GitBucket. AI coding assistants (Claude, Cursor, VS Code Copilot, etc.) can read and manage your GitBucket repositories, issues, and pull requests over MCP — no separate service or proxy required.

Two ways to authenticate: HTTP Basic with a GitBucket account, and OAuth 2.1 with PKCE for clients that expect a browser authorization flow, such as ChatGPT's web connector. Either way the caller is a GitBucket account and every tool is subject to that account's repository permissions. See [docs/authentication.md](docs/authentication.md).

## Features

19 MCP tools covering the most common developer workflows. Each one declares
whether it reads or writes, which is what an OAuth grant's scope is checked
against; a Basic-auth caller holds both.

### Identity

| Tool | Scope | Description |
|------|-------|-------------|
| `whoami` | `mcp:read` | The GitBucket account this connection is authenticated as |

### Repository tools

| Tool | Scope | Description |
|------|-------|-------------|
| `list_repositories` | `mcp:read` | List repositories accessible to a user or organization |
| `get_repository` | `mcp:read` | Get repository metadata, branches, and tags |

Both tools report the same counters, and they mean what GitBucket means by
them, which is narrower than the names suggest:

| Field | Meaning |
|-------|---------|
| `issue_count` | **Open** issues, **excluding** pull requests |
| `pr_count` | **Open** pull requests |
| `fork_count` | How many forks exist of this repository's origin. It is **not** a flag that this repository is itself a fork. |
| `is_fork` | Whether this repository is a fork, by GitBucket's own test — it has an origin. |

Each response repeats those meanings in `issue_count_semantics`,
`pr_count_semantics` and `fork_count_semantics`, so a reader does not have to
assume they are totals.

`without_forked` on `list_repositories` excludes repositories that are
themselves forks. Counters are read per repository, for the repositories the
caller may read and no others, so a listing costs one repository read per
visible entry.

### Issue tools

| Tool | Scope | Description |
|------|-------|-------------|
| `list_issues` | `mcp:read` | List issues in a repository (open / closed / all) |
| `get_issue` | `mcp:read` | Get issue details and comment count |
| `create_issue` | `mcp:write` | Create a new issue |
| `close_issue` | `mcp:write` | Close an issue with an optional comment |
| `reopen_issue` | `mcp:write` | Reopen a closed issue |
| `add_issue_comment` | `mcp:write` | Add a comment to an issue |
| `update_own_comment` | `mcp:write` | Edit a comment the authenticated account wrote |

### Pull request tools

| Tool | Scope | Description |
|------|-------|-------------|
| `list_pull_requests` | `mcp:read` | List pull requests (open / closed / all) |
| `get_pull_request` | `mcp:read` | Get pull request details and comment count |
| `get_pr_diff` | `mcp:read` | List changed files and patches for a pull request |
| `get_thread_comments` | `mcp:read` | Read a pull request's review thread |
| `add_pr_comment` | `mcp:write` | Add a comment to a pull request |
| `close_pull_request` | `mcp:write` | Close a PR without merging, with an optional comment |
| `reopen_pull_request` | `mcp:write` | Reopen a closed pull request |
| `update_pull_request` | `mcp:write` | Change a pull request's title or body |
| `mark_ready_for_review` | `mcp:write` | Take a draft pull request out of draft |

## Requirements

- **GitBucket**: 4.46.0, 4.47.0 or 4.48.0 (see [compatibility](#gitbucket-version-compatibility))
- **Java**: 17 or 21 (required by GitBucket 4.42+)
- **A database**: H2 (GitBucket's default) or PostgreSQL. The OAuth tables are
  created by the plugin's own migration on first start.

## Installation

### Option A — Download a release JAR

1. Go to [Releases](https://github.com/DADA-TUDA/gitbucket-mcp-plugin/releases) and download the latest `.jar`.
2. Copy it to your GitBucket plugins directory:
   ```bash
   cp gitbucket-mcp-plugin-*.jar $GITBUCKET_HOME/plugins/
   ```
3. Restart GitBucket.

### Option B — Build from source

```bash
git clone https://github.com/DADA-TUDA/gitbucket-mcp-plugin.git
cd gitbucket-mcp-plugin
./gradlew shadowJar
cp build/libs/gitbucket-mcp-plugin-*.jar $GITBUCKET_HOME/plugins/
```

Restart GitBucket.

## MCP Endpoint

Once installed, the plugin exposes:

| Endpoint | Purpose |
|----------|---------|
| `POST http://<host>/plugin-mcp/` | MCP JSON-RPC (Streamable HTTP transport) |
| `GET  http://<host>/plugin-mcp/health` | Health check |
| `GET  http://<host>/plugin-mcp/sse` | SSE endpoint event (for SSE-capable clients) |

With OAuth configured, five more:

| Endpoint | Purpose |
|----------|---------|
| `GET  http://<host>/.well-known/oauth-protected-resource` | RFC 9728 resource metadata |
| `GET  http://<host>/.well-known/oauth-authorization-server` | RFC 8414 authorization server metadata |
| `GET\|POST http://<host>/plugin-mcp/oauth/authorize` | Consent, on your GitBucket session |
| `POST http://<host>/plugin-mcp/oauth/token` | Code exchange and refresh |
| `POST http://<host>/plugin-mcp/oauth/revoke` | RFC 7009 revocation |

**HTTP Basic Auth** with your GitBucket credentials works with no configuration
and is unchanged. **OAuth** is off until an operator sets this plugin's external
base URL:

```
-Dgitbucket.mcp.oauth.baseUrl=https://git.example.com
```

The identifiers the plugin publishes are derived from that one value and never
from a request header, so a `Host` or `X-Forwarded-Host` a client controls
cannot move the issuer. Without it the discovery documents answer 404 and
Bearer tokens are refused; nothing else changes. The full picture — scopes,
token lifetimes, revocation, what the plugin implements itself and what comes
from a library — is in [docs/authentication.md](docs/authentication.md).

## MCP Client Configuration

### Claude Code

Add to `~/.claude.json` (or your project's `.claude/settings.json`):

```json
{
  "mcpServers": {
    "gitbucket": {
      "type": "http",
      "url": "http://localhost:8080/plugin-mcp/",
      "headers": {
        "Authorization": "Basic <base64(user:password)>"
      }
    }
  }
}
```

Generate the Base64 token:
```bash
echo -n "user:password" | base64
```

### Cursor / VS Code (MCP-compatible extensions)

Use the HTTP transport URL `http://localhost:8080/plugin-mcp/` with Basic auth headers, matching your client's MCP settings format.

### Clients that authorize in a browser

A client that speaks MCP authorization — ChatGPT's web connector, for example —
needs only the server URL. It reads
`/.well-known/oauth-protected-resource`, follows it to the authorization server
metadata, registers itself by publishing a client ID metadata document (no
pre-registration with this server, and no client secret), and sends you to
GitBucket's own sign-in and then to a consent screen.

The **client** chooses which scopes to ask for. The consent screen lists
exactly those and offers one decision — Authorize or Cancel; there is no
per-scope selector, so a client that asks for `mcp:write` is approved or
refused as a whole. A client that asks for nothing is granted the weaker
scope, `mcp:read`. What is granted is the set the server recorded when the
request arrived, not a field posted back with the form. Either way the token
acts as your account and can do nothing your account cannot.

This requires `gitbucket.mcp.oauth.baseUrl` and a GitBucket the client can
reach over HTTPS. The flow is exercised end to end by
`itest/oauth_controls.py` over real HTTP, and a commercial client — ChatGPT's
connector — has since completed it against a running GitBucket and made
authenticated calls through it.
[docs/authentication.md](docs/authentication.md) says exactly which operations
were observed, against which build, and what that does and does not
establish.

### Manual testing with curl

`root:root` below is GitBucket's own default administrator on a **fresh local
instance**, which is what these examples assume. Substitute your own account,
and never point these at an instance that holds anything real.

```bash
# List all tools
curl -s -u root:root \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' \
  http://localhost:8080/plugin-mcp/

# List issues in a repository
curl -s -u root:root \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"list_issues","arguments":{"owner":"root","repo":"myrepo","state":"open"}},"id":2}' \
  http://localhost:8080/plugin-mcp/
```

### Manual testing with MCP Inspector

```bash
npx @modelcontextprotocol/inspector \
  --transport http \
  --url http://localhost:8080/plugin-mcp/ \
  --header "Authorization: Basic $(echo -n 'root:root' | base64)"
```

## GitBucket Version Compatibility

Three versions are tested, by compiling and running the unit suite against
each release's own runtime classpath, including the `oauth2-oidc-sdk` version
that release ships:

| GitBucket | `oauth2-oidc-sdk` | Status |
|-----------|-------------------|--------|
| 4.48.0 | 11.38.2 | ✅ Tested |
| 4.47.0 | 11.38.2 | ✅ Tested |
| 4.46.0 | 11.33 | ✅ Tested |

The live, OAuth and upgrade harnesses have been run on 4.48.0, including the
PostgreSQL migration path. For 4.46.0 and 4.47.0 the evidence is the unit
suite against their own runtime classpaths.

Versions below 4.46.0 are untested rather than known broken: the plugin uses
`anonymousAccessiblePaths`, which earlier releases may not have, and the OAuth
code depends on `oauth2-oidc-sdk` being on the host's classpath. Anything
outside the table above has not been run.

## Development

### Build

```bash
./gradlew shadowJar       # produces build/libs/gitbucket-mcp-plugin-*.jar
```

### Unit tests

```bash
./gradlew test
```

Covers JSON-RPC protocol parsing, the tool registry, schema validation,
argument parsing, repository authorization, tool execution through the
production dispatcher, the OAuth grant store against a real database created
by the plugin's own migration, the client metadata fetcher's SSRF policy and
its HTTP framing bounds. The run prints its own total; this page deliberately
does not restate it, because a number written here is wrong the next time a
test is added.

### Integration tests

Requires a **disposable** GitBucket instance with the plugin installed. Both
harnesses build their own fixtures and refuse to run against an instance that
is not blank, but they create accounts and repositories and should never be
pointed at anything you care about.

```bash
./gradlew shadowJar
cp build/libs/gitbucket-mcp-plugin-*.jar $GITBUCKET_HOME/plugins/
# start GitBucket on 127.0.0.1:8099, with OAuth configured for the second suite:
#   -Dgitbucket.mcp.oauth.baseUrl=http://localhost:8099
#   -Dgitbucket.mcp.oauth.allowLoopbackClients=true
# keep a copy of the exact artifact outside the directory GitBucket scans
cp build/libs/gitbucket-mcp-plugin-0.1.0.jar /tmp/candidate.jar
MCP_PLUGIN_JAR=/tmp/candidate.jar \
  python3 itest/integration.py    # the tools, against GitBucket's own REST API
python3 itest/oauth_controls.py   # the OAuth flow, over HTTP
```

`MCP_PLUGIN_JAR` must be **one concrete path**. A glob there does not work:
the shell leaves `*` unexpanded inside an assignment word and the gate exits 2
before it reaches HTTP. `itest/README.md` has the form to use when the file
name is not known ahead of time.

`integration.py` needs `MCP_PLUGIN_JAR` to name the exact JAR installed on the
instance, so the version the plugin reports can be compared with the
descriptor actually packaged in the artifact rather than with a literal.

Installation and upgrade are a third harness, and it starts and stops its own
GitBucket processes rather than using a running one:

```bash
python3 itest/upgrade_gate.py \
  --war gitbucket.war \
  --previous <the plugin currently installed>.jar \
  --candidate build/libs/gitbucket-mcp-plugin-*.jar \
  --home /tmp/gb-upgrade          # must not exist, or be empty
```

It covers a fresh install, the upgrade through GitBucket's own plugin
mechanism, the migration running, data created under the previous plugin
surviving, and grants and revocations surviving a restart. Add
`--db-url jdbc:postgresql://…` (with `--db-user`, `--db-password`) to run the
same sequence against PostgreSQL instead of the bundled H2; without it the
PostgreSQL path is generated DDL, not an executed migration.

A fourth harness checks the audience binding, which needs a restart to
observe: it issues a grant while the server is configured as one resource,
restarts the same server as another, and presents the credential again.

```bash
python3 itest/resource_gate.py \
  --war gitbucket.war \
  --candidate build/libs/gitbucket-mcp-plugin-*.jar \
  --home /tmp/gb-resource        # must not exist, or be empty
```

`integration.py` exercises every tool against each repository visibility and
each collaborator role, comparing the plugin's answer with GitBucket's own.
`oauth_controls.py` drives discovery, consent, code exchange, refresh rotation,
revocation and Bearer-authenticated tool calls through the real endpoints, and
checks what GitBucket stored rather than what the plugin replied. See
[itest/README.md](itest/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).
