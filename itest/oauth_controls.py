"""
The OAuth controls, run against a disposable GitBucket over HTTP.

    python3 itest/oauth_controls.py

Nothing here calls the plugin's internals. Each control performs the flow the
way a client would and then reads GitBucket's own API to see what the server
is storing, so a plugin that answered well and did the wrong thing would fail.
"""
import json
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb import api, Web
from harness import check, summary, verdict
from checks import GateError, require
import fixtures
import observe
from oauth_gate import (OBASE, RESOURCE, REDIRECT, ClientHost, pkce, http, as_json,
                        signin, consent_screen, decide, authorize, query_of,
                        token, redeem, refresh, revoke, tool, outcome)

ROOT = ("root", "root")
OW = ("owner1", "Test-pass-1")


def issues(repo, owner="owner1"):
    return observe.stored_issue_count(api, owner, repo)


def error_of(payload):
    return payload.get("error") if isinstance(payload, dict) else None


def rpc_error_message(body):
    err = body.get("error") if isinstance(body, dict) else None
    return (err or {}).get("message", "") if isinstance(err, dict) else ""


def run():
    host = ClientHost()
    try:
        controls(host)
    finally:
        host.stop()
    return summary("OAuth HTTP controls")


def controls(host):
    client = host.publish("/client.json", [REDIRECT])
    other = host.publish("/other.json", [REDIRECT], name="Second Client")

    # ── A. discovery, anonymous ──────────────────────────────────────────────
    st, _, body = http("GET", f"{OBASE}/.well-known/oauth-protected-resource")
    pr = as_json(body)
    check("A1 protected-resource metadata is anonymous", st, 200)
    check("A1 names this resource", pr.get("resource"), RESOURCE)
    check("A1 names its authorization server", pr.get("authorization_servers"), [OBASE])

    st, _, body = http("GET", f"{OBASE}/.well-known/oauth-authorization-server")
    asm = as_json(body)
    check("A2 authorization-server metadata is anonymous", st, 200)
    check("A2 issuer", asm.get("issuer"), OBASE)
    check("A2 S256 only", asm.get("code_challenge_methods_supported"), ["S256"])
    check("A2 scopes", sorted(asm.get("scopes_supported") or []), ["mcp:read", "mcp:write"])
    check("A2 CIMD advertised", asm.get("client_id_metadata_document_supported"), True)

    st, headers, _ = http("POST", f"{OBASE}/plugin-mcp/",
                          json_body={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    check("A3 the MCP endpoint refuses an unauthenticated call", st, 401)
    check("A3 and points at its metadata",
          "resource_metadata=" in headers.get("WWW-Authenticate", ""), True)

    # ── B. consent ───────────────────────────────────────────────────────────
    verifier, challenge = pkce()
    st, hdrs, _ = http(
        "GET", f"{OBASE}/plugin-mcp/oauth/authorize?" + urllib.parse.urlencode(
            {"response_type": "code", "client_id": client, "redirect_uri": REDIRECT,
             "scope": "mcp:read", "code_challenge": challenge,
             "code_challenge_method": "S256"}))
    location = hdrs.get("Location", "")
    check("B1 an unauthenticated authorize goes to GitBucket's own sign-in",
          (st, urllib.parse.urlparse(location).path), (302, "/signin"))

    jar = signin(*OW)
    st, _, html = consent_screen(jar, client, REDIRECT, "mcp:read mcp:write", challenge)
    check("B2 the consent screen renders", st, 200)
    check("B2 names the signed-in account", "owner1" in html, True)
    check("B2 names the client", "Integration Test Client" in html, True)
    check("B2 lists the requested scopes", "mcp:write" in html, True)

    st, location, _ = decide(jar, html, decision="deny")
    q = query_of(location)
    check("B3 denial redirects with access_denied",
          (st, q.get("error"), q.get("iss"), q.get("state")),
          (302, ["access_denied"], [OBASE], ["st-1"]))
    check("B3 and issues no code", "code" in q, False)

    st, _, html = consent_screen(jar, client, REDIRECT, "mcp:read", challenge)
    require(st == 200, "the consent screen did not render for the CSRF control")
    st, location, body = decide(jar, html, csrf="not-the-token")
    check("B4 a wrong CSRF token is refused", st, 400)
    check("B4 with no redirect", location, "")
    st, location2, body2 = decide(jar, html)          # resubmit, correct token
    check("B5 and the pending authorization is gone afterwards",
          (st, "no authorization in progress" in body2), (400, True))

    verifier, challenge = pkce()
    st, _, html = consent_screen(jar, client, REDIRECT, "mcp:read mcp:write", challenge)
    st, location, _ = decide(jar, html)
    q = query_of(location)
    check("B6 approval redirects with a code, iss and state",
          (st, len(q.get("code", [])), q.get("iss"), q.get("state")),
          (302, 1, [OBASE], ["st-1"]))
    code = q["code"][0]

    # ── C. code binding, redemption and replay ───────────────────────────────
    st, b = redeem(code, other, REDIRECT, verifier)
    check("C1 a wrong client_id is refused", (st, error_of(b)), (400, "invalid_grant"))
    st, b = redeem(code, client, "https://client.example.com/elsewhere", verifier)
    check("C2 a wrong redirect_uri is refused", (st, error_of(b)), (400, "invalid_grant"))
    st, b = redeem(code, client, REDIRECT, verifier, resource="https://example.com/other/")
    check("C3 a wrong resource is refused", (st, error_of(b)), (400, "invalid_target"))
    st, b = redeem(code, client, REDIRECT, pkce()[0])
    check("C4 a wrong code_verifier is refused", (st, error_of(b)), (400, "invalid_grant"))

    st, granted = redeem(code, client, REDIRECT, verifier)
    check("C5 none of those four consumed the code", st, 200)
    check("C5 the grant carries the consented scopes", granted.get("scope"), "mcp:read mcp:write")
    access, refresh_token = granted.get("access_token"), granted.get("refresh_token")
    require(access and refresh_token, "the token response carried no credentials")

    st, _, body = tool(access, "whoami", {})
    kind, payload = outcome(body)
    check("C6 the access token acts as the consenting account",
          (st, kind, payload.get("login")), (200, "ok", "owner1"))

    # A spent code is not a credential, and possessing one is not evidence.
    # Revoking on replay is defensible only for a presentation that could have
    # come from the legitimate client; it used to fire for any of these, so
    # anyone holding a used code could end a live session.
    for label, args, kw, expected in (
            ("client", (code, other, REDIRECT, verifier), {}, "invalid_grant"),
            ("redirect", (code, client, "https://client.example.com/elsewhere", verifier), {},
             "invalid_grant"),
            ("resource", (code, client, REDIRECT, verifier),
             {"resource": "https://elsewhere.example.com/mcp/"}, "invalid_target"),
            ("verifier", (code, client, REDIRECT, pkce()[0]), {}, "invalid_grant")):
        st, b = redeem(*args, **kw)
        check(f"C7 a wrong {label} on a spent code is refused",
              (st, error_of(b)), (400, expected))
    st, _, body = tool(access, "whoami", {})
    kind, payload = outcome(body)
    check("C7 and none of those four revoked the grant: the token still works",
          (st, kind, payload.get("login")), (200, "ok", "owner1"))

    st, b = redeem(code, client, REDIRECT, verifier)
    check("C8 a correctly bound replay is refused", (st, error_of(b)), (400, "invalid_grant"))
    st, _, body = tool(access, "whoami", {})
    check("C8 and its revocation is committed: the issued token stops working",
          (st, "access token" in rpc_error_message(body)), (401, True))

    # ── D. bearer credentials ────────────────────────────────────────────────
    verifier, challenge = pkce()
    code, _ = authorize(jar, client, REDIRECT, "mcp:read mcp:write", challenge)
    st, granted = redeem(code, client, REDIRECT, verifier)
    require(st == 200, "could not obtain a working grant for the bearer controls")
    access, refresh_token = granted["access_token"], granted["refresh_token"]

    st, headers, body = tool("not-a-token", "whoami", {})
    check("D1 an unknown bearer token is refused", st, 401)
    check("D1 with an invalid_token challenge",
          "error=\"invalid_token\"" in headers.get("WWW-Authenticate", ""), True)

    st, _, body = http("POST", f"{OBASE}/plugin-mcp/",
                       headers={"Authorization": "Bearer " + access + "x"},
                       json_body={"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                  "params": {"name": "whoami", "arguments": {}}},
                       jar=jar)
    check("D2 a bad bearer token does not fall back to the browser session", st, 401)

    st, _, body = http(
        "POST", f"{OBASE}/plugin-mcp/",
        headers={"Authorization": "Bearer " + access,
                 "X-Forwarded-Authorization": "Basic cm9vdDpyb290"},
        json_body={"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                   "params": {"name": "whoami", "arguments": {}}})
    kind, payload = outcome(as_json(body))
    check("D3 a bearer caller cannot be re-identified by another header",
          payload.get("login") if kind == "ok" else kind, "owner1")

    st, body = revoke(access)
    check("D4 revocation answers 200", st, 200)
    st, _, body = tool(access, "whoami", {})
    check("D4 and the revoked token stops working at the next check", st, 401)
    st, b = refresh(refresh_token, client)
    check("D5 revoking the access token revokes its grant",
          (st, error_of(b)), (400, "invalid_grant"))

    # ── E. scope enforcement, in the real dispatcher ─────────────────────────
    admin = signin(*ROOT)
    verifier, challenge = pkce()
    code, _ = authorize(admin, client, REDIRECT, "mcp:read", challenge)
    st, granted = redeem(code, client, REDIRECT, verifier)
    require(st == 200, "the administrator's read-only grant did not issue")
    check("E1 a read-only consent issues a read-only grant", granted.get("scope"), "mcp:read")
    read_only = granted["access_token"]

    st, _, body = tool(read_only, "whoami", {})
    kind, payload = outcome(body)
    check("E2 the caller is the GitBucket administrator",
          (kind, payload.get("login"), payload.get("is_admin")), ("ok", "root", True))

    before = issues("pub-all")
    st, _, body = tool(read_only, "create_issue",
                       {"owner": "owner1", "repo": "pub-all", "title": "read-only must not write"})
    check("E3 read-only consent refuses a write tool to an administrator", st, 403)
    check("E3 named as a scope failure", "insufficient_scope" in rpc_error_message(body), True)
    check("E3 and nothing was stored", issues("pub-all"), before)

    st, _, body = tool(read_only, "list_issues", {"owner": "owner1", "repo": "pub-all"})
    check("E4 the same grant still reads", outcome(body)[0], "ok")

    # Adequate scope does not override GitBucket's own permission checks.
    out = signin("outsider", "Test-pass-4")
    verifier, challenge = pkce()
    code, _ = authorize(out, client, REDIRECT, "mcp:read mcp:write", challenge)
    st, granted = redeem(code, client, REDIRECT, verifier)
    require(st == 200, "the outsider's grant did not issue")
    outsider = granted["access_token"]

    before = issues("priv-private")
    st, _, body = tool(outsider, "create_issue",
                       {"owner": "owner1", "repo": "priv-private", "title": "no access"})
    kind, payload = outcome(body)
    check("E5 write scope does not grant repository access",
          verdict(kind, payload), "not-found")
    check("E5 and nothing was stored", issues("priv-private"), before)

    before = issues("pub-disable")
    st, _, body = tool(outsider, "create_issue",
                       {"owner": "owner1", "repo": "pub-disable", "title": "issues are off"})
    kind, payload = outcome(body)
    check("E6 write scope does not override the repository's issue setting",
          verdict(kind, payload), "forbidden")
    check("E6 and nothing was stored", issues("pub-disable"), before)

    # A properly scoped, properly permitted write does land.
    verifier, challenge = pkce()
    code, _ = authorize(jar, client, REDIRECT, "mcp:read mcp:write", challenge)
    st, granted = redeem(code, client, REDIRECT, verifier)
    require(st == 200, "the owner's write grant did not issue")
    writer, writer_refresh = granted["access_token"], granted["refresh_token"]

    before = issues("pub-all")
    st, _, body = tool(writer, "create_issue",
                       {"owner": "owner1", "repo": "pub-all", "title": "an authorized write"})
    kind, payload = outcome(body)
    check("E7 an authorized write through an OAuth caller succeeds", kind, "ok")
    check("E7 and GitBucket stored it", issues("pub-all"), before + 1)
    st, created = api("GET", f"/api/v3/repos/owner1/pub-all/issues/{payload.get('number')}")
    check("E7 attributed to the consenting account",
          (st, created.get("user", {}).get("login")), (200, "owner1"))

    # ── F. refresh rotation ──────────────────────────────────────────────────
    st, b = refresh(writer_refresh, other)
    check("F1 a refresh bound to another client is refused",
          (st, error_of(b)), (400, "invalid_grant"))
    st, rotated = refresh(writer_refresh, client)
    check("F2 the binding failure did not consume the refresh token", st, 200)
    check("F2 rotation returns a new refresh token",
          rotated.get("refresh_token") != writer_refresh, True)
    check("F2 and preserves the grant's scopes", rotated.get("scope"), "mcp:read mcp:write")
    successor, successor_access = rotated["refresh_token"], rotated["access_token"]

    st, _, body = tool(successor_access, "whoami", {})
    check("F3 the rotated access token works", outcome(body)[0], "ok")

    st, b = refresh(writer_refresh, client)
    check("F4 replaying the rotated token is refused", (st, error_of(b)), (400, "invalid_grant"))
    st, b = refresh(successor, client)
    check("F5 the replay revoked the family: the successor is dead too",
          (st, error_of(b)), (400, "invalid_grant"))
    st, _, body = tool(successor_access, "whoami", {})
    check("F6 and an access token from the revoked grant is refused at the next check",
          st, 401)

    # ── G. account lifecycle and role changes ────────────────────────────────
    dev = signin("devuser", "Test-pass-2")
    verifier, challenge = pkce()
    code, _ = authorize(dev, client, REDIRECT, "mcp:read mcp:write", challenge)
    st, granted = redeem(code, client, REDIRECT, verifier)
    require(st == 200, "devuser's grant did not issue")
    dev_token = granted["access_token"]

    before = issues("pub-public")
    st, _, body = tool(dev_token, "create_issue",
                       {"owner": "owner1", "repo": "pub-public", "title": "developer write"})
    check("G1 a developer may write", outcome(body)[0], "ok")
    check("G1 and it was stored", issues("pub-public"), before + 1)

    st, b = api("DELETE", "/api/v3/repos/owner1/priv-all/collaborators/devuser", auth=OW)
    require(st in (200, 204), f"could not remove devuser's role: {st} {b}")
    st, b = api("PUT", "/api/v3/repos/owner1/priv-all/collaborators/devuser",
                {"permission": "pull"}, auth=OW)
    require(st in (200, 201, 204), f"could not demote devuser: {st} {b}")
    st, cols = api("GET", "/api/v3/repos/owner1/priv-all/collaborators", auth=OW)
    require(any(c["login"] == "devuser" for c in cols),
            "devuser is no longer a collaborator, so the control would pass for the wrong reason")
    before = issues("priv-all")
    st, _, body = tool(dev_token, "create_issue",
                       {"owner": "owner1", "repo": "priv-all", "title": "after demotion"})
    kind, payload = outcome(body)
    check("G2 a role change takes effect for an existing token",
          verdict(kind, payload), "forbidden")
    check("G2 and nothing was stored", issues("priv-all"), before)

    disable_account("devuser")
    st, _, body = tool(dev_token, "whoami", {})
    check("G3 a removed account's outstanding token stops working", st, 401)

    # ── H. the production client-metadata fetch path ─────────────────────────
    jar2 = signin(*OW)
    _, challenge = pkce()

    st, _, body = consent_screen(jar2, "http://10.0.0.17/client.json", REDIRECT,
                                 "mcp:read", challenge)
    check("H1 a plain-http client id on a private address is refused",
          (st, "https" in body), (400, True))

    st, _, body = consent_screen(jar2, "https://10.0.0.17/client.json", REDIRECT,
                                 "mcp:read", challenge)
    check("H2 an https client id on a private address is refused",
          (st, "non-public address" in body), (400, True))

    st, _, body = consent_screen(jar2, "https://169.254.169.254/client.json", REDIRECT,
                                 "mcp:read", challenge)
    check("H3 the link-local metadata address is refused",
          (st, "non-public address" in body), (400, True))

    moved = host.publish_redirect("/moved.json", host.url("/client.json"))
    st, _, body = consent_screen(jar2, moved, REDIRECT, "mcp:read", challenge)
    check("H4 the production fetch refuses a redirecting metadata document",
          (st, "must not redirect" in body), (400, True))

    missing = host.url("/absent.json")
    st, _, body = consent_screen(jar2, missing, REDIRECT, "mcp:read", challenge)
    check("H5 a missing metadata document is unreachable, not a client",
          (st, "returned HTTP 404" in body), (400, True))

    lying = host.publish("/lying.json", [REDIRECT], declared="https://elsewhere.example.com/c")
    st, _, body = consent_screen(jar2, lying, REDIRECT, "mcp:read", challenge)
    check("H6 a document declaring another client_id is refused",
          (st, "declares a different client_id" in body), (400, True))

    narrow = host.publish("/narrow.json", ["https://client.example.com/only-here"])
    st, _, body = consent_screen(jar2, narrow, REDIRECT, "mcp:read", challenge)
    check("H7 an unregistered redirect_uri is refused before any consent",
          (st, "not registered" in body), (400, True))


def disable_account(login):
    """Mark an account removed through GitBucket's own administration form."""
    web = Web()
    st, _, cookies = web.signin("root", "root")
    require(st == 200 and cookies, "root could not sign in to disable an account")
    st, body = web.post(f"/admin/users/{login}/_edituser",
                        {"userName": login, "fullName": login,
                         "mailAddress": f"{login}@example.com",
                         "description": "", "url": "", "removed": "true"})
    require(st in (200, 302), f"could not disable {login}: {st} {body}")
    st, account = api("GET", f"/api/v3/users/{login}")
    require(st == 404 or (isinstance(account, dict) and account.get("type") == "removed"),
            f"{login} does not read back as removed: {st} {account}")


if __name__ == "__main__":
    fixtures.build()
    try:
        failures = run()
    finally:
        fixtures.cleanup()
    sys.exit(1 if failures else 0)
