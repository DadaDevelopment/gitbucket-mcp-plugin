# Live-instance integration gate

The offline suites under `src/test` replace GitBucket's storage and role
predicates, so they establish tool wiring and policy branching but not
GitBucket's own role computation, its SQL or the HTTP transport. This harness
closes that gap: it drives the deployed plugin over `/plugin-mcp/` with real
credentials, on a disposable GitBucket, and cross-checks every answer against
GitBucket's own REST API, its web routes and Git.

**It must never be pointed at a GitBucket that holds anything real.** It
creates accounts, repositories, issues, comments and merges, and it deliberately
attempts refused writes. `fixtures.py` assumes a blank instance.

## Running it

A disposable GitBucket 4.48.0 on `127.0.0.1:8099`, with this plugin installed
and an empty `GITBUCKET_HOME`:

```sh
java -jar gitbucket.war --port=8099 --host=127.0.0.1 --gitbucket.home=/tmp/gb-itest
# install the artifact you intend to test, keeping a copy outside the
# directory GitBucket scans, and restart
cp build/libs/gitbucket-mcp-plugin-0.1.0.jar /tmp/candidate.jar
cp /tmp/candidate.jar /tmp/gb-itest/plugins/
MCP_PLUGIN_JAR=/tmp/candidate.jar python3 itest/integration.py
```

**Give it one concrete path.** A glob does not work here: the shell does not
expand `*` inside an assignment word, so
`MCP_PLUGIN_JAR=.../gitbucket-mcp-plugin-*.jar` passes the asterisk through
literally and the gate exits 2 with *does not name a readable file* before it
reaches HTTP. If the name is not known ahead of time, resolve it and check
that it resolved to exactly one file:

```sh
set -- /tmp/gb-itest/plugins/gitbucket-mcp-plugin-*.jar
[ $# -eq 1 ] && [ -f "$1" ] || { echo "expected exactly one JAR, got $#"; exit 2; }
MCP_PLUGIN_JAR=$1 python3 itest/integration.py
```

Keeping the copy you point at **outside** `plugins/` avoids the other trap:
GitBucket copies what it loads into `plugins/.installed/` and rebuilds that
directory on each scan, so a path into it is not stable.

`MCP_PLUGIN_JAR` is required, not optional. The gate compares the version the
plugin reports with the descriptor packaged in that JAR; checking it against a
literal written here would re-introduce exactly the defect the descriptor
lookup exists to prevent, and would quietly encode one fork's version number.
An unset or unreadable path is exit 2, like every other precondition.

`oauth_controls.py` is a second gate over the same instance, covering the
OAuth flow. It needs two more system properties on the GitBucket command
line, because the plugin publishes its identifiers from a configured base URL
and because a client metadata document served from this machine is on a
loopback address the fetcher refuses by default:

```sh
java -Dgitbucket.mcp.oauth.baseUrl=http://localhost:8099 \
     -Dgitbucket.mcp.oauth.allowLoopbackClients=true \
     -jar gitbucket.war --port=8099 --host=127.0.0.1 --gitbucket.home=/tmp/gb-itest
python3 itest/oauth_controls.py
```

Use `localhost` for the browser part and `127.0.0.1` for the REST control
calls, as the files do: the consent form's action is built from the
configured base URL, so the session cookie has to live on that host name.

`oauth_gate.py` holds the flow helpers — a local HTTP server that publishes
client metadata documents, PKCE, a non-following HTTP client so redirects are
inspected rather than followed, and the walk through the real consent screen.
`oauth_controls.py` holds the controls themselves.

## The audience binding

`resource_gate.py` checks the one property that only a restart can show: a
grant issued while the server calls itself one resource, presented again after
the same server restarts as another. It starts and stops its own processes,
like the upgrade gate.

```sh
python3 itest/resource_gate.py \
  --war gitbucket.war \
  --candidate build/libs/gitbucket-mcp-plugin-*.jar \
  --home /tmp/gb-resource
```

