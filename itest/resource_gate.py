"""
Audience binding, observed across a change of configured resource.

    python3 itest/resource_gate.py --war <gitbucket.war> --candidate <plugin.jar> --home <empty dir>

A token says which resource it was issued for, and the store records it. That
is only a fact in a table until something compares it with the resource this
server actually is. The comparison has to happen at the point of use, because
the token outlives the request that created it: a deployment that is
reconfigured, or a token minted for a neighbouring deployment, both arrive
here as a perfectly valid, unexpired credential for somewhere else.

So this gate does the one thing a single-instance test cannot: it issues a
grant while the server calls itself A, restarts the same server as B, and
presents the credential again. Nothing is revoked in between and no clock
moves; the only thing that changed is which resource the server is.

The two names are the same machine. That is deliberate — it isolates the
audience check from everything else that might refuse a request.
"""
import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import oauth_gate
import upgrade_gate
from checks import GateError, require
from harness import check, summary

PORT = 8099
NAME_A = "localhost"
NAME_B = "127.0.0.1"
ADMIN = ("root", "root")
REPO = "audience"


def base_url(name):
    return f"http://{name}:{PORT}"


def resource_of(name):
    return f"{base_url(name)}/plugin-mcp/"


def api(method, path, body=None, creds=ADMIN):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Basic " +
                   base64.b64encode(f"{creds[0]}:{creds[1]}".encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode(errors="replace")
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw[:300]


def issue_count():
    status, body = api("GET", f"/api/v3/repos/root/{REPO}/issues?state=all")
    require(status == 200 and isinstance(body, list),
            f"cannot read the stored issues ({status}); this gate has no witness")
    return len(body)


def authorize_under(name, client_id, scope="mcp:read mcp:write"):
    """A full consent walk against the server as it is currently configured."""
    oauth_gate.OBASE = base_url(name)
    verifier, challenge = oauth_gate.pkce()
    jar = oauth_gate.signin(*ADMIN)
    code, _ = oauth_gate.authorize(jar, client_id, oauth_gate.REDIRECT, scope, challenge)
    status, payload = oauth_gate.redeem(code, client_id, oauth_gate.REDIRECT, verifier,
                                        resource=resource_of(name))
    require(status == 200 and "access_token" in payload,
            f"the token endpoint answered {status}: {payload}")
    return payload


# The documented refusals. RFC 6749 §5.2 gives the token endpoint one shape
# for a rejected grant: HTTP 400 with an `error` code, and 401 when the client
# itself was not accepted. Anything else — a 500, a 404 from a missing route,
# a body that is not the documented object — is the server failing to answer,
# and a failure to answer is not a refusal. Accepting `status >= 400` as proof
# of authorization meant a crashed endpoint read as a correct denial.
TOKEN_REFUSAL_STATUSES = (400, 401)
TOKEN_REFUSAL_ERRORS = {
    "invalid_request", "invalid_client", "invalid_grant",
    "unauthorized_client", "unsupported_grant_type", "invalid_scope",
    "invalid_target",
}


# What each refresh scenario is entitled to see. `OAuthEndpoints.refreshFailure`
# answers `400 invalid_grant` for every rotation failure, deliberately, so a
# refusal cannot disclose which binding was wrong. That uniformity is a
# production property and stays; what it means for this gate is that the only
# response proving the resource binding was consulted is that exact pair.
REFRESH_REFUSAL = (400, "invalid_grant")


def refused_grant(status, payload, what, expected=REFRESH_REFUSAL):
    """
    Assert the refusal this scenario is entitled to, or abort.

    Checking only that the status was >= 400, or only that it was some
    documented refusal, is not enough. An `invalid_client` says the client was
    not accepted; an `invalid_request` says the request was malformed. Neither
    is evidence that the resource binding was examined, and a scenario that
    accepts them reports a restriction it never observed.
    """
    want_status, want_error = expected
    if status not in TOKEN_REFUSAL_STATUSES:
        raise GateError(
            f"{what}: expected a token-endpoint refusal {TOKEN_REFUSAL_STATUSES}, got "
            f"HTTP {status} with {payload!r}. A server failure is not a refusal and "
            f"this gate cannot read authorization from it.")
    if not isinstance(payload, dict):
        raise GateError(
            f"{what}: HTTP {status} carried {payload!r}, not an OAuth error object.")
    error = payload.get("error")
    if error not in TOKEN_REFUSAL_ERRORS:
        raise GateError(
            f"{what}: HTTP {status} carried error={error!r}, which is not one of the "
            f"documented token-endpoint error codes.")
    if (status, error) != (want_status, want_error):
        raise GateError(
            f"{what}: expected {want_status} {want_error!r}, got {status} {error!r}. "
            f"That is a refusal, but not the one this scenario establishes, so it is "
            f"no evidence that the resource binding was checked.")
    return (status, error)


def tool_call(name, token, tool, arguments):
    oauth_gate.OBASE = base_url(name)
    status, headers, payload = oauth_gate.tool(token, tool, arguments)
    return status, headers, oauth_gate.outcome(payload)


def run(war, candidate, home):
    host = oauth_gate.ClientHost()
    started = []

    def boot(name):
        gb = upgrade_gate.Instance(war, home)
        started.append(gb)
        # Instance builds its command from upgrade_gate.OBASE.
        upgrade_gate.OBASE = base_url(name)
        gb.start()
        return gb

    try:
        client_id = host.publish("/client.json", [oauth_gate.REDIRECT], name="Audience gate")

        # ── A: the server calls itself localhost ──────────────────────────
        print(f"## 1. issued while the resource is {resource_of(NAME_A)}")
        gb = upgrade_gate.Instance(war, home)
        started.append(gb)
        gb.install(candidate, "gitbucket-mcp-plugin-candidate.jar")
        upgrade_gate.OBASE = base_url(NAME_A)
        gb.start()

        status, _ = api("POST", "/api/v3/user/repos",
                        {"name": REPO, "description": "audience fixture",
                         "private": False, "auto_init": True})
        require(status in (200, 201), f"could not create the fixture repository ({status})")

        grant = authorize_under(NAME_A, client_id)
        access, refresh = grant["access_token"], grant.get("refresh_token")
        require(refresh, "the grant carries no refresh token; the gate needs one")

        st, _, (kind, _) = tool_call(NAME_A, access, "whoami", {})
        check("the token works for the resource it was issued for", (st, kind), (200, "ok"))
        before = issue_count()
        st, _, (kind, _) = tool_call(NAME_A, access, "create_issue",
                                     {"owner": "root", "repo": REPO, "title": "under A"})
        check("and a write it was consented for succeeds", kind, "ok")
        check("which the repository actually stored", issue_count(), before + 1)
        gb.stop()

        # ── B: the same server, a different configured resource ───────────
        print(f"\n## 2. the same server restarted as {resource_of(NAME_B)}")
        gb = boot(NAME_B)
        check("the server is up under the new resource",
              api("GET", "/api/v3/repos/root/" + REPO)[0], 200)
        check("its metadata now names the new resource",
              json.loads(urllib.request.urlopen(
                  f"http://127.0.0.1:{PORT}/.well-known/oauth-protected-resource",
                  timeout=10).read().decode()).get("resource"),
              resource_of(NAME_B))

        witness = issue_count()
        st, headers, (kind, payload) = tool_call(NAME_B, access, "whoami", {})
        check("a valid, unexpired token for another resource is refused", kind, "error")
        check("refused as an authentication failure, not a server error", st, 401)
        check("and the challenge names an invalid token",
              "invalid_token" in headers.get("WWW-Authenticate", ""), True)

        st, _, (kind, _) = tool_call(NAME_B, access, "create_issue",
                                     {"owner": "root", "repo": REPO, "title": "must not exist"})
        check("a write with that token is refused too", kind, "error")
        check("and nothing was stored", issue_count(), witness)

        st, payload = oauth_gate.token(grant_type="refresh_token", refresh_token=refresh,
                                       client_id=client_id)
        check("its refresh token is refused under the other resource",
              refused_grant(st, payload, "refresh under the other resource"),
              REFRESH_REFUSAL)
        gb.stop()

        # ── A again: nothing was destroyed by being refused ───────────────
        print(f"\n## 3. restored to {resource_of(NAME_A)}")
        gb = boot(NAME_A)
        st, _, (kind, _) = tool_call(NAME_A, access, "whoami", {})
        check("the token works again once the resource matches", (st, kind), (200, "ok"))

        oauth_gate.OBASE = base_url(NAME_A)
        st, payload = oauth_gate.token(grant_type="refresh_token", refresh_token=refresh,
                                       client_id=client_id, resource="https://elsewhere.example.com/mcp/")
        check("a refresh naming the wrong resource is refused",
              refused_grant(st, payload, "refresh naming the wrong resource"),
              REFRESH_REFUSAL)
        st, payload = oauth_gate.token(grant_type="refresh_token", refresh_token=refresh,
                                       client_id=client_id, resource=resource_of(NAME_A))
        check("and the lawful refresh still works, so it was not consumed",
              st == 200 and "access_token" in payload, True)
        rotated = payload.get("access_token")
        st, _, (kind, _) = tool_call(NAME_A, rotated, "whoami", {})
        check("the rotated token works", (st, kind), (200, "ok"))
        gb.stop()
    finally:
        host.stop()
        for gb in started:
            try:
                gb.stop()
            except Exception:
                pass

    failed = summary("RESOURCE BINDING GATE")
    ran = len(__import__("harness").RESULTS)
    if ran < 15:
        raise GateError(f"only {ran} checks executed; this gate has no result below 15")
    return 1 if failed else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--war", required=True)
    ap.add_argument("--candidate", required=True)
    ap.add_argument("--home", required=True)
    a = ap.parse_args()
    for p in (a.war, a.candidate):
        require(os.path.isfile(p), f"not a readable file: {p}")
    require(not os.path.exists(a.home) or not os.listdir(a.home),
            f"{a.home} is not empty; this gate needs a blank GITBUCKET_HOME")
    os.makedirs(a.home, exist_ok=True)
    try:
        sys.exit(run(a.war, a.candidate, a.home))
    except GateError as exc:
        print(f"\nGATE ABORTED: {exc}", file=sys.stderr)
        print("No result may be read from this run.", file=sys.stderr)
        sys.exit(2)
