"""
Negative controls for the gate's own guards. No server, no network.

The gate's two P2 defects were skips, not wrong answers: a precondition that
failed quietly and a comparison whose input went missing. A green run proved
nothing about either, because the skipped case never reported anything. These
controls assert the opposite property directly — that each guard *raises* on
the input that used to be tolerated.

Run with `python3 itest/selftest.py`. Exit 0 means every guard fires.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from checks import (GateError, require, require_ok, native_decision,
                    require_same_set, same_instant, java_date_to_epoch, iso_to_epoch,
                    require_iso_utc)
from observe import stored_issue_count, observe_issue_write

RESULTS = []


def fires(name, fn, expect_fragment=None):
    """The guard must raise GateError, and say why."""
    try:
        fn()
    except GateError as e:
        ok = expect_fragment is None or expect_fragment in str(e)
        RESULTS.append((ok, name, str(e)[:140]))
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            print(f"        message did not mention {expect_fragment!r}: {e}")
        return
    RESULTS.append((False, name, "did not raise"))
    print("FAIL " + name + "\n        the guard did not fire; this is the skip the gate was corrected for")


def allows(name, fn, want=None):
    """The guard must not raise on a legitimate input."""
    try:
        got = fn()
    except GateError as e:
        RESULTS.append((False, name, str(e)[:140]))
        print("FAIL " + name + f"\n        raised on a legitimate input: {e}")
        return
    ok = want is None or got == want
    RESULTS.append((ok, name, repr(got)[:140]))
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        print(f"        got {got!r}, want {want!r}")


print("## 1. a failed precondition must stop the run, not be skipped")
# Defect 1: fixtures.py ignored exit statuses, so a repository created without
# its collaborators still produced a green run.
fires("a failed repository creation aborts",
      lambda: require_ok(422, {"message": "already exists"}, "create repository pub-all"),
      "create repository pub-all")
fires("a failed collaborator addition aborts",
      lambda: require_ok(404, {"message": "Not Found"}, "add collaborator devuser to pub-all"),
      "got 404")
fires("a failed removal of the fork's inherited roles aborts",
      lambda: require_ok(500, "boom", "remove inherited collaborator devuser from the fork"),
      "inherited collaborator")
fires("an unmet invariant aborts",
      lambda: require(False, "devuser is still a collaborator on the fork"),
      "still a collaborator")
allows("a successful creation is allowed through",
       lambda: require_ok(200, {"number": 7}, "create pull request"), {"number": 7})
allows("204 counts as success for a delete",
       lambda: require_ok(204, b"", "remove collaborator"), b"")

print("\n## 2. the web-merge route may not be optional")
# Defect 2a: creating the web-merge pull request inline meant a creation
# failure skipped the whole route. The pull request is now a fixture, and this
# is the guard that made it one.
fires("a web-merge pull request that cannot be created aborts",
      lambda: require_ok(500, {"message": "internal"}, "create pull request web_merge"),
      "web_merge")
fires("a web merge that does not go through aborts",
      lambda: require_ok(400, "not mergeable", "merge #4 through GitBucket's web route"),
      "web route")
fires("a web merge GitBucket does not consider merged aborts",
      lambda: require(False, "#4: the web merge route did not mark it merged"),
      "did not mark it merged")

print("\n## 3. a set comparison may not fall back to the intersection")
# Defect 2b: the comparison iterated GitBucket's list and dropped any number
# the gateway had not returned.
fires("a gateway list that loses every pull request aborts",
      lambda: require_same_set("list_pull_requests", set(), {1, 2, 3, 4}),
      "missing from the gateway: [1, 2, 3, 4]")
fires("a gateway list that loses one pull request aborts",
      lambda: require_same_set("list_pull_requests", {1, 2, 3}, {1, 2, 3, 4}),
      "missing from the gateway: [4]")
fires("a gateway list with an extra pull request aborts",
      lambda: require_same_set("list_pull_requests", {1, 2, 3, 4, 9}, {1, 2, 3, 4}),
      "not in GitBucket's answer: [9]")
fires("two empty sets abort rather than compare nothing",
      lambda: require_same_set("list_pull_requests", set(), set()),
      "nothing to compare")
allows("equal sets are allowed through",
       lambda: require_same_set("list_pull_requests", {4, 1, 2, 3}, {1, 2, 3, 4}), [1, 2, 3, 4])

print("\n## 4. a server error is not an authorization verdict")
# Defect 2c: a 500 from GitBucket's own issue route was read as "refused", so
# a broken control agreed with whatever the gateway said.
for code in (500, 502, 503, 404, 400, 302):
    fires(f"HTTP {code} from GitBucket's own route aborts instead of counting as a refusal",
          lambda c=code: native_decision(c, "GitBucket's own create-issue route for priv-all/guestuser"),
          f"HTTP {code}")
allows("200 is an allow", lambda: native_decision(200, "x"), "allowed")
allows("401 is a refusal", lambda: native_decision(401, "x"), "denied")
allows("403 is a refusal", lambda: native_decision(403, "x"), "denied")

print("\n## 5. merged_at must be an instant, not a non-empty string")
# The review's item 4: comparing truthiness proved only that something was
# published.
allows("the same moment in both formats compares equal",
       lambda: same_instant("Thu Oct 23 11:59:31 UTC 2026", "2026-10-23T11:59:31Z", "merged_at"), True)
allows("a one-second difference is tolerated",
       lambda: same_instant("Thu Oct 23 11:59:32 UTC 2026", "2026-10-23T11:59:31Z", "merged_at"), True)
allows("a different moment compares unequal rather than passing",
       lambda: same_instant("Thu Oct 23 12:04:31 UTC 2026", "2026-10-23T11:59:31Z", "merged_at"), False)
allows("a day out compares unequal",
       lambda: same_instant("Fri Oct 24 11:59:31 UTC 2026", "2026-10-23T11:59:31Z", "merged_at"), False)
fires("an empty gateway timestamp aborts instead of comparing as falsy",
      lambda: same_instant("", "2026-10-23T11:59:31Z", "merged_at"), "cannot read")
fires("a missing gateway timestamp aborts",
      lambda: same_instant(None, "2026-10-23T11:59:31Z", "merged_at"), "cannot read")
fires("an unparseable gateway timestamp aborts",
      lambda: same_instant("yesterday", "2026-10-23T11:59:31Z", "merged_at"), "Java Date")
fires("a timestamp in an unknown zone aborts rather than guessing an offset",
      lambda: java_date_to_epoch("Thu Oct 23 11:59:31 PST 2026", "merged_at"), "zone"),
fires("an unparseable GitBucket timestamp aborts",
      lambda: iso_to_epoch("n/a", "merged_at"), "ISO 8601")

print("\n## 6. the gateway's own timestamps must be machine-readable")
allows("an ISO 8601 UTC instant is accepted",
       lambda: require_iso_utc("2026-10-23T12:38:41Z", "merged_at"), 1792759121)
fires("a Java Date string is refused as a gateway timestamp",
      lambda: require_iso_utc("Fri Oct 23 15:38:41 EEST 2026", "merged_at"), "not an ISO 8601")
fires("an instant with an offset instead of Z is refused",
      lambda: require_iso_utc("2026-10-23T15:38:41+03:00", "merged_at"), "not an ISO 8601")
fires("a date without a time is refused",
      lambda: require_iso_utc("2026-10-23", "merged_at"), "not an ISO 8601")
fires("an empty timestamp is refused",
      lambda: require_iso_utc("", "merged_at"), "not an ISO 8601")
allows("ISO against ISO compares as the same instant",
       lambda: same_instant("2026-10-23T12:38:41Z", "2026-10-23T12:38:41Z", "merged_at"), True)
allows("ISO against a different ISO compares unequal",
       lambda: same_instant("2026-10-23T12:38:41Z", "2026-10-23T12:40:41Z", "merged_at"), False)

print("\n## 7. an unavailable storage witness is not a count")
# The defect: the witness was `len(r) if isinstance(r, list) else -1`, so two
# failed reads cancelled into a delta of zero and a refused write was reported
# as having stored nothing while the gate had observed nothing at all.
# These drive the real `observe.stored_issue_count`, not a reimplementation.

def api_double(status, body):
    """GitBucket's REST API, doubled. Records every call it is given."""
    calls = []
    def fn(method, path, payload=None, auth=None):
        calls.append((method, path))
        return (status, body)
    fn.calls = calls
    return fn

