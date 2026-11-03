# Contributing

Thanks for taking the time to contribute!

## Getting started

```bash
git clone https://github.com/DADA-TUDA/gitbucket-mcp-plugin.git
cd gitbucket-mcp-plugin
./gradlew test
```

You'll need **Java 17+** and **Gradle** (the wrapper is included).

## Project structure

```
src/
  main/resources/
    gitbucket-plugin.properties           # the descriptor GitBucket reads
    update/mcp-plugin_1.0.xml             # Liquibase changelog for the OAuth tables
  main/scala/
    Plugin.scala                          # GitBucket entry point; declares `versions`
    com/newsrx/mcpplugin/
      MCPPlugin.scala                     # registration, routes, anonymous paths
      MCPController.scala                 # HTTP, authentication, JSON-RPC dispatch
      PluginDescriptor.scala              # reads the packaged descriptor, not a literal
      mcp/
        JsonRpc.scala                     # JSON-RPC 2.0 parsing and formatting
        ToolDef.scala                     # tool interface, registry, schema helpers
        StrictArgs.scala                  # argument parsing that refuses coercion
        Timestamps.scala                  # UTC ISO-8601 formatting
      oauth/                              # the authorization server
        OAuthConfig.scala                 # configuration, and the rules it enforces
        OAuthEndpoints.scala              # authorize, token, revoke
        OAuthStore.scala                  # grants, codes, rotation, replay detection
        Metadata.scala ConsentView.scala   # discovery documents, consent screen
        ClientMetadata.scala              # client ID metadata documents, SSRF policy
        PinnedConnection.scala            # the fetcher: destination pinning and bounds
        Pkce.scala Secrets.scala Scopes.scala ChallengeError.scala
        AuthenticatedCaller.scala         # the caller, and the scope it holds
      tools/
        IdentityTools.scala               # 1 identity tool
        IssueTools.scala                  # 7 issue tools
        PullRequestTools.scala            # 9 pull request tools
        RepositoryTools.scala             # 2 repository tools
        Authz.scala                       # the repository read/write guards
        SessionSupport.scala              # database session acquisition
  test/scala/                             # the offline suites: `./gradlew test`
itest/                                    # live-instance gates; see itest/README.md
scripts/
  integration_test.py                     # the original pre-OAuth runner, superseded
                                          # by itest/integration.py
```

Per-file tool counts are there to orient you, not as an inventory to keep in
step; `ToolRegistrySpec` is what actually pins the registry.

## Running tests

```bash
./gradlew test                     # the offline suites
python3 itest/selftest.py          # the gates' own guards; no server, no network
```

Everything else needs a **disposable** GitBucket with the plugin installed —
the gates create accounts and repositories and deliberately attempt refused
writes, so they must never be pointed at an instance holding anything real.
`itest/README.md` has the commands, the system properties each gate needs, and
what each one covers.

## Adding a new tool

1. Add a class in the appropriate `*Tools.scala` file, extending `ToolDef` and
   the relevant service trait.
2. **Declare `requiredScope`.** It is an abstract member of `ToolDef`, so a
   tool that omits it does not compile — which is deliberate: the alternative
   is a tool that quietly defaults to the weaker requirement. `McpScope.Read`
   for a tool that only reads, `McpScope.Write` for anything that changes
   state. A `mcp:read` grant cannot call a `Write` tool, and the check happens
   before the tool runs; the repository guard in `Authz` still applies inside
   it, so the scope is a ceiling and never a substitute for permissions.
3. Register it in `ToolDef.scala` → `ToolRegistry.all`.
4. Add unit tests: schema and registry in `ToolDefSpec.scala` /
   `ToolRegistrySpec.scala`, argument parsing in `ToolArgParseSpec.scala` or
   `StrictArgsSpec.scala`, and the tool's behaviour through its real `execute`
   in `tools/ToolExecutionSpec.scala`.
5. Add a case to `itest/integration.py`, which compares the tool's answer with
   GitBucket's own REST API against each repository visibility and each
   collaborator role.
6. Add its row to the tool table in `README.md`, including the scope.

## Pull requests

- Keep changes focused — one feature or fix per PR.
- All existing tests must pass (`./gradlew test`).
- Match the existing code style (no comments explaining *what*, only *why* when non-obvious).
- Update `README.md` if you add or change tool behaviour.
- Use neutral example identities in fixtures (`example-org`, `example.com`,
  `example.invalid`). Never a real host, account or deployment address.

## Reporting issues

Use the [GitHub issue tracker](https://github.com/DADA-TUDA/gitbucket-mcp-plugin/issues).  
Please include your GitBucket version, Java version, and the MCP client you're using.
