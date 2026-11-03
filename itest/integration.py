"""
The live-instance gate: every check, on a GitBucket built from zero.

Nothing here may be skipped. Both defects this file was corrected for were
skips rather than wrong answers: a precondition that failed quietly, and a
comparison whose input went missing. So every precondition goes through
`checks.require*`, which raises, and every set comparison goes through
`require_same_set`, which fails on an empty or partial set instead of
comparing whatever happens to be in both answers.

`selftest.py` proves each of those guards fires, without a server.
"""
import sys, os, json, subprocess
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp import *
from harness import check, summary, verdict, RESULTS
from gb import api, Web
from checks import (GateError, require, require_ok, native_decision,
                    require_same_set, same_instant, require_iso_utc)
from observe import stored_issue_count, observe_issue_write
import fixtures

R = "priv-private"
OWNER = ACCOUNTS["owner1"]

def nat_pr(n):
    st, b = api("GET", f"/api/v3/repos/owner1/{R}/pulls/{n}", auth=OWNER)
    require_ok(st, b, f"read pull request #{n} from GitBucket", ok=(200,))
    require(isinstance(b, dict), f"pull request #{n}: GitBucket did not answer an object")
    return b

def nat_iss(n):
    st, b = api("GET", f"/api/v3/repos/owner1/{R}/issues/{n}", auth=OWNER)
    require_ok(st, b, f"read issue #{n} from GitBucket", ok=(200,))
    require(isinstance(b, dict), f"issue #{n}: GitBucket did not answer an object")
    return b

def nat_com(n):
    st, b = api("GET", f"/api/v3/repos/owner1/{R}/issues/{n}/comments", auth=OWNER)
    require_ok(st, b, f"read the comments of #{n} from GitBucket", ok=(200,))
    require(isinstance(b, list), f"comments of #{n}: GitBucket did not answer a list")
    return b

def nat_pr_list(state="all"):
    st, b = api("GET", f"/api/v3/repos/owner1/{R}/pulls?state={state}", auth=OWNER)
    require_ok(st, b, f"list {state} pull requests from GitBucket", ok=(200,))
    require(isinstance(b, list) and b, "GitBucket listed no pull requests at all")
    return b

def git(*a, cwd):
    return fixtures.git(list(a), cwd, "gate: git " + " ".join(a))

# A failed precondition ends the run with its reason and a distinct exit code,
# and never leaves the temporary clone behind. Exit 1 means a check failed;
# exit 2 means the gate could not be run and no result should be read from it.
import atexit
atexit.register(fixtures.cleanup)

def _on_error(kind, value, tb):
    if issubclass(kind, GateError):
        print(f"\nGATE ABORTED: {value}", file=sys.stderr)
        print("No result may be read from this run.", file=sys.stderr)
        sys.exit(2)
    sys.__excepthook__(kind, value, tb)

sys.excepthook = _on_error

# Fault injection, off unless ITEST_BREAK names a fault. It exists so the
# gate's own guards can be shown to fire end to end, not only in
# `selftest.py`: each named fault reproduces one of the skips this gate was
# corrected for, and the run must abort with exit 2 rather than report success.
#   ITEST_BREAK=webmerge   the web merge route fails
#   ITEST_BREAK=prlist     the gateway's pull request list comes back empty
#   ITEST_BREAK=native500  GitBucket's own issue route answers 500
#   ITEST_BREAK=storage    the stored-issue count cannot be read
_FAULTS = {f for f in os.environ.get("ITEST_BREAK", "").split(",") if f}
if _FAULTS:
    print(f"!! fault injection active: {sorted(_FAULTS)} — this run must abort, not pass\n")

def fault(name):
    return name in _FAULTS