allows("a successful empty list is a lawful count of zero",
       lambda: stored_issue_count(api_double(200, []), "owner1", "pub-disable"), 0)
allows("a successful populated list counts its rows",
       lambda: stored_issue_count(api_double(200, [{"number": 1}, {"number": 2}]), "owner1", "pub-all"), 2)
for status in (500, 502, 503, 401, 404, 302, 204):
    fires(f"HTTP {status} on the stored-issue read aborts instead of becoming a count",
          lambda s=status: stored_issue_count(api_double(s, {"message": "x"}), "owner1", "pub-disable"),
          "read the stored issues of owner1/pub-disable")
fires("HTTP 200 with an object instead of a list aborts",
      lambda: stored_issue_count(api_double(200, {"message": "Not Found"}), "owner1", "pub-disable"),
      "not a JSON list")
fires("HTTP 200 with a string body aborts",
      lambda: stored_issue_count(api_double(200, "<html>oops</html>"), "owner1", "pub-disable"),
      "not a JSON list")
fires("HTTP 200 with a null body aborts",
      lambda: stored_issue_count(api_double(200, None), "owner1", "pub-disable"),
      "not a JSON list")

print("\n## 8. the counterexample, through the real call path")
# The case this section exists for: the stored-issue GET answers 500 with an
# error object, GitBucket's own POST answers the expected 401, and the gateway
# records an unintended insert and then returns its policy error. With the old
# witness every one of those cases reported a pass. A test of require_ok in
# isolation could not have caught it, because the bypass was at the call
# site, so these drive the gate's real `observe_issue_write`.

