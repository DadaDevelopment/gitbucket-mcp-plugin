"""
The OAuth flow, driven through the production HTTP endpoints.

Every control here goes over the wire to a running GitBucket with the plugin
installed: discovery, the consent screen, the token endpoint, Bearer
authentication and the real MCP tool dispatcher. Nothing is reimplemented,
and the independent observations go to GitBucket's own REST API rather than
to the plugin's answers.

Committed effects are observed the way they matter: by asking again. After a
refusal that must change nothing, the lawful operation is performed next and
has to succeed; after a replay that must revoke, a fresh authorization check
has to refuse. That is a stronger reading than inspecting the table, because
it is the property the server actually has to provide, and it is taken on a
connection the first request did not use.

The instance under test runs with `gitbucket.mcp.oauth.allowLoopbackClients`
on, which is the only way a client metadata document can be served to it from
this machine. The refusal cases below exercise the fence in its default state
as well, through client ids the escape does not cover.

Note the two host names. The consent screen's form action is built from the
configured base URL, so the browser session must live on that host; the REST
control calls use the address `gb` was configured with. They are the same
server, and keeping them apart is what makes the cookie jar behave.
"""
import base64
import hashlib
from http import cookiejar as _cookiejar
from http import server as _httpserver
import json
import os
import re
import secrets
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gb import api, BASE
from mcp import ACCOUNTS
from harness import check, summary, verdict
from checks import GateError, require
import fixtures
import observe

OBASE = "http://localhost:8099"      # must equal the configured base URL
RESOURCE = f"{OBASE}/plugin-mcp/"
REDIRECT = "https://client.example.com/connector/oauth/cb"


# ── a client metadata document the server can fetch ─────────────────────────

class _Handler(_httpserver.BaseHTTPRequestHandler):
    documents = {}

    def do_GET(self):
        entry = self.documents.get(self.path)
        if entry is None:
            self.send_response(404)
            self.end_headers()
            return
        if isinstance(entry, str):                      # a redirect target
            self.send_response(302)
            self.send_header("Location", entry)
            self.end_headers()
            return
        body = json.dumps(entry).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class ClientHost:
    def __init__(self):
        self.server = _httpserver.HTTPServer(("127.0.0.1", 0), _Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def url(self, path):
        return f"http://127.0.0.1:{self.port}{path}"

    def publish(self, path, redirect_uris, name="Integration Test Client", declared=None):
        url = self.url(path)
        _Handler.documents[path] = {
            "client_id": declared if declared is not None else url,
            "client_name": name,
            "redirect_uris": list(redirect_uris)}
        return url

    def publish_redirect(self, path, to):
        _Handler.documents[path] = to
        return self.url(path)

    def stop(self):
        self.server.shutdown()


def pkce():
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).decode().rstrip("=")
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()).decode().rstrip("=")
    return verifier, challenge


# ── raw HTTP, so responses are inspected rather than followed ───────────────

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **kw):
        return None


def http(method, url, data=None, headers=None, jar=None, json_body=None):
    body = None
    hdrs = dict(headers or {})
    if json_body is not None:
        body = json.dumps(json_body).encode()
        hdrs["Content-Type"] = "application/json"
    elif data is not None:
        body = urllib.parse.urlencode(data).encode()
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=body, method=method)
    for k, v in hdrs.items():
        req.add_header(k, v)
    handlers = [_NoRedirect()]
    if jar is not None:
        handlers.append(urllib.request.HTTPCookieProcessor(jar))
    opener = urllib.request.build_opener(*handlers)
    try:
        with opener.open(req) as r:
            return r.status, dict(r.headers), r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode(errors="replace")
    except urllib.error.URLError as e:
        raise GateError(f"{method} {url}: {e}")


def as_json(body):
    try:
        return json.loads(body)
    except Exception:
        return {"raw": body[:300]}


def signin(login, password):
    """A real browser session on the configured host."""
    jar = _cookiejar.CookieJar()
    status, _, _ = http("POST", f"{OBASE}/signin",
                        data={"userName": login, "password": password}, jar=jar)
    require(status in (200, 302) and len(jar) > 0, f"{login} could not sign in ({status})")
    return jar


# ── the flow ────────────────────────────────────────────────────────────────

def consent_screen(jar, client_id, redirect_uri, scope, challenge, state="st-1",
                   resource=RESOURCE, method="S256", response_type="code"):
    query = {"response_type": response_type, "client_id": client_id,
             "redirect_uri": redirect_uri, "scope": scope,
             "code_challenge": challenge, "code_challenge_method": method,
             "state": state}
    if resource:
        query["resource"] = resource
    status, headers, body = http(
        "GET", f"{OBASE}/plugin-mcp/oauth/authorize?" + urllib.parse.urlencode(query),
        jar=jar)
    return status, headers.get("Location", ""), body


def decide(jar, html, decision="allow", csrf=None):
    action = re.search(r'<form method="post" action="([^"]+)"', html)
    require(action is not None, "the consent screen has no form action")
    token = re.search(r'name="csrf_token" value="([^"]*)"', html)
    require(token is not None, "the consent screen has no CSRF token")
    form = {"decision": decision,
            "csrf_token": token.group(1) if csrf is None else csrf}
    status, headers, body = http("POST", action.group(1).replace("&amp;", "&"),
                                 data=form, jar=jar)
    return status, headers.get("Location", ""), body


def authorize(jar, client_id, redirect_uri, scope, challenge, **kw):
    """Walk the real consent screen and return the authorization code."""
    status, _, html = consent_screen(jar, client_id, redirect_uri, scope, challenge, **kw)
    require(status == 200, f"the consent screen answered {status}")
    status, location, body = decide(jar, html)
    require(status in (301, 302), f"the decision answered {status}, not a redirect")
    code = query_of(location).get("code", [None])[0]
    require(code is not None, f"no code in {location}")
    return code, location


def query_of(location):
    return urllib.parse.parse_qs(urllib.parse.urlparse(location or "").query)


def token(**fields):
    status, _, body = http("POST", f"{OBASE}/plugin-mcp/oauth/token", data=fields)
    return status, as_json(body)


def redeem(code, client_id, redirect_uri, verifier, resource=RESOURCE):
    fields = {"grant_type": "authorization_code", "code": code,
              "client_id": client_id, "redirect_uri": redirect_uri,
              "code_verifier": verifier}
    if resource:
        fields["resource"] = resource
    return token(**fields)


def refresh(refresh_token, client_id):
    return token(grant_type="refresh_token", refresh_token=refresh_token,
                 client_id=client_id)


def revoke(tok):
    status, _, body = http("POST", f"{OBASE}/plugin-mcp/oauth/revoke", data={"token": tok})
    return status, body


def tool(access_token, name, arguments, jar=None):
    """One tools/call through the production dispatcher, as a Bearer caller."""
    status, headers, body = http(
        "POST", f"{OBASE}/plugin-mcp/",
        headers={"Authorization": f"Bearer {access_token}"},
        json_body={"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                   "params": {"name": name, "arguments": arguments}},
        jar=jar)
    return status, headers, as_json(body)


def outcome(payload):
    """Collapse a tools/call body to the harness's ('ok'|'error', value)."""
    if isinstance(payload, dict) and "error" in payload:
        return "error", payload["error"]
    result = payload.get("result", {}) if isinstance(payload, dict) else {}
    content = result.get("content") if isinstance(result, dict) else None
    if content:
        text = content[0].get("text", "")
        try:
            return "ok", json.loads(text)
        except Exception:
            return "ok", text
    return "ok", result
