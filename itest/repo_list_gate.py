"""
The repository listing, against GitBucket's own numbers.

    python3 itest/repo_list_gate.py

`list_repositories` published `issue_count` and `pr_count` as zero for every
repository, while `get_repository` reported real values for the same
repository. The cause was not a serialization slip: GitBucket's
`getUserRepositories` builds each `RepositoryInfo` through the constructor its
own source documents as "without issue, pull request, and milestone count".
Those zeroes were placeholders being published as measurements.

So the controls here never compare two plugin answers to each other alone.
Every count is also checked against GitBucket's own REST API, which is
computed by a different code path from the one under test. Where the API
cannot answer (it has no issue-state route in 4.48.0), the fixture drives
GitBucket's own web route and then re-reads through the API.

The fixture deliberately contains a repository with nothing in it. A gate
whose every expectation is nonzero cannot tell a correct zero from a
placeholder, which is the whole of the original defect.
"""
import json
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb import api, Web
from mcp import ACCOUNTS, call, rpc
from harness import check, summary
from checks import GateError, require

OWNER = ("owner1", "Test-pass-1")


def native_open(owner, repo, kind):
    """Open issues or pull requests, counted by GitBucket's own API."""
    path = "issues" if kind == "issue" else "pulls"
    status, body = api("GET", f"/api/v3/repos/{owner}/{repo}/{path}?state=open")
    require(status == 200 and isinstance(body, list),
            f"cannot read native open {path} for {owner}/{repo} ({status}); "
            f"this gate has no independent witness")
    if kind == "issue":
        # GitBucket's issues endpoint already excludes pull requests.
        return len(body)
    return len(body)


def native_repo(owner, repo):
    status, body = api("GET", f"/api/v3/repos/{owner}/{repo}")
    require(status == 200 and isinstance(body, dict),
            f"cannot read {owner}/{repo} natively ({status})")
    return body


def listed(owner, who="owner1", **extra):
    kind, payload = call(who, "list_repositories", dict(owner=owner, **extra))
    require(kind == "ok", f"list_repositories failed for {who}: {payload}")
    return {e["name"]: e for e in payload["repositories"]}, payload


def detail(owner, repo, who="owner1"):
    kind, payload = call(who, "get_repository", {"owner": owner, "repo": repo})
    return kind, payload


# ── fixture ─────────────────────────────────────────────────────────────────

def build():
    """Repositories with counts that differ, including a legitimate zero."""
    st, existing = api("GET", "/api/v3/repos/owner1/busy")
    require(st == 404,
            "the target instance is not blank (owner1/busy already exists). "
            "This gate creates accounts and repositories and must only ever be "
            "pointed at a disposable GitBucket.")
    for login, (u, pw) in ACCOUNTS.items():
        if login == "root":
            continue
        st, b = api("POST", "/api/v3/admin/users",
                    {"login": u, "password": pw, "email": f"{u}@example.invalid",
                     "full_name": u})
        require(st in (200, 201), f"could not create account {u} ({st}): {b}")

    web = Web()
    st, _, cookies = web.signin(*OWNER)
    require(st == 200 and cookies, "owner1 could not sign in to the web UI")

    def repo(name, private):
        status, _ = api("POST", "/api/v3/user/repos",
                        {"name": name, "description": name, "private": private,
                         "auto_init": True}, auth=OWNER)
        require(status in (200, 201), f"could not create {name} ({status})")

    repo("busy", False)      # open and closed issues, and a pull request
    repo("quiet", False)     # nothing at all: a correct zero
    repo("secret", True)     # private, for the visibility cases

    for i in range(3):
        status, _ = api("POST", "/api/v3/repos/owner1/busy/issues",
                        {"title": f"open-{i}", "body": "x"}, auth=OWNER)
        require(status in (200, 201), f"could not open issue {i} ({status})")

    status, closing = api("POST", "/api/v3/repos/owner1/busy/issues",
                          {"title": "closed-one", "body": "x"}, auth=OWNER)
    require(status in (200, 201), f"could not create the issue to close ({status})")

    # 4.48.0's REST API has no issue-state route, so the close goes through
    # GitBucket's own web route and is then confirmed by re-reading the API.
    st, _ = web.post(f"/owner1/busy/issues/batchedit/state",
                     {"value": "close", "checked": str(closing["number"]),
                      "from": "", "uri": ""})
    require(st in (200, 302), f"the web close route answered {st}")

    status, body = api("GET", "/api/v3/repos/owner1/busy/issues?state=open")
    require(status == 200 and isinstance(body, list), "cannot confirm the close")
    require(len(body) == 3,
            f"the close did not take effect: {len(body)} open issues, expected 3")

    # A pull request, so that an issue count that includes pull requests is
    # distinguishable from one that does not.
    status, _ = api("POST", "/api/v3/repos/owner1/busy/git/refs",
                    {"ref": "refs/heads/feature",
                     "sha": api("GET", "/api/v3/repos/owner1/busy/git/refs/heads/main")[1]
                     ["object"]["sha"]}, auth=OWNER)
    require(status in (200, 201), f"could not branch ({status})")
    status, _ = api("PUT", "/api/v3/repos/owner1/busy/contents/feature.txt",
                    {"message": "add", "content": "aGk=", "branch": "feature"}, auth=OWNER)
    require(status in (200, 201), f"could not commit on the branch ({status})")
    status, _ = api("POST", "/api/v3/repos/owner1/busy/pulls",
                    {"title": "a pull request", "head": "feature", "base": "main"}, auth=OWNER)
    require(status in (200, 201), f"could not open the pull request ({status})")

    # A real fork, so `without_forked` and `is_fork` have something to act on.
    status, _ = api("POST", "/api/v3/repos/owner1/busy/forks", {}, auth=("devuser", "Test-pass-2"))
    require(status in (200, 201, 202), f"could not fork ({status})")
    return web