def inserting_refusal(world):
    """A tool that refuses to the caller but writes anyway."""
    def operation():
        world.append("INSERT")
        return "error", {"code": -2,
                         "message": "guestuser may not open an issue in owner1/pub-disable"}
    return operation

def one_policy_case(api_fn, world):
    """Exactly what the gate's policy loop does for one case."""
    return observe_issue_write(api_fn, inserting_refusal(world), "owner1", "pub-disable")

def scripted_api(*answers):
    """An API double that answers each read in turn."""
    seq = iter(answers)
    return lambda method, path, body=None, auth=None: next(seq)

# (a) the read fails on the way in: the gate stops before the write is even
#     attempted, which is better than detecting it afterwards.
for status, body, label in ((500, {"message": "Internal Server Error"}, "HTTP 500 and an error object"),
                            (200, {"message": "Not Found"}, "HTTP 200 and a non-list body"),
                            (404, [], "HTTP 404 and an empty list")):
    world = []
    fires(f"an unavailable witness aborts the case before the write, answering {label}",
          lambda a=status, b=body, w=world: one_policy_case(api_double(a, b), w),
          "stored issues")
    ok = world == []
    RESULTS.append((ok, f"...and the operation was never run ({label})", repr(world)))
    print(("PASS " if ok else "FAIL ") + f"...and the operation was never run ({label})")

# (b) the read fails only on the way out, after an unintended insert. This is
#     the case the old sentinel turned into a delta of zero.
world = []
fires("a witness that fails only after the write aborts instead of reporting delta 0",
      lambda w=world: one_policy_case(scripted_api((200, []), (500, {"message": "boom"})), w),
      "stored issues")
ok = world == ["INSERT"]
RESULTS.append((ok, "...and the unintended insert had in fact happened", repr(world)))
print(("PASS " if ok else "FAIL ") + "...and the unintended insert had in fact happened")

# (c) with a working witness on both sides the same insert is visible, which is
#     what makes the refusal check meaningful at all.
world = []
kind, payload, delta = one_policy_case(scripted_api((200, []), (200, [{"number": 1}])), world)
ok = (delta == 1 and world == ["INSERT"] and kind == "error")
RESULTS.append((ok, "a working witness reports the unintended insert as delta 1", repr((kind, delta, world))))
print(("PASS " if ok else "FAIL ") + "a working witness reports the unintended insert as delta 1")
if not ok:
    print(f"        got kind={kind!r} delta={delta!r} world={world!r}, want ('error', 1, ['INSERT'])")

print("\n## 9. a lawful refusal is still reported as having written nothing")
world = []
def refusing_tool_that_writes_nothing():
    return "error", {"code": -2, "message": "may not open an issue"}
allows("a genuine refusal with a working witness gives delta 0",
       lambda: observe_issue_write(
           (lambda seq=iter([(200, [{"number": 1}]), (200, [{"number": 1}])]): (lambda m, p, b=None, a=None: next(seq)))(),
           refusing_tool_that_writes_nothing, "owner1", "priv-private")[2], 0)