# The version the plugin reports has to be checked against the artifact under
# test, not against a literal: a literal is exactly the defect this check
# exists to catch, and it also silently encodes one lineage's version number.
# Fail-closed, like every other precondition here — an unreadable descriptor
# is not a passing check, it is a gate that could not be run.
def _packaged_plugin_version():
    import configparser
    import zipfile

    jar = os.environ.get("MCP_PLUGIN_JAR", "")
    if not jar:
        raise GateError(
            "MCP_PLUGIN_JAR is not set. It must name the exact plugin JAR installed on the "
            "instance under test, so the reported version can be compared with the packaged "
            "descriptor instead of a hardcoded string.")
    if not os.path.isfile(jar):
        raise GateError(f"MCP_PLUGIN_JAR does not name a readable file: {jar}")
    try:
        with zipfile.ZipFile(jar) as z:
            raw = z.read("gitbucket-plugin.properties").decode("utf-8")
    except Exception as exc:
        raise GateError(f"cannot read gitbucket-plugin.properties from {jar}: {exc}")
    parser = configparser.ConfigParser()
    parser.read_string("[d]\n" + raw)
    version = parser["d"].get("pluginversion", "").strip()
    if not version:
        raise GateError(f"{jar} packages a descriptor with no pluginVersion")
    return version


PACKAGED_VERSION = _packaged_plugin_version()
print(f"artifact under test declares pluginVersion={PACKAGED_VERSION}\n")

prs, BASE_DIR, FORK_DIR, SHAS = fixtures.build()
SAME, FORKPR = prs["same_draft"], prs["fork_draft"]
APIMERGE, WEBMERGE = prs["api_merge"], prs["web_merge"]
print(f"fixtures: same-repo draft #{SAME}, fork draft #{FORKPR}, "
      f"api-merge #{APIMERGE}, web-merge #{WEBMERGE}\n")

# ── 1. transport and identity ───────────────────────────────────────────────
print("## 1. transport and identity")
for who in ("devuser","guestuser","outsider","root","owner1"):
    k,p = call(who,"whoami",{})
    check(f"whoami as {who} resolves to the authenticating account", (k, p.get("login") if k=="ok" else p), ("ok",who))
k,p = call("devuser","whoami",{})
check("whoami marks the shipped descriptor as self-reported", p.get("plugin",{}).get("self_reported"), True)
check("whoami reports is_admin truthfully for a plain account", p.get("is_admin"), False)
check("whoami reports is_admin truthfully for a site admin", call("root","whoami",{})[1].get("is_admin"), True)
check("a wrong password is rejected at the transport", rpc("devuser","tools/list",creds=("devuser","WRONG"))[0], 401)
check("an unknown account is rejected at the transport", rpc("devuser","tools/list",creds=("nobody","x"))[0], 401)
st,b = rpc("devuser","tools/list")
check("tools/list publishes the full registry over HTTP", (st, len(b["result"]["tools"])), (200, 19))
check("initialize reports the version packaged in the artifact under test",
      rpc("devuser","initialize",{"protocolVersion":"2024-11-05","capabilities":{}})[1]["result"]["serverInfo"]["version"],
      PACKAGED_VERSION)
check("whoami reports the same packaged version, so both read the descriptor",
      call("devuser","whoami",{})[1].get("plugin",{}).get("plugin_version"), PACKAGED_VERSION)

# ── 2. read authorization, against GitBucket's own role lookup ──────────────
print("\n## 2. read authorization")
READ = {"get_repository":{}, "get_pull_request":{"number":1}, "get_issue":{"number":1},
        "get_thread_comments":{"number":1}, "get_pr_diff":{"number":1},
        "list_issues":{}, "list_pull_requests":{}}
for tool, extra in READ.items():
    k,p = call("outsider", tool, {"owner":"owner1","repo":R, **extra})
    check(f"{tool}: an account with no role is refused on a private repository", verdict(k,p), "not-found")
    k2,p2 = call("outsider", tool, {"owner":"owner1","repo":"no-such-repo", **extra})
    m1 = p.get("message","").replace(R,"X"); m2 = p2.get("message","").replace("no-such-repo","X")
    check(f"{tool}: hidden and absent are indistinguishable", (p.get("code")==p2.get("code"), m1), (True, m2))