def run():
    build()

    print("## 1. the listing agrees with GitBucket's own numbers")
    entries, payload = listed("owner1")
    for name in ("busy", "quiet"):
        native = native_repo("owner1", name)
        open_issues = native_open("owner1", name, "issue")
        open_pulls = native_open("owner1", name, "pull")
        e = entries.get(name)
        require(e is not None, f"{name} is missing from the listing")
        check(f"{name}: issue_count equals GitBucket's open issues",
              e["issue_count"], open_issues)
        check(f"{name}: pr_count equals GitBucket's open pull requests",
              e["pr_count"], open_pulls)
        check(f"{name}: fork_count equals GitBucket's forks_count",
              e["fork_count"], native["forks_count"])
        check(f"{name}: is_fork equals GitBucket's fork flag",
              e["is_fork"], native["fork"])

    print("\n## 2. the fixture distinguishes a real zero from a placeholder")
    check("busy has a nonzero issue count", entries["busy"]["issue_count"] > 0, True)
    check("busy has a nonzero pr count", entries["busy"]["pr_count"] > 0, True)
    check("quiet is legitimately zero", (entries["quiet"]["issue_count"],
                                         entries["quiet"]["pr_count"]), (0, 0))
    check("the two repositories do not report the same counts",
          entries["busy"]["issue_count"] == entries["quiet"]["issue_count"], False)

    print("\n## 3. closed issues and pull requests are excluded")
    status, all_issues = api("GET", "/api/v3/repos/owner1/busy/issues?state=all")
    require(status == 200 and isinstance(all_issues, list), "cannot read all issues")
    check("GitBucket holds more issues than the open count",
          len(all_issues) > entries["busy"]["issue_count"], True)
    check("the pull request is not counted as an issue",
          entries["busy"]["issue_count"], native_open("owner1", "busy", "issue"))

    print("\n## 4. the listing agrees with the detail of the same repository")
    for name in ("busy", "quiet"):
        kind, d = detail("owner1", name)
        require(kind == "ok", f"get_repository failed for {name}: {d}")
        e = entries[name]
        check(f"{name}: list and detail agree on issue_count",
              e["issue_count"], d["issue_count"])
        check(f"{name}: list and detail agree on pr_count", e["pr_count"], d["pr_count"])
        check(f"{name}: list and detail agree on fork_count", e["fork_count"], d["fork_count"])
        check(f"{name}: list and detail agree on is_fork", e["is_fork"], d["is_fork"])

    print("\n## 5. the fork filter acts on the fork relationship")
    forks, _ = listed("devuser", who="devuser")
    fork_names = [n for n, e in forks.items() if e["is_fork"]]
    check("the forked repository is visible to its owner and marked a fork",
          len(fork_names) >= 1, True)
    for n in fork_names:
        check(f"{n}: GitBucket agrees it is a fork", native_repo("devuser", n)["fork"], True)

    omitted, _ = listed("devuser", who="devuser")
    explicit_false, _ = listed("devuser", who="devuser", without_forked=False)
    excluded, _ = listed("devuser", who="devuser", without_forked=True)
    check("omitted and false behave the same", sorted(omitted), sorted(explicit_false))
    check("true removes exactly the forks",
          sorted(excluded), sorted(n for n in omitted if not omitted[n]["is_fork"]))
    check("and leaves the originals", all(not excluded[n]["is_fork"] for n in excluded), True)

    print("\n## 6. the fork filter does not disturb the counters")
    busy_excluded, _ = listed("owner1", without_forked=True)
    check("busy still reports its real issue count under without_forked",
          busy_excluded["busy"]["issue_count"], entries["busy"]["issue_count"])
    check("and no negative placeholder appears as a fork count",
          all(e["fork_count"] >= 0 for e in busy_excluded.values()), True)

    print("\n## 7. visibility is unchanged by enrichment")
    for who, sees_private in (("outsider", False), ("devuser", False), ("root", True)):
        seen, _ = listed("owner1", who=who)
        check(f"{who} sees the public repositories", "busy" in seen, True)
        check(f"{who} sees the private repository: {sees_private}",
              "secret" in seen, sees_private)
    owner_view, _ = listed("owner1", who="owner1")
    check("the owner sees their own private repository", "secret" in owner_view, True)
    kind, _ = detail("owner1", "secret", who="outsider")
    check("and an outsider is refused its detail", kind, "error")

    print("\n## 8. the payload says what the counters mean")
    check("the listing documents its issue counter",
          payload.get("issue_count_semantics"), "open issues, excluding pull requests")
    check("the listing documents its pull request counter",
          payload.get("pr_count_semantics"), "open pull requests")
    check("the listing documents the fork counter",
          payload.get("fork_count_semantics"),
          "forks of this repository's origin; not a flag that this repository is a fork")

    failed = summary("REPOSITORY LIST GATE")
    ran = len(__import__("harness").RESULTS)
    if ran < 35:
        raise GateError(f"only {ran} checks executed; this gate has no result below 35")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(run())
    except GateError as exc:
        print(f"\nGATE ABORTED: {exc}", file=sys.stderr)
        print("No result may be read from this run.", file=sys.stderr)
        sys.exit(2)