allows("a genuine allow with a working witness gives delta 1",
       lambda: observe_issue_write(
           (lambda seq=iter([(200, []), (200, [{"number": 1}])]): (lambda m, p, b=None, a=None: next(seq)))(),
           lambda: ("ok", {"number": 1}), "owner1", "pub-all")[2], 1)
fires("a count that falls across one operation aborts rather than reporting a negative delta",
      lambda: observe_issue_write(
          (lambda seq=iter([(200, [{"number": 1}, {"number": 2}]), (200, [])]): (lambda m, p, b=None, a=None: next(seq)))(),
          refusing_tool_that_writes_nothing, "owner1", "priv-private"),
      "fell from 2 to 0")

# ── 10. a server failure is not an authorization refusal ──────────────────
#
# The resource gate asserted `status >= 400` at its two refresh sites, so an
# HTTP 500 counted as a denial and the gate reported 15/15 and exit 0 while
# two of its negative checks had observed nothing at all. These drive the same
# helper those call sites now use.
print("\n## 10. a server failure is not an authorization refusal")
from resource_gate import refused_grant, TOKEN_REFUSAL_STATUSES, REFRESH_REFUSAL

for status in (500, 502, 503):
    fires(f"HTTP {status} is not a refusal, whatever the body says",
          lambda st=status: refused_grant(st, {"error": "invalid_grant"}, "control"),
          "is not a refusal")

fires("a missing token endpoint is not a refusal",
      lambda: refused_grant(404, {"error": "invalid_grant"}, "control"),
      "is not a refusal")
fires("a 200 is certainly not a refusal",
      lambda: refused_grant(200, {"access_token": "t"}, "control"),
      "is not a refusal")
fires("a refusal status carrying no object is not a refusal",
      lambda: refused_grant(400, "server error", "control"),
      "not an OAuth error object")
fires("a refusal status carrying nothing is not a refusal",
      lambda: refused_grant(400, None, "control"),
      "not an OAuth error object")
fires("a documented status with an undocumented error code is not a refusal",
      lambda: refused_grant(400, {"error": "server_error"}, "control"),
      "not one of the documented")
fires("a documented status with no error code is not a refusal",
      lambda: refused_grant(400, {"error_description": "nope"}, "control"),
      "not one of the documented")

# The positive twins: the guard has to accept what the server really answers,
# or it would merely be strict rather than correct.
allows("a 400 invalid_grant is the documented refusal",
       lambda: refused_grant(400, {"error": "invalid_grant"}, "control"), (400, "invalid_grant"))
allows("a 400 invalid_target is the documented refusal on the code path",
       lambda: refused_grant(400, {"error": "invalid_target"}, "control",
                             expected=(400, "invalid_target")), (400, "invalid_target"))
allows("a 401 invalid_client is a refusal of the client itself, where that is expected",
       lambda: refused_grant(401, {"error": "invalid_client"}, "control",
                             expected=(401, "invalid_client")), (401, "invalid_client"))
allows("extra fields alongside a documented error are still a refusal",
       lambda: refused_grant(400, {"error": "invalid_grant", "error_description": "x"}, "control"),
       (400, "invalid_grant"))

# A refusal that is not *this* scenario's refusal. Each of these is a lawful,
# documented OAuth error -- and none of them says the resource binding was
# examined, which is the only thing these two scenarios exist to establish.
for wrong_status, wrong_error in [
        (401, "invalid_client"), (400, "invalid_client"),
        (400, "invalid_request"), (400, "invalid_target"),
        (400, "unauthorized_client"), (400, "invalid_scope"),
        (400, "unsupported_grant_type")]:
    fires(f"a refresh scenario does not accept {wrong_status} {wrong_error}",
          lambda st=wrong_status, er=wrong_error:
              refused_grant(st, {"error": er}, "control"),
          "not the one this scenario establishes")

allows("and the pair it is entitled to still passes",
       lambda: refused_grant(400, {"error": "invalid_grant"}, "control"), REFRESH_REFUSAL)
allows("a scenario may name a different expected pair explicitly",
       lambda: refused_grant(400, {"error": "invalid_target"}, "control",
                             expected=(400, "invalid_target")), (400, "invalid_target"))

bad = [r for r in RESULTS if not r[0]]
print()
print(f"=== GATE SELF-TEST: {len(RESULTS)-len(bad)} passed, {len(bad)} failed ===")
for r in bad:
    print("   FAILED:", r[1], "|", r[2])
sys.exit(1 if bad else 0)