It then restores the original configuration and checks that the refused
credentials work again, so that a refusal is established to be a refusal
rather than a quiet destruction. Each refusal must arrive as the exact
status and error code RFC 6749 §5.2 entitles that scenario to — `(400,
invalid_grant)` at both observation points. A refusal of some other kind is a
refusal of something else, and the gate aborts rather than counting it.

## Installation and upgrade

`upgrade_gate.py` is different from the two above: it starts and stops its own
GitBucket processes rather than talking to one you started, because what it
checks is startup, migration and restart.

```sh
python3 itest/upgrade_gate.py \
  --war gitbucket.war \
  --previous <the plugin being replaced>.jar \
  --candidate build/libs/gitbucket-mcp-plugin-*.jar \
  --home /tmp/gb-upgrade
```

Add `--db-url jdbc:postgresql://host:port/db --db-user … --db-password …` to
run the whole sequence against PostgreSQL. Without it the bundled H2 is used,
and the PostgreSQL path remains generated DDL rather than an executed
migration.

It refuses to start if something else is already serving the port: a leftover
server answers health checks and would otherwise be mistaken for the artifact
under test. It stops every process it started, including when it aborts.

## The repository listing

`repo_list_gate.py` checks the listing's counters against GitBucket's own REST
API rather than against the plugin's other answers, because the original
defect made two plugin answers disagree and comparing them to each other would
have been the only way to see it. Its fixture holds a repository with real
issues and a pull request, one that is legitimately empty, a private one, and
a real fork, so a correct zero is distinguishable from an unpopulated field.

```sh
python3 itest/repo_list_gate.py
```

Committed effects are observed by asking again rather than by reading the
tables. After a refusal that must change nothing, the lawful operation is
performed next and has to succeed; after a replay that must revoke, a fresh
authorization check has to refuse. That is the property the server actually
has to provide, and it is taken on a connection the first request did not
use.

It needs only the standard library and `git`. It writes nothing outside a
directory from `tempfile.mkdtemp()`, which it removes on exit. Exit codes:

| Exit | Meaning |
| --- | --- |
| 0 | every check ran and passed |
| 1 | a check failed |
| 2 | **the gate could not be run** — a precondition failed, a comparison lost its input, or fewer checks executed than that gate's floor. No result may be read from the run. |

Each gate carries its own floor, declared in its own source rather than here:
`integration.py` 200, `repo_list_gate.py` 35, `upgrade_gate.py` 22,
`resource_gate.py` 15. A run that falls below one is exit 2, not a smaller
pass.

`BASE` and the account passwords are at the top of `mcp.py`. They are
throwaway credentials for a throwaway instance; do not reuse them anywhere.

## Nothing here may be skipped

The two defects this harness was corrected for were both *skips*, not wrong
answers: a precondition that failed quietly, and a comparison whose input went
missing. A green run proved nothing about either, because a skipped check
reports nothing at all. So:

- every setup step goes through `checks.require_ok`, which raises. `fixtures.py`
  verifies that collaborators were actually added, that `issuesOption` and
  `isPrivate` read *back* from storage, that the fork's inherited roles were
  actually removed, and that every Git command exited zero;
- the web merge route is a fixture, not an inline step, so a creation failure
  stops the run instead of skipping the only check of the commit-id recording
  path;
- pull request comparisons go through `checks.require_same_set`, which fails on
  a partial or empty set rather than comparing the intersection;
- GitBucket's own verdict goes through `checks.native_decision`, which accepts
  200/401/403 and raises on anything else. A 5xx is not a refusal: reading it as
  one lets a broken control agree with any answer;
- the stored-state witness for a write goes through `observe.stored_issue_count`
  and `observe.observe_issue_write`, which require 200 **and** a JSON list.
  There is no sentinel for an unavailable read: it used to answer `-1`, so two
  failed reads cancelled into a delta of zero and a refused write was reported
  as having stored nothing. A successful empty list stays a lawful zero;