check("the guest role opens a private repository", verdict(*call("guestuser","get_repository",{"owner":"owner1","repo":R})), "allowed")
check("a site admin can read a private repository", verdict(*call("root","get_repository",{"owner":"owner1","repo":R})), "allowed")
check("a public repository is readable by any authenticated account", verdict(*call("outsider","get_repository",{"owner":"owner1","repo":"pub-all"})), "allowed")
ALL7 = {n for n,_,_ in fixtures.REPOS}
PUB3 = {n for n,priv,_ in fixtures.REPOS if not priv}
for who, want in (("outsider",PUB3),("guestuser",ALL7),("devuser",ALL7),("root",ALL7),("owner1",ALL7)):
    k,p = call(who,"list_repositories",{"owner":"owner1"})
    got = {r["name"] for r in p.get("repositories",[])} if k=="ok" else {"ERR"}
    check(f"list_repositories as {who} is filtered for the caller", (got, p.get("count")), (want, len(want)))

# ── 3. issue-creation policy, compared with GitBucket's own route ───────────
print("\n## 3. issue-creation policy, against GitBucket's own verdict")
EXPECT = {
 "pub-all":      {"outsider":"allowed","guestuser":"allowed","devuser":"allowed","root":"allowed"},
 "pub-public":   {"outsider":"forbidden","guestuser":"allowed","devuser":"allowed","root":"allowed"},
 "pub-disable":  {"outsider":"forbidden","guestuser":"forbidden","devuser":"forbidden","root":"forbidden"},
 "priv-all":     {"outsider":"not-found","guestuser":"forbidden","devuser":"forbidden","root":"forbidden"},
 "priv-private": {"outsider":"not-found","guestuser":"forbidden","devuser":"allowed","root":"allowed"},
 "priv-disable": {"outsider":"not-found","guestuser":"forbidden","devuser":"forbidden","root":"forbidden"},
 "priv-public":  {"outsider":"not-found","guestuser":"allowed","devuser":"allowed","root":"allowed"},
}
# The storage witness goes through `observe.observe_issue_write`, which reads
# the stored count on both sides of the operation and raises if either read is
# unavailable. It used to be a local helper that returned -1 on a failed read,
# so two failed reads cancelled into a delta of zero and a refusal check
# reported "nothing was stored" without the gate having observed storage.
def _count_api(method, path, body=None, auth=None):
    st, b = api(method, path, body) if auth is None else api(method, path, body, auth)
    if fault("storage") and path.endswith("/issues?state=all"):
        return 500, {"message": "injected: the stored-issue read failed"}
    return st, b

for repo in [n for n,_,_ in fixtures.REPOS]:
    for who in ("outsider","guestuser","devuser","root"):
        want = EXPECT[repo][who]
        k, p, wrote = observe_issue_write(
            _count_api,
            lambda who=who, repo=repo: call(who, "create_issue",
                {"owner":"owner1","repo":repo,"title":f"probe {repo}/{who}","body":"probe"}),
            "owner1", repo)
        v = verdict(k, p)
        check(f"create_issue {repo}/{who}: verdict and stored rows", (v, wrote), (want, 1 if want=="allowed" else 0))
        nst, nb = api("POST", f"/api/v3/repos/owner1/{repo}/issues",
                      {"title":"native","body":"n"}, auth=ACCOUNTS[who])
        # A 5xx is not a refusal. Reading it as one would let a broken server
        # agree with anything, so it ends the run instead.
        if fault("native500"):
            nst = 500
        native = native_decision(nst, f"GitBucket's own create-issue route for {repo}/{who}")
        check(f"create_issue {repo}/{who}: agrees with GitBucket's own route",
              "allowed" if v=="allowed" else "denied", native)

