"""
Build the whole test estate on a blank GitBucket, from zero.

Every step is checked. An earlier version ignored the exit status of its Git
commands and of some API calls, which meant a repository created without its
collaborators, or a fork whose inherited roles were never removed, still
produced a green run — passing for the wrong reason.

Nothing is written outside a temporary directory created by `tempfile`. The
earlier version removed and recreated a hard-coded path under one particular
user's home, which was both unportable and a destructive operation on a path
it did not own.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb import api, Web, BASE
from mcp import ACCOUNTS
from checks import GateError, require, require_ok

OW = ACCOUNTS["owner1"]
REPOS = [("pub-all", False, "ALL"), ("pub-public", False, "PUBLIC"), ("pub-disable", False, "DISABLE"),
         ("priv-all", True, "ALL"), ("priv-private", True, "PRIVATE"), ("priv-disable", True, "DISABLE"),
         ("priv-public", True, "PUBLIC")]
R = "priv-private"

# Set by build(); removed by cleanup().
WORKDIR = None


def git(args, cwd, what):
    """Run Git and stop the run if it failed. Returns stdout, stripped."""
    r = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        raise GateError(f"{what}: git {' '.join(args)} exited {r.returncode}\n"
                        f"  stdout: {r.stdout.strip()[:400]}\n  stderr: {r.stderr.strip()[:400]}")
    return r.stdout.strip()


def _clone(url, dest, what):
    require(not os.path.exists(dest), f"{what}: {dest} already exists")
    git(["-c", "http.proxy=", "clone", "-q", url, dest], cwd=WORKDIR, what=what)
    require(os.path.isdir(os.path.join(dest, ".git")), f"{what}: no .git in {dest}")


def _commit_branch(repo_dir, branch, filename, author, what):
    git(["checkout", "-q", "main"], cwd=repo_dir, what=f"{what}: checkout main")
    with open(os.path.join(repo_dir, filename), "w") as f:
        f.write(f"{branch}\n")
    git(["checkout", "-q", "-b", branch], cwd=repo_dir, what=f"{what}: branch {branch}")
    git(["add", "-A"], cwd=repo_dir, what=f"{what}: add")
    git(["-c", f"user.name={author}", "-c", f"user.email={author}@example.invalid",
         "commit", "-q", "-m", f"{branch} commit"], cwd=repo_dir, what=f"{what}: commit")
    git(["-c", "http.proxy=", "push", "-q", "origin", branch], cwd=repo_dir, what=f"{what}: push {branch}")
    sha = git(["rev-parse", branch], cwd=repo_dir, what=f"{what}: rev-parse")
    require(len(sha) == 40, f"{what}: {branch} did not resolve to a commit id")
    return sha


def cleanup():
    global WORKDIR
    if WORKDIR and os.path.isdir(WORKDIR):
        shutil.rmtree(WORKDIR, ignore_errors=True)
    WORKDIR = None


def build():
    """Create accounts, repositories, roles, Git history and pull requests."""
    global WORKDIR
    WORKDIR = tempfile.mkdtemp(prefix="gitbucket-itest-")

    # The instance must be blank: these fixtures assume nothing already exists.
    st, existing = api("GET", "/api/v3/repos/owner1/priv-private")
    require(st == 404,
            "the target instance is not blank (owner1/priv-private already exists). "
            "This harness must only be pointed at a disposable GitBucket.")

    for login, (u, p) in ACCOUNTS.items():
        if login == "root":
            continue
        st, b = api("POST", "/api/v3/admin/users",
                    {"login": u, "password": p, "email": f"{u}@example.invalid", "full_name": u})
        require_ok(st, b, f"create account {u}")

    for name, private, _ in REPOS:
        st, b = api("POST", "/api/v3/user/repos",
                    {"name": name, "description": f"test {name}", "private": private,
                     "auto_init": True}, auth=OW)
        require_ok(st, b, f"create repository {name}")
        require(b.get("private") is private, f"{name}: privacy was not applied")
        for who, perm in (("devuser", "push"), ("guestuser", "pull")):
            st, b = api("PUT", f"/api/v3/repos/owner1/{name}/collaborators/{who}",
                        {"permission": perm}, auth=OW)
            require_ok(st, b, f"add collaborator {who} to {name}")
        st, cols = api("GET", f"/api/v3/repos/owner1/{name}/collaborators", auth=OW)
        require_ok(st, cols, f"read collaborators of {name}")
        got = {c["login"] for c in cols}
        require({"devuser", "guestuser"} <= got,
                f"{name}: collaborators are {sorted(got)}, expected devuser and guestuser")

    web = Web()
    st, _, cookies = web.signin("owner1", "Test-pass-1")
    require(st == 200 and cookies, "owner1 could not sign in to the web UI")
    for name, private, option in REPOS:
        st, b = web.post(f"/owner1/{name}/settings/options",
                         {"description": f"test {name}", "isPrivate": str(private).lower(),
                          "issuesOption": option, "wikiOption": "PUBLIC", "allowFork": "true",
                          "mergeOptions": ["merge-commit", "squash", "rebase"],
                          "defaultMergeOption": "merge-commit", "safeMode": "false"})
        require_ok(st, b, f"set options on {name}")
    # Read the stored settings back: the form answers 200 either way.
    import re
    for name, private, option in REPOS:
        st, html = web.get(f"/owner1/{name}/settings/options")
        require_ok(st, html, f"read options of {name}")
        checked = re.findall(r'name="issuesOption" value="([A-Z]+)"\s*checked', html)
        require(checked == [option],
                f"{name}: issuesOption reads back as {checked}, expected [{option!r}]")
        priv = re.findall(r'name="isPrivate" value="(\w+)"\s*checked', html)
        require(priv == [str(private).lower()],
                f"{name}: isPrivate reads back as {priv}")

    # --- Git history in the base repository ---------------------------------
    base = os.path.join(WORKDIR, "base")
    _clone(f"http://owner1:Test-pass-1@127.0.0.1:8099/git/owner1/{R}.git", base, "clone base")
    shas = {br: _commit_branch(base, br, fn, "owner1", "base history")
            for br, fn in (("feature-a", "a.txt"), ("feature-b", "b.txt"), ("feature-c", "c.txt"))}

    # --- a fork, with the inherited roles removed ---------------------------
    st, b = api("POST", f"/api/v3/repos/owner1/{R}/forks", {}, auth=ACCOUNTS["guestuser"])
    require_ok(st, b, "fork priv-private as guestuser")
    for _ in range(20):
        st, _ = api("GET", f"/api/v3/repos/guestuser/{R}", auth=ACCOUNTS["guestuser"])
        if st == 200:
            break
        time.sleep(1)
    require(st == 200, "the fork did not become available")

    # GitBucket copies the base repository's collaborators into a fork, so a
    # developer of the base is normally also a developer of the fork. Without
    # removing them the head-repository role check in mark_ready_for_review
    # cannot be observed at all, and an earlier run of it passed for exactly
    # that wrong reason.
    for u in ("devuser", "owner1"):
        st, b = api("DELETE", f"/api/v3/repos/guestuser/{R}/collaborators/{u}",
                    auth=ACCOUNTS["guestuser"])
        require_ok(st, b, f"remove inherited collaborator {u} from the fork")
    st, cols = api("GET", f"/api/v3/repos/guestuser/{R}/collaborators", auth=ACCOUNTS["guestuser"])
    require_ok(st, cols, "read the fork's collaborators")
    remaining = {c["login"] for c in cols}
    require("devuser" not in remaining,
            f"devuser is still a collaborator on the fork ({sorted(remaining)}); the "
            "head-repository role check would pass for the wrong reason")

    fork = os.path.join(WORKDIR, "fork")
    _clone(f"http://guestuser:Test-pass-3@127.0.0.1:8099/git/guestuser/{R}.git", fork, "clone fork")
    shas["fork-feature"] = _commit_branch(fork, "fork-feature", "fork.txt", "guestuser", "fork history")

    # --- pull requests ------------------------------------------------------
    prs = {}
    for key, payload, auth in (
        ("same_draft", {"title": "same-repo draft", "body": "original body",
                        "head": "feature-a", "base": "main", "draft": True}, OW),
        ("fork_draft", {"title": "fork draft", "body": "fork body",
                        "head": "guestuser:fork-feature", "base": "main", "draft": True},
         ACCOUNTS["guestuser"]),
        ("api_merge", {"title": "to merge by api", "body": "b",
                       "head": "feature-b", "base": "main"}, OW),
        ("web_merge", {"title": "to merge by web", "body": "b",
                       "head": "feature-c", "base": "main"}, OW),
    ):
        st, b = api("POST", f"/api/v3/repos/owner1/{R}/pulls", payload, auth=auth)
        require_ok(st, b, f"create pull request {key}")
        prs[key] = b["number"]
    require(prs["same_draft"] != prs["fork_draft"], "pull request numbers collided")
    st, d = api("GET", f"/api/v3/repos/owner1/{R}/pulls/{prs['fork_draft']}", auth=OW)
    require_ok(st, d, "read the fork pull request")
    require(d["head"]["repo"]["full_name"] == f"guestuser/{R}",
            f"the fork pull request's head repository is {d['head']['repo']['full_name']}")
    require(d["draft"] is True, "the fork pull request is not a draft")

    return prs, base, fork, shas
