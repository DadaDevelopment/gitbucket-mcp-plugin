# Contributing

Thanks for taking the time to contribute!

## Getting started

```bash
git clone https://github.com/hide-san-45/gitbucket-mcp-plugin.git
cd gitbucket-mcp-plugin
./gradlew test
```

You'll need **Java 17+** and **Gradle** (the wrapper is included).

## Project structure

```
src/
  main/scala/
    Plugin.scala                          # GitBucket plugin entry point
    com/newsrx/mcpplugin/
      MCPController.scala                 # HTTP routes, auth, JSON-RPC dispatch
      mcp/
        JsonRpc.scala                     # JSON-RPC 2.0 parsing/formatting
        ToolDef.scala                     # Tool interface + registry + schema helpers
      tools/
        IssueTools.scala                  # 6 issue management tools
        MilestoneTools.scala              # 4 milestone management tools
        PullRequestTools.scala            # 6 pull request tools
        RepositoryTools.scala             # 2 repository tools
        SessionSupport.scala              # Database session acquisition
  test/scala/com/newsrx/mcpplugin/
    JsonRpcSpec.scala                     # JSON-RPC protocol tests (15)
    ToolDefSpec.scala                     # Registry and schema tests (23)
    ToolArgParseSpec.scala                # Argument parsing tests (22)
    tools/MilestoneJsonSpec.scala         # Milestone JSON and date tests
scripts/
  integration_test.py                     # End-to-end integration test runner
```

## Running tests

```bash
./gradlew test                # unit tests
python3 scripts/integration_test.py ...  # integration tests (needs GitBucket)
```

## Adding a new tool

1. Add a class in the appropriate `*Tools.scala` file, extending `ToolDef` and the relevant service trait.
2. Register it in `ToolDef.scala` → `ToolRegistry.all`.
3. Add unit tests in `ToolDefSpec.scala` and/or `ToolArgParseSpec.scala`.
4. Add an integration test case in `scripts/integration_test.py`.

## Pull requests

- Keep changes focused — one feature or fix per PR.
- All existing tests must pass (`./gradlew test`).
- Match the existing code style (no comments explaining *what*, only *why* when non-obvious).
- Update `README.md` if you add or change tool behaviour.

## Reporting issues

Use the [GitHub issue tracker](https://github.com/hide-san-45/gitbucket-mcp-plugin/issues).  
Please include your GitBucket version, Java version, and the MCP client you're using.