# ── 4. issue round-trip ─────────────────────────────────────────────────────
print("\n## 4. issue round-trip and byte exactness")
T, B = "заголовок ✅", "тіло\nдругий\t漢字\r\nкінець"
k,p = call("devuser","create_issue",{"owner":"owner1","repo":R,"title":T,"body":B})
num = p["number"]; n = nat_iss(num)
check("the issue title is stored byte for byte", n["title"], T)
check("the issue body is stored byte for byte", n["body"], B)
check("the issue is attributed to the authenticating account", n["user"]["login"], "devuser")
check("get_issue reads back the same bytes", tuple(call("devuser","get_issue",{"owner":"owner1","repo":R,"number":num})[1][x] for x in ("title","body")), (T,B))
call("devuser","close_issue",{"owner":"owner1","repo":R,"number":num})
check("close_issue reaches GitBucket's own store", nat_iss(num)["state"], "closed")
call("devuser","reopen_issue",{"owner":"owner1","repo":R,"number":num})
check("reopen_issue reaches GitBucket's own store", nat_iss(num)["state"], "open")
check("a guest cannot close an issue", verdict(*call("guestuser","close_issue",{"owner":"owner1","repo":R,"number":num})), "forbidden")
check("the guest's refused close left the issue open", nat_iss(num)["state"], "open")
call("devuser","add_issue_comment",{"owner":"owner1","repo":R,"number":num,"body":B})
check("an issue comment is stored byte for byte", nat_com(num)[-1]["body"], B)

# ── 5. pull request commit identities ───────────────────────────────────────
print("\n## 5. pull request commit identities, against Git")
git("fetch","-q","origin", cwd=BASE_DIR)
tip = git("rev-parse","origin/feature-a", cwd=BASE_DIR)
mb  = git("merge-base","origin/main","origin/feature-a", cwd=BASE_DIR)
require(len(tip)==40 and len(mb)==40, "Git did not resolve the head and merge base")
require(tip == SHAS["feature-a"], "the pushed head no longer matches what the fixture created")
k,p = call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":SAME})
check("head_sha equals the tip of the head branch as Git reports it", p["head_sha"], tip)
check("merge_base_sha equals git merge-base", p["merge_base_sha"], mb)
check("the stored-snapshot nature of both ids is published", p["commit_ids_are_stored_snapshot"], True)
check("is_draft comes from storage", p["is_draft"], True)
check("a same-repo pull request names its own repository as head", (p["head_user"],p["head_repo"]), ("owner1",R))
k,pf = call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":FORKPR})
check("a cross-repo pull request names the fork as head", (pf["head_user"],pf["head_repo"]), ("guestuser",R))
e1 = call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":1.9})[1]
e2 = call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":1})[1]
check("read-only tools still coerce 1.9 to 1 on this runtime's Jackson", e1.get("message")==e2.get("message"), True)
# Timestamps must be readable by a client regardless of the server's zone.
# `Date.toString` passed a truthiness check while being locale-dependent and
# incomparable with GitBucket's own ISO 8601 answer.
nd_same = nat_pr(SAME)
for field in ("created_at", "updated_at"):
    check(f"{field} is published as an ISO 8601 instant in UTC",
          bool(require_iso_utc(p.get(field), f"get_pull_request {field}")), True,
          note=f"{field}={p.get(field)!r}")
check("created_at names the same instant GitBucket recorded",
      same_instant(p["created_at"], nd_same["created_at"], "created_at"), True,
      note=f"gateway={p['created_at']!r} gitbucket={nd_same['created_at']!r}")