- timestamps go through `checks.require_iso_utc` and `checks.same_instant`,
  which compare the moment against GitBucket's own value instead of checking
  that a string is non-empty;
- the run aborts if fewer checks executed than the gate's declared floor.

### Proving the guards fire

`python3 itest/selftest.py` drives those guards with no server and no network,
and prints its own total — deliberately not repeated here, because a number
written in prose is wrong the next time a control is added.

Most of its controls are negative: each asserts that a guard **raises** on the
input that used to be tolerated — an empty gateway list, a 500 from
GitBucket's own route, a failed collaborator addition, a `Date.toString`
timestamp, an unreadable stored-issue count, a token-endpoint refusal that is
documented but is not the one the scenario is entitled to. The rest are
lawful twins, which assert the same guard does **not** raise on a legitimate
input. The twins are not padding: a guard that rejects everything passes every
negative control there is, and only a twin notices.

Two of the bypasses were at the *call site* rather than in the helper, so these
controls drive the real code path with API doubles instead of testing a helper
alone. The stored-count section reproduces a worked counterexample: the
read answers 500 with an error object while the doubled tool refuses to its
caller and writes anyway, in three variants — read failing on the way in, read
failing only on the way out, and both reads working so the unintended insert
shows as a delta of 1.

End to end, `ITEST_BREAK` injects one of the same faults into a real run:

| `ITEST_BREAK` | Fault | Required outcome |
| --- | --- | --- |
| `native500` | GitBucket's own issue route answers 500 | abort, exit 2 |
| `webmerge` | the web merge route fails | abort, exit 2 |
| `prlist` | the gateway's pull request list comes back empty | abort, exit 2 |
| `storage` | the stored-issue count cannot be read | abort, exit 2 |

It is off unless the variable names a fault, and a run with it set announces
itself.

## What it covers

| Section | Checks |
| --- | --- |
| transport and identity | `whoami` per account, admin flag, wrong password and unknown account rejected at the transport, registry size over HTTP, descriptor version in `initialize` |
| read authorization | every read tool refused on a private repository without a role, hidden and absent indistinguishable, guest and site-admin access, public readable by any authenticated account, `list_repositories` filtered for the caller |
| issue-creation policy | the `issuesOption` × role × privacy matrix, each case witnessed by the stored issue count **and** compared with GitBucket's own `POST /api/v3/.../issues` verdict |
| issue round-trip | title and body stored byte for byte including Unicode and CRLF, attribution, close/reopen reaching GitBucket's store, a guest refused |
| commit identities | `head_sha` against `git rev-parse`, `merge_base_sha` against `git merge-base`, fork head repository, and the inherited coercion still live on read-only tools |
| `update_pull_request` | omitted-field preservation both ways, explicit clearing, empty title refused, guest and outsider refused, every refusal witnessed as unchanged storage |
| argument strictness | all eight malformed identifier forms against both mutating identifier sites, malformed and null bodies, each with a before/after snapshot of every pull request |
| comments | byte-exact storage, attribution, `total_count` against GitBucket's own count, bounded paging, non-overlap, full coverage, read and write authorization |
| `update_own_comment` | author edits byte-exactly; repository owner, site admin and unrelated account all refused; empty replacement refused; every malformed id refused; a state-change record refused |
| `mark_ready_for_review` | developer role required on the **head** repository of a fork and not the base, stale and malformed preconditions, idempotence, closed and merged refused, nothing but the draft flag changed |
| merged semantics | both of GitBucket's merge routes, `merged` / `merged_at` / `merged_by` / `merged_commit_ids` compared with GitBucket's own fields on every pull request |

## A note on the fork fixture

GitBucket copies a repository's collaborators when it is forked. A developer of
the base is therefore usually also a developer of the fork, which makes the
head-repository role check look like a no-op. `fixtures.py` removes the
inherited collaborators from the fork on purpose, so the distinction is
observable. Without that step the check passes for the wrong reason.
