import json, urllib.request, urllib.error, base64, itertools

BASE = "http://127.0.0.1:8099"
_ids = itertools.count(1)

ACCOUNTS = {
    "root":      ("root", "root"),
    "owner1":    ("owner1", "Test-pass-1"),
    "devuser":   ("devuser", "Test-pass-2"),
    "guestuser": ("guestuser", "Test-pass-3"),
    "outsider":  ("outsider", "Test-pass-4"),
}

def rpc(who, method, params=None, creds=None):
    """One JSON-RPC call to /plugin-mcp/ as `who`. Returns (http_status, body)."""
    user, pw = creds if creds else ACCOUNTS[who]
    payload = {"jsonrpc": "2.0", "id": next(_ids), "method": method}
    if params is not None:
        payload["params"] = params
    req = urllib.request.Request(BASE + "/plugin-mcp/", data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode())
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw[:400]

def call(who, tool, args, creds=None):
    """tools/call. Returns ('ok', parsed-payload) or ('error', {code,message})."""
    st, body = rpc(who, "tools/call", {"name": tool, "arguments": args}, creds=creds)
    if isinstance(body, dict) and "error" in body:
        return "error", body["error"]
    if isinstance(body, dict) and "result" in body:
        res = body["result"]
        if isinstance(res, dict) and res.get("content"):
            txt = res["content"][0].get("text", "")
            try:
                return "ok", json.loads(txt)
            except Exception:
                return "ok", txt
        return "ok", res
    return "error", {"http": st, "body": body}