# ── 6. update_pull_request ──────────────────────────────────────────────────
print("\n## 6. update_pull_request")
base_pr = nat_pr(SAME)
call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME,"title":"renamed only"})
a = nat_pr(SAME)
check("a title-only update changes the title", a["title"], "renamed only")
check("a title-only update leaves the body byte-identical", a["body"], base_pr["body"])
k,p = call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME,"body":B})
check("the read-back comparison is reported", (p["title_saved_exactly"],p["body_saved_exactly"]), (True,True))
a = nat_pr(SAME)
check("a body-only update leaves the title untouched", a["title"], "renamed only")
check("a body-only update preserves unicode and CRLF byte for byte", a["body"], B)
call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME,"body":""})
a = nat_pr(SAME)
check("an explicit empty body clears it", a["body"], "")
check("an explicit empty body leaves the title alone", a["title"], "renamed only")
k,p = call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME,"title":""})
check("an empty title is refused", (k, "must not be empty" in str(p.get("message",""))), ("error",True))
check("the refused empty title never reached storage", nat_pr(SAME)["title"], "renamed only")
check("supplying neither field is refused", call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME})[0], "error")
check("a guest cannot update a pull request", verdict(*call("guestuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME,"title":"guest"})), "forbidden")
check("the guest's refused update never reached storage", nat_pr(SAME)["title"], "renamed only")
check("an outsider on a readable public repository is refused as a write", verdict(*call("outsider","update_pull_request",{"owner":"owner1","repo":"pub-all","number":1,"title":"x"})), "forbidden")

# ── 7. argument strictness on live writes ───────────────────────────────────
print("\n## 7. argument strictness on live writes")
FORMS = [(1.9,"a fractional number"),(4294967297,"range"),("1","JSON integer"),(True,"JSON integer"),
         (None,"JSON integer"),({},"JSON integer"),([],"JSON integer"),(0,"positive")]
snap = {n: nat_pr(n) for n in (SAME, FORKPR, APIMERGE)}
for val,frag in FORMS:
    k,p = call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":val,"title":"HIJACKED"})
    check(f"update_pull_request refuses number={json.dumps(val)}", (k, frag in str(p.get("message",""))), ("error",True))
for val,frag in FORMS:
    k,p = call("guestuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":val})
    check(f"mark_ready_for_review refuses number={json.dumps(val)}", (k, frag in str(p.get("message",""))), ("error",True))
for n,was in snap.items():
    now = nat_pr(n)
    check(f"no malformed identifier altered pull request #{n}", (now["title"],now["body"],now["draft"],now["state"]),
          (was["title"],was["body"],was["draft"],was["state"]))
k,p = call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME,"body":{}})
check("a malformed object body is refused instead of erasing the body", (k,"must be a JSON string" in str(p.get("message",""))), ("error",True))
check("the refused object body left storage untouched", nat_pr(SAME)["body"], "")
check("an explicit null body is refused rather than treated as absent", call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":SAME,"body":None})[0], "error")

# ── 8. comments ─────────────────────────────────────────────────────────────
print("\n## 8. comments")
UNI = "коментар\nдругий\t— ✅\r\nкінець 漢字 <b>&amp;</b>"
k,p = call("devuser","add_pr_comment",{"owner":"owner1","repo":R,"number":SAME,"body":UNI})
check("a developer can comment on a pull request", k, "ok")
check("the comment is stored byte for byte", nat_com(SAME)[-1]["body"], UNI)
check("the comment is attributed to the authenticating account", nat_com(SAME)[-1]["user"]["login"], "devuser")
for i in range(6): call("devuser","add_pr_comment",{"owner":"owner1","repo":R,"number":SAME,"body":f"bulk {i}"})
total = len(nat_com(SAME))
k,p = call("devuser","get_thread_comments",{"owner":"owner1","repo":R,"number":SAME,"limit":100})
check("the thread total matches GitBucket's own count", p["total_count"], total)
check("has_more is false on a complete page", p["has_more"], False)
check("ids and authors are published for answering", all("comment_id" in c and "author" in c for c in p["comments"]), True)
k,p1 = call("devuser","get_thread_comments",{"owner":"owner1","repo":R,"number":SAME,"limit":3,"offset":0})
check("a bounded page returns exactly the limit", len(p1["comments"]), 3)
check("has_more is true when more remain", p1["has_more"], True)
check("next_offset points past the page", p1["next_offset"], 3)
k,p2 = call("devuser","get_thread_comments",{"owner":"owner1","repo":R,"number":SAME,"limit":3,"offset":3})
check("consecutive pages do not overlap", set(c["comment_id"] for c in p1["comments"]) & set(c["comment_id"] for c in p2["comments"]), set())
ids = [c["comment_id"] for c in p["comments"]]
check("paging covers the thread exactly once", (len(ids), len(set(ids))), (total,total))
check("a guest can read a thread they may read", call("guestuser","get_thread_comments",{"owner":"owner1","repo":R,"number":SAME})[0], "ok")
check("an outsider cannot read a private thread", verdict(*call("outsider","get_thread_comments",{"owner":"owner1","repo":R,"number":SAME})), "not-found")
check("a guest cannot comment", verdict(*call("guestuser","add_pr_comment",{"owner":"owner1","repo":R,"number":SAME,"body":"no"})), "forbidden")

# ── 9. update_own_comment ───────────────────────────────────────────────────
print("\n## 9. update_own_comment")
cid = [c for c in p["comments"] if c["author"]=="devuser"][0]["comment_id"]
EDIT = "відредаговано\nрядок два\t✅"
k,r = call("devuser","update_own_comment",{"owner":"owner1","repo":R,"comment_id":cid,"body":EDIT})
check("the author can edit their own comment", k, "ok")
check("the edit is stored byte for byte", {c["id"]:c for c in nat_com(SAME)}[cid]["body"], EDIT)
check("the read-back comparison is reported", r["body_saved_exactly"], True)
check("authorship is unchanged by the edit", {c["id"]:c for c in nat_com(SAME)}[cid]["user"]["login"], "devuser")
for who, label in (("owner1","the repository owner"),("root","a site admin"),("guestuser","an unrelated account")):
    k,r = call(who,"update_own_comment",{"owner":"owner1","repo":R,"comment_id":cid,"body":f"{who} takeover"})
    check(f"{label} cannot edit someone else's comment", verdict(k,r), "forbidden" if who!="guestuser" else "forbidden")
    check(f"{label}'s refused edit left the text untouched", {c["id"]:c for c in nat_com(SAME)}[cid]["body"], EDIT)
k,r = call("devuser","update_own_comment",{"owner":"owner1","repo":R,"comment_id":cid,"body":""})
check("an empty replacement is refused rather than blanking the comment", (k,"must not be empty" in str(r.get("message",""))), ("error",True))
check("the refused blanking left the text untouched", {c["id"]:c for c in nat_com(SAME)}[cid]["body"], EDIT)
for val in (1.9,"1",True,None,{},[],0):
    check(f"a malformed comment_id={json.dumps(val)} is refused", call("devuser","update_own_comment",{"owner":"owner1","repo":R,"comment_id":val,"body":"x"})[0], "error")
check("no malformed comment_id altered any comment", {c["id"]:c for c in nat_com(SAME)}[cid]["body"], EDIT)
call("devuser","close_pull_request",{"owner":"owner1","repo":R,"number":SAME})
call("devuser","reopen_pull_request",{"owner":"owner1","repo":R,"number":SAME})
k,p = call("devuser","get_thread_comments",{"owner":"owner1","repo":R,"number":SAME,"limit":200})
acts = [c for c in p["comments"] if c.get("action") not in (None,"comment")]
check("state-change records are surfaced with their action", bool(acts), True)
require(acts, "no close/reopen record appeared, so the non-editable-record check has no subject")
k,r = call("devuser","update_own_comment",{"owner":"owner1","repo":R,"comment_id":acts[0]["comment_id"],"body":"rewrite history"})
check("a state-change record is refused as non-editable text", (k,"not editable comment text" in str(r.get("message",""))), ("error",True))

# ── 10. the Ready transition ────────────────────────────────────────────────
print("\n## 10. mark_ready_for_review")
head = nat_pr(FORKPR)["head"]["sha"]
check("the fork pull request starts as a draft", nat_pr(FORKPR)["draft"], True)
check("an outsider is refused as not-found on the private base", verdict(*call("outsider","mark_ready_for_review",{"owner":"owner1","repo":R,"number":FORKPR})), "not-found")
check("devuser is a developer on the base repository", verdict(*call("devuser","update_pull_request",{"owner":"owner1","repo":R,"number":FORKPR,"title":"fork draft"})), "allowed")
k,p = call("devuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":FORKPR})
check("a developer of the base with no role on the head fork is refused", verdict(k,p), "forbidden")
check("that refusal names the head repository", "guestuser/"+R in str(p.get("message","")), True)
check("that refusal left the draft intact", nat_pr(FORKPR)["draft"], True)
k,p = call("root","mark_ready_for_review",{"owner":"owner1","repo":R,"number":FORKPR,"expected_head_sha":"c"*40})
check("a stale expected_head_sha is refused even for a site admin", (k,"stale expected_head_sha" in str(p.get("message",""))), ("error",True))
check("the stale precondition left the draft intact", nat_pr(FORKPR)["draft"], True)
check("a malformed precondition is refused, not dropped", call("guestuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":FORKPR,"expected_head_sha":"abc"})[0], "error")
check("the malformed precondition left the draft intact", nat_pr(FORKPR)["draft"], True)
was = nat_pr(FORKPR)
k,p = call("guestuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":FORKPR,"expected_head_sha":head})
check("the fork owner can mark it ready", (k,p["was_draft"],p["is_draft"],p["changed"]), ("ok",True,False,True))
check("GitBucket's own API reports it is no longer a draft", nat_pr(FORKPR)["draft"], False)
check("the race guarantee is stated, not overstated", p["race_guarantee"], "checked-then-written; not compare-and-swap")
now = nat_pr(FORKPR)
check("the transition changed nothing but the draft flag",
      (now["title"],now["body"],now["head"]["sha"],now["head"]["label"],now["base"]["label"],now["state"]),
      (was["title"],was["body"],was["head"]["sha"],was["head"]["label"],was["base"]["label"],was["state"]))
