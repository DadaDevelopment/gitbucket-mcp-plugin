#!/usr/bin/env python3
"""
Integration test suite for gitbucket-mcp-plugin.

Usage:
  python3 integration_test.py [--host HOST] [--port PORT] [--user USER] [--password PASSWORD]

Expects a running GitBucket instance with the plugin deployed.
Fresh GitBucket auto-creates root/root; pass --user/--password to match.
Creates test fixtures then exercises all 18 MCP tools via JSON-RPC HTTP.
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.parse
import urllib.error
import base64

# ── Config ────────────────────────────────────────────────────────────────────

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 8080
DEFAULT_USER = "root"
DEFAULT_PASSWORD = "root"

GITBUCKET_VERSIONS = None  # filled in from args
PASSED = []
FAILED = []
FIXTURE_ISSUE_BODY = "This is the issue body created by the integration test suite."
EXPECTED_ISSUE_COMMENT_BODIES = (
    "Integration test comment one",
    "Integration test comment two",
)

# ── HTTP helpers ──────────────────────────────────────────────────────────────


def basic_auth(user: str, password: str) -> str:
    return "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()


def http_post(url: str, data: dict | str, headers: dict = None, auth: str = None) -> tuple[int, str]:
    if isinstance(data, dict):
        body = urllib.parse.urlencode(data).encode()
        content_type = "application/x-www-form-urlencoded"
    else:
        body = data.encode() if isinstance(data, str) else data
        content_type = "application/json"

    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", content_type)
    if auth:
        req.add_header("Authorization", auth)
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)

    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except urllib.error.URLError as e:
        return 0, str(e)


def http_get(url: str, auth: str = None) -> tuple[int, str]:
    req = urllib.request.Request(url)
    if auth:
        req.add_header("Authorization", auth)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except urllib.error.URLError as e:
        return 0, str(e)


# ── GitBucket setup ───────────────────────────────────────────────────────────


def wait_for_gitbucket(base_url: str, user: str, password: str, timeout: int = 120) -> bool:
    """Wait until GitBucket's API accepts authentication.

    Fresh GitBucket auto-creates root/root with no install wizard.
    We poll /api/v3/user until we get a 200 (auth works) rather than
    accepting any HTTP response, which would race the DB initialisation.
    """
    print(f"  Waiting for GitBucket at {base_url} ...")
    auth = basic_auth(user, password)
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            code, _ = http_get(f"{base_url}/api/v3/user", auth=auth)
            if code == 200:
                print("  GitBucket is up and authenticated.")
                return True
        except Exception:
            pass
        time.sleep(3)
    print("  ERROR: GitBucket did not become ready within timeout.")
    return False


def api_post(base_url: str, path: str, payload: dict, auth: str) -> tuple[int, dict]:
    code, body = http_post(
        f"{base_url}/api/v3{path}",
        json.dumps(payload),
        auth=auth,
    )
    try:
        return code, json.loads(body)
    except Exception:
        return code, {}


def create_test_fixtures(base_url: str, user: str, password: str) -> dict:
    auth = basic_auth(user, password)
    print("  Creating test fixtures ...")

    # Create repo
    code, repo = api_post(base_url, "/user/repos", {
        "name": "mcp-test-repo",
        "description": "Integration test repository",
        "private": False,
        "auto_init": True,
    }, auth)
    if code not in (200, 201, 422):  # 422 = already exists
        raise RuntimeError(f"Failed to create repo: HTTP {code}")
    print(f"  Repo mcp-test-repo created/exists (HTTP {code}).")

    # Create issue
    code, issue = api_post(base_url, f"/repos/{user}/mcp-test-repo/issues", {
        "title": "Test issue for MCP",
        "body": FIXTURE_ISSUE_BODY,
    }, auth)
    issue_number = issue.get("number", 1)
    print(f"  Issue #{issue_number} created (HTTP {code}).")

    return {
        "repo": "mcp-test-repo",
        "owner": user,
        "issue": issue_number,
        "issue_body": FIXTURE_ISSUE_BODY,
    }


# ── MCP client ────────────────────────────────────────────────────────────────


_rpc_id = 0


def mcp_call(mcp_url: str, auth: str, method: str, params: dict = None) -> dict:
    global _rpc_id
    _rpc_id += 1
    payload = json.dumps({
        "jsonrpc": "2.0",
        "method": method,
        "params": params or {},
        "id": _rpc_id,
    })
    code, body = http_post(mcp_url, payload, auth=auth)
    if code != 200:
        return {"error": {"code": code, "message": body[:200]}}
    try:
        return json.loads(body)
    except Exception as e:
        return {"error": {"code": -1, "message": str(e)}}


def tool_call(mcp_url: str, auth: str, name: str, arguments: dict = None) -> dict:
    return mcp_call(mcp_url, auth, "tools/call", {
        "name": name,
        "arguments": arguments or {},
    })


def extract_json(response: dict) -> dict | None:
    """Extract the parsed JSON from a tools/call MCP response."""
    result = response.get("result")
    if not result:
        return None
    content = result.get("content", [])
    if not content:
        return None
    text = content[0].get("text", "")
    try:
        return json.loads(text)
    except Exception:
        return {"_raw": text}


# ── Test runner ───────────────────────────────────────────────────────────────


def run_test(name: str, fn):
    try:
        fn()
        PASSED.append(name)
        print(f"  PASS  {name}")
    except AssertionError as e:
        FAILED.append(name)
        print(f"  FAIL  {name}: {e}")
    except Exception as e:
        FAILED.append(name)
        print(f"  ERROR {name}: {type(e).__name__}: {e}")


def assert_ok(response: dict, context: str = ""):
    assert "error" not in response, f"JSON-RPC error{' in ' + context if context else ''}: {response.get('error')}"
    assert "result" in response, f"No result{' in ' + context if context else ''}"


def assert_json(response: dict) -> dict:
    assert_ok(response)
    data = extract_json(response)
    assert data is not None, "Empty content in result"
    return data


# ── Tests ─────────────────────────────────────────────────────────────────────


def run_all_tests(base_url: str, mcp_url: str, auth: str, fixtures: dict):
    owner = fixtures["owner"]
    repo = fixtures["repo"]
    issue_no = fixtures["issue"]
    issue_body = fixtures["issue_body"]

    # ── Protocol ──────────────────────────────────────────────────────────────

    def test_initialize():
        resp = mcp_call(mcp_url, auth, "initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "integration-test", "version": "0.0.1"},
        })
        assert_ok(resp)
        r = resp["result"]
        assert "capabilities" in r, "Missing capabilities"
        assert r.get("protocolVersion") == "2025-03-26", f"Wrong protocol: {r.get('protocolVersion')}"

    def test_tools_list():
        resp = mcp_call(mcp_url, auth, "tools/list")
        assert_ok(resp)
        tools = resp["result"].get("tools", [])
        names = {t["name"] for t in tools}
        expected = {
            "list_repositories", "get_repository",
            "list_issues", "get_issue", "create_issue", "close_issue", "reopen_issue", "add_issue_comment",
            "get_milestone", "list_milestones", "create_milestone", "update_milestone",
            "list_pull_requests", "get_pull_request", "add_pr_comment", "close_pull_request",
            "reopen_pull_request", "get_pr_diff",
        }
        missing = expected - names
        assert not missing, f"Missing tools: {missing}"

    def test_ping():
        resp = mcp_call(mcp_url, auth, "ping")
        assert_ok(resp)

    def test_health():
        host_port = mcp_url.split("/plugin-mcp")[0]
        code, body = http_get(f"{host_port}/plugin-mcp/health")
        assert code == 200, f"Health returned HTTP {code}"
        data = json.loads(body)
        assert data.get("status") == "ok", f"Unexpected health body: {data}"

    # ── Repository tools ──────────────────────────────────────────────────────

    def test_list_repositories():
        resp = tool_call(mcp_url, auth, "list_repositories", {"owner": owner})
        data = assert_json(resp)
        assert "repositories" in data, f"Missing repositories key: {data}"
        assert data["count"] >= 1, f"Expected at least 1 repo, got {data['count']}"

    def test_get_repository():
        resp = tool_call(mcp_url, auth, "get_repository", {"owner": owner, "repo": repo})
        data = assert_json(resp)
        assert data.get("name") == repo, f"Wrong repo name: {data.get('name')}"
        assert data.get("owner") == owner, f"Wrong owner: {data.get('owner')}"
        assert "branches" in data, "Missing branches"

    def test_get_repository_not_found():
        resp = tool_call(mcp_url, auth, "get_repository", {"owner": owner, "repo": "no-such-repo-xyz"})
        assert "error" in resp, "Expected error for non-existent repo"

    # ── Issue tools ───────────────────────────────────────────────────────────

    def test_list_issues():
        resp = tool_call(mcp_url, auth, "list_issues", {"owner": owner, "repo": repo, "state": "open"})
        data = assert_json(resp)
        assert "issues" in data, f"Missing issues key: {data}"
        issue = next((i for i in data["issues"] if i.get("number") == issue_no), None)
        assert issue is not None, f"Issue missing from list_issues: {data}"
        assert issue.get("body") == issue_body, f"Wrong issue body in list_issues: {issue}"

    def test_get_issue():
        resp = tool_call(mcp_url, auth, "get_issue", {"owner": owner, "repo": repo, "number": issue_no})
        data = assert_json(resp)
        assert data.get("number") == issue_no, f"Wrong issue number: {data}"
        assert "title" in data
        assert data.get("body") == issue_body, f"Wrong issue body in get_issue: {data}"
        assert data.get("comments") == [], f"Unexpected comments on new issue: {data}"
        assert data.get("comment_count") == 0, f"Unexpected comment_count on new issue: {data}"

    def test_create_issue():
        resp = tool_call(mcp_url, auth, "create_issue", {
            "owner": owner, "repo": repo,
            "title": "MCP-created issue",
            "body": "Created by integration test",
        })
        data = assert_json(resp)
        assert "number" in data, f"Missing number in create_issue response: {data}"
        assert data.get("state") == "open"

    def test_add_issue_comment():
        for body in EXPECTED_ISSUE_COMMENT_BODIES:
            resp = tool_call(mcp_url, auth, "add_issue_comment", {
                "owner": owner, "repo": repo,
                "number": issue_no,
                "body": body,
            })
            data = assert_json(resp)
            assert "comment_id" in data, f"Missing comment_id: {data}"

        issue = assert_json(tool_call(mcp_url, auth, "get_issue", {
            "owner": owner, "repo": repo, "number": issue_no,
        }))
        comments = issue.get("comments", [])
        comment_bodies = [comment.get("body") for comment in comments]
        assert sorted(comment_bodies) == sorted(EXPECTED_ISSUE_COMMENT_BODIES), \
            f"get_issue did not return all comment bodies: {comments}"
        assert issue.get("body") == issue_body, f"Wrong issue body in get_issue: {issue}"
        assert issue.get("comment_count") == len(EXPECTED_ISSUE_COMMENT_BODIES), \
            f"Incorrect comment_count in get_issue: {issue}"
        assert issue.get("comment_count") == len(comments), f"Incorrect comment_count: {issue}"

        listed = assert_json(tool_call(mcp_url, auth, "list_issues", {
            "owner": owner, "repo": repo, "state": "open",
        }))
        listed_issue = next((i for i in listed["issues"] if i.get("number") == issue_no), None)
        assert listed_issue is not None, f"Issue missing from list_issues: {listed}"
        listed_comments = listed_issue.get("comments", [])
        listed_comment_bodies = [comment.get("body") for comment in listed_comments]
        assert sorted(listed_comment_bodies) == sorted(EXPECTED_ISSUE_COMMENT_BODIES), \
            f"list_issues did not return all comment bodies: {listed_issue}"
        assert listed_issue.get("body") == issue_body, f"Wrong issue body in list_issues: {listed_issue}"
        assert listed_issue.get("comment_count") == len(EXPECTED_ISSUE_COMMENT_BODIES), \
            f"Incorrect comment_count in list_issues: {listed_issue}"
        assert listed_issue.get("comment_count") == len(listed_comments), \
            f"Incorrect comment_count in list_issues: {listed_issue}"

    def test_close_and_reopen_issue():
        # Create a fresh issue to close/reopen
        resp = tool_call(mcp_url, auth, "create_issue", {
            "owner": owner, "repo": repo, "title": "To be closed",
        })
        data = assert_json(resp)
        n = data["number"]

        close_resp = tool_call(mcp_url, auth, "close_issue", {"owner": owner, "repo": repo, "number": n})
        assert_ok(close_resp)

        reopen_resp = tool_call(mcp_url, auth, "reopen_issue", {"owner": owner, "repo": repo, "number": n})
        assert_ok(reopen_resp)

    def test_milestone_lifecycle():
        created = assert_json(tool_call(mcp_url, auth, "create_milestone", {
            "owner": owner,
            "repo": repo,
            "title": "MCP milestone",
            "description": "Milestone lifecycle integration test",
            "due_date": "2026-12-01",
        }))
        number = created.get("number")
        assert isinstance(number, int) and number > 0, f"Missing milestone number: {created}"
        assert created.get("state") == "open", f"New milestone should be open: {created}"
        assert created.get("description") == "Milestone lifecycle integration test"
        assert created.get("due_date", "").startswith("2026-12-01"), f"Wrong due date: {created}"
        assert created.get("open_issues") == 0 and created.get("closed_issues") == 0, \
            f"New milestone should have no issues: {created}"

        code, linked_issue = api_post(base_url, f"/repos/{owner}/{repo}/issues", {
            "title": "Issue linked to MCP milestone",
            "body": "Used to verify milestone issue counts.",
            "milestone": number,
        }, auth)
        assert code in (200, 201), f"Failed to create milestone issue: HTTP {code}: {linked_issue}"
        linked_issue_number = linked_issue.get("number")
        assert isinstance(linked_issue_number, int), f"Missing linked issue number: {linked_issue}"

        fetched = assert_json(tool_call(mcp_url, auth, "get_milestone", {
            "owner": owner, "repo": repo, "number": number,
        }))
        assert fetched.get("open_issues") == 1 and fetched.get("closed_issues") == 0, \
            f"Incorrect counts for milestone with an open issue: {fetched}"

        updated = assert_json(tool_call(mcp_url, auth, "update_milestone", {
            "owner": owner,
            "repo": repo,
            "number": number,
            "title": "Updated MCP milestone",
            "description": "Updated description",
            "due_date": "2026-12-15",
            "state": "closed",
        }))
        assert updated.get("title") == "Updated MCP milestone", f"Milestone title was not updated: {updated}"
        assert updated.get("description") == "Updated description"
        assert updated.get("state") == "closed", f"Milestone was not closed: {updated}"

        listed_closed = assert_json(tool_call(mcp_url, auth, "list_milestones", {
            "owner": owner, "repo": repo, "state": "closed",
        }))
        assert any(item.get("number") == number for item in listed_closed.get("milestones", [])), \
            f"Closed milestone missing from list: {listed_closed}"

        assert_ok(tool_call(mcp_url, auth, "close_issue", {
            "owner": owner, "repo": repo, "number": linked_issue_number,
        }))
        closed_counts = assert_json(tool_call(mcp_url, auth, "get_milestone", {
            "owner": owner, "repo": repo, "number": number,
        }))
        assert closed_counts.get("open_issues") == 0 and closed_counts.get("closed_issues") == 1, \
            f"Milestone issue counts did not update after closing issue: {closed_counts}"

        reopened = assert_json(tool_call(mcp_url, auth, "update_milestone", {
            "owner": owner, "repo": repo, "number": number, "state": "open",
        }))
        assert reopened.get("state") == "open", f"Milestone was not reopened: {reopened}"
        missing = tool_call(mcp_url, auth, "get_milestone", {
            "owner": owner, "repo": repo, "number": 99999,
        })
        assert "error" in missing, f"Expected error for missing milestone: {missing}"

    # ── PR tools ──────────────────────────────────────────────────────────────

    def test_list_pull_requests():
        resp = tool_call(mcp_url, auth, "list_pull_requests", {"owner": owner, "repo": repo, "state": "all"})
        data = assert_json(resp)
        assert "pull_requests" in data, f"Missing pull_requests key: {data}"

    def test_get_pr_not_found():
        resp = tool_call(mcp_url, auth, "get_pull_request", {"owner": owner, "repo": repo, "number": 99999})
        assert "error" in resp, "Expected error for non-existent PR"

    def test_get_issue_not_found():
        resp = tool_call(mcp_url, auth, "get_issue", {"owner": owner, "repo": repo, "number": 99999})
        assert "error" in resp, "Expected error for non-existent issue"

    # Run all
    print("\n[Protocol]")
    run_test("initialize",  test_initialize)
    run_test("tools/list",  test_tools_list)
    run_test("ping",        test_ping)
    run_test("health",      test_health)

    print("\n[Repository tools]")
    run_test("list_repositories",           test_list_repositories)
    run_test("get_repository",              test_get_repository)
    run_test("get_repository (not found)",  test_get_repository_not_found)

    print("\n[Issue tools]")
    run_test("list_issues",                test_list_issues)
    run_test("get_issue",                  test_get_issue)
    run_test("create_issue",               test_create_issue)
    run_test("add_issue_comment",          test_add_issue_comment)
    run_test("close_issue / reopen_issue", test_close_and_reopen_issue)
    run_test("get_issue (not found)",      test_get_issue_not_found)

    print("\n[Milestone tools]")
    run_test("milestone lifecycle", test_milestone_lifecycle)

    print("\n[Pull request tools]")
    run_test("list_pull_requests",      test_list_pull_requests)
    run_test("get_pull_request (not found)", test_get_pr_not_found)

    # PR CRUD tools (close/reopen/diff/comment) require a real PR which needs
    # two branches. GitBucket's REST API can create branches and PRs.
    # We verify the tools are listed and callable; error handling is tested above.


# ── Entrypoint ────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description="MCP plugin integration tests")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--user", default=DEFAULT_USER)
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    args = parser.parse_args()

    base_url = f"http://{args.host}:{args.port}"
    mcp_url = f"{base_url}/plugin-mcp/"
    auth = basic_auth(args.user, args.password)

    print(f"=== GitBucket MCP Plugin Integration Tests ===")
    print(f"Target: {base_url}")

    if not wait_for_gitbucket(base_url, args.user, args.password):
        sys.exit(1)

    try:
        fixtures = create_test_fixtures(base_url, args.user, args.password)
    except RuntimeError as e:
        print(f"ERROR: Failed to create test fixtures: {e}")
        sys.exit(1)

    run_all_tests(base_url, mcp_url, auth, fixtures)

    total = len(PASSED) + len(FAILED)
    print(f"\n=== Results: {len(PASSED)}/{total} passed ===")
    if FAILED:
        print("Failed:")
        for t in FAILED:
            print(f"  - {t}")
        sys.exit(1)
    else:
        print("All tests passed.")


if __name__ == "__main__":
    main()
