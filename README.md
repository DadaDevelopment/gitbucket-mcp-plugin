# gitbucket-mcp-plugin

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

## Fork and extensions

This repository is a feature-extended fork of [DadaDevelopment/gitbucket-mcp-plugin](https://github.com/DadaDevelopment/gitbucket-mcp-plugin), maintained by [hide-san-45](https://github.com/hide-san-45). It builds on the original GitBucket MCP plugin with additional features. For the upstream project, see the [original repository](https://github.com/DadaDevelopment/gitbucket-mcp-plugin).

A [GitBucket](https://github.com/gitbucket/gitbucket) plugin that embeds a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server directly inside GitBucket. AI coding assistants (Claude, Cursor, VS Code Copilot, etc.) can read and manage your GitBucket repositories, issues, and pull requests over MCP — no separate service or proxy required.

## Features

18 MCP tools covering the most common developer workflows:

### Repository tools

| Tool | Description |
|------|-------------|
| `list_repositories` | List repositories accessible to a user or organization |
| `get_repository` | Get repository metadata, branches, and tags |

### Issue tools

| Tool | Description |
|------|-------------|
| `list_issues` | List issues with comments, labels, assignees, milestone, and priority (open / closed / all) |
| `get_issue` | Get all issue fields, comments, and related metadata |
| `create_issue` | Create a new issue |
| `close_issue` | Close an issue with an optional comment |
| `reopen_issue` | Reopen a closed issue |
| `add_issue_comment` | Add a comment to an issue |

### Milestone tools

| Tool | Description |
|------|-------------|
| `get_milestone` | Get milestone details, state, due date, and open/closed issue counts |
| `list_milestones` | List repository milestones by state (open / closed / all) |
| `create_milestone` | Create a milestone with an optional description and due date |
| `update_milestone` | Update milestone details or open/close it |

### Pull request tools

| Tool | Description |
|------|-------------|
| `list_pull_requests` | List pull requests (open / closed / all) |
| `get_pull_request` | Get pull request details and comment count |
| `add_pr_comment` | Add a comment to a pull request |
| `close_pull_request` | Close a PR without merging, with an optional comment |
| `reopen_pull_request` | Reopen a closed pull request |
| `get_pr_diff` | List changed files and patches for a pull request |

## Requirements

- **GitBucket**: 4.42.0 – 4.46.0
- **Java**: 17 or 21 (required by GitBucket 4.42+)

## Installation

### Option A — Download a release JAR

1. Go to [Releases](https://github.com/hide-san-45/gitbucket-mcp-plugin/releases) and download the latest `.jar`.
2. Copy it to your GitBucket plugins directory:
   ```bash
   cp gitbucket-mcp-plugin-*.jar $GITBUCKET_HOME/plugins/
   ```
3. Restart GitBucket.

### Option B — Build from source

```bash
git clone https://github.com/hide-san-45/gitbucket-mcp-plugin.git
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

Authentication uses **HTTP Basic Auth** with your GitBucket credentials.

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

### Manual testing with curl

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

| GitBucket | Status | Notes |
|-----------|--------|-------|
| 4.48.0 | ✅ Supported | Current target |
| 4.47.0 | ✅ Supported | |
| 4.46.0 | ✅ Supported | |
| 4.45.0 | ✅ Supported | |
| 4.44.0 | ✅ Supported | |
| 4.43.0 | ✅ Supported | H2 1.x → 2.x DB migration (no plugin API change) |
| 4.42.0 | ✅ Supported | Minimum supported version; Java 17 required |
| < 4.42.0 | ❌ Not supported | Plugin API incompatible |

## Development

### Build

```bash
./gradlew shadowJar       # produces build/libs/gitbucket-mcp-plugin-*.jar
```

### Unit tests

```bash
./gradlew test
```

Unit tests cover JSON-RPC protocol parsing, tool registration, schema validation, argument parsing, and milestone serialization.

### Integration tests

Requires a running GitBucket instance with the plugin installed:

```bash
./gradlew shadowJar
cp build/libs/gitbucket-mcp-plugin-*.jar $GITBUCKET_HOME/plugins/
# start GitBucket...
python3 scripts/integration_test.py --host localhost --port 8080 --user root --password root
```

The script creates test fixtures, exercises the MCP tools, including milestone lifecycle operations, and reports a pass/fail summary.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).