k,p = call("guestuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":FORKPR})
check("a second call is idempotent and reports changed=false", (k,p["was_draft"],p["is_draft"],p["changed"]), ("ok",False,False,False))
check("the second call did not re-draft it", nat_pr(FORKPR)["draft"], False)
call("devuser","close_pull_request",{"owner":"owner1","repo":R,"number":SAME})
check("the same-repo pull request is closed and still a draft", (nat_pr(SAME)["state"],nat_pr(SAME)["draft"]), ("closed",True))
k,p = call("devuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":SAME})
check("a closed pull request is refused", (k,"is closed" in str(p.get("message",""))), ("error",True))
check("the closed pull request stayed a draft", nat_pr(SAME)["draft"], True)
call("devuser","reopen_pull_request",{"owner":"owner1","repo":R,"number":SAME})
k,p = call("devuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":SAME,"expected_head_sha":nat_pr(SAME)["head"]["sha"]})
check("once reopened, a developer of the head repository succeeds", (k,p["changed"]), ("ok",True))
check("GitBucket reports the same-repo pull request is ready", nat_pr(SAME)["draft"], False)

# ── 11. merged semantics, both merge routes, neither optional ───────────────
print("\n## 11. merged semantics")
check("an open pull request reports merged=false",
      call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":APIMERGE})[1]["merged"], False)

# --- route A: the REST merge, which records no commit ids -------------------
st, b = api("PUT", f"/api/v3/repos/owner1/{R}/pulls/{APIMERGE}/merge",
            {"commit_message":"api merge"}, auth=OWNER)
require_ok(st, b, f"merge #{APIMERGE} through GitBucket's REST route")
nd = nat_pr(APIMERGE)
require(nd["merged"] is True, f"#{APIMERGE}: GitBucket does not consider it merged after its own merge")
k,p = call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":APIMERGE})
check("after a REST merge GitBucket reports merged=true", nd["merged"], True)
check("after a REST merge the gateway agrees", p["merged"], nd["merged"])
check("merged_by matches GitBucket's record", p.get("merged_by"), nd["merged_by"]["login"])
# Not "is the string non-empty": the same moment, compared with GitBucket's.
check("merged_at is published as an ISO 8601 instant in UTC",
      bool(require_iso_utc(p.get("merged_at"), "merged_at")), True,
      note=f"merged_at={p.get('merged_at')!r}")
check("merged_at names the same instant GitBucket recorded",
      same_instant(p.get("merged_at"), nd.get("merged_at"), "merged_at"), True,
      note=f"gateway={p.get('merged_at')!r} gitbucket={nd.get('merged_at')!r}")
check("merged_commit_ids is absent because the REST route records none", "merged_commit_ids" in p, False)
check("a merged pull request is refused by mark_ready_for_review",
      call("guestuser","mark_ready_for_review",{"owner":"owner1","repo":R,"number":APIMERGE})[0], "error")

# --- route B: the web merge, which does record commit ids -------------------
# Mandatory. The previous version created this pull request inline and skipped
# the whole route if creation failed, so a broken fixture produced a green run
# that had never exercised the recording path at all. The pull request is now
# created in fixtures.py, where failure stops the run.
web = Web()
st, _, cookies = web.signin("owner1", "Test-pass-1")
require(st == 200 and cookies, "owner1 could not sign in for the web merge route")
st, b = web.post(f"/owner1/{R}/pull/{WEBMERGE}/merge",
                 {"message":"web merge","strategy":"merge-commit","isDraft":"false"})
if fault("webmerge"):
    st, b = 500, "injected: the web merge route failed"
require_ok(st, b, f"merge #{WEBMERGE} through GitBucket's web route")
nd = nat_pr(WEBMERGE)
require(nd["merged"] is True, f"#{WEBMERGE}: the web merge route did not mark it merged")
k,p = call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":WEBMERGE})
check("after a web merge GitBucket reports merged=true", nd["merged"], True)
check("after a web merge the gateway agrees", p["merged"], True)
check("merged_by matches GitBucket's record on the web route", p.get("merged_by"), nd["merged_by"]["login"])
check("merged_at names the same instant on the web route",
      same_instant(p.get("merged_at"), nd.get("merged_at"), "merged_at"), True,
      note=f"gateway={p.get('merged_at')!r} gitbucket={nd.get('merged_at')!r}")
ids = p.get("merged_commit_ids")
check("merged_commit_ids is published when GitBucket recorded them",
      isinstance(ids, list) and len(ids) > 0 and all(len(x)==40 for x in ids), True,
      note=f"ids={ids!r}")
check("the two merge routes differ only in whether commit ids were recorded",
      ("merged_commit_ids" in call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":APIMERGE})[1],
       "merged_commit_ids" in p), (False, True))

# --- every pull request, as full sets rather than an intersection -----------
native = {d["number"]: d for d in nat_pr_list("all")}
gateway = {x["number"]: x for x in
           call("devuser","list_pull_requests",{"owner":"owner1","repo":R,"state":"all"})[1]["pull_requests"]}
if fault("prlist"):
    gateway = {}
# A set comparison first: the previous version iterated GitBucket's list and
# silently dropped any number the gateway had not returned, so a gateway that
# lost every row compared nothing and passed.
numbers = require_same_set("list_pull_requests covers exactly GitBucket's pull requests",
                           gateway.keys(), native.keys())
check("the fixture's four pull requests are all present",
      sorted(numbers), sorted([SAME, FORKPR, APIMERGE, WEBMERGE]))
for n in numbers:
    check(f"list_pull_requests #{n} merged flag equals GitBucket's", gateway[n]["merged"], native[n]["merged"])
    st, one = call("devuser","get_pull_request",{"owner":"owner1","repo":R,"number":n})
    require(st == "ok", f"get_pull_request #{n} failed: {one!r}")
    check(f"get_pull_request #{n} merged flag equals GitBucket's", one["merged"], native[n]["merged"])
    check(f"get_pull_request #{n} state equals GitBucket's", one["state"], native[n]["state"])
    check(f"get_pull_request #{n} is_draft equals GitBucket's", one["is_draft"], native[n]["draft"])
check("at least one pull request is merged and at least one is not",
      (any(native[n]["merged"] for n in numbers), any(not native[n]["merged"] for n in numbers)), (True, True))

failed = summary("LIVE INTEGRATION GATE")
print(f"total checks: {len(RESULTS)}")
# A run that executed too few checks is not a pass. The count is a floor, not
# an exact figure, so adding checks does not require editing it; it exists so
# that a run which quietly stopped early cannot report success.
MINIMUM_CHECKS = 200
if len(RESULTS) < MINIMUM_CHECKS:
    print(f"GATE ABORTED: only {len(RESULTS)} checks ran, fewer than the "
          f"{MINIMUM_CHECKS} this gate must execute", file=sys.stderr)
    sys.exit(2)
sys.exit(1 if failed else 0)
