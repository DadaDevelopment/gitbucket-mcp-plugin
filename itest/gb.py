"""GitBucket's own REST API and web forms, used as the control for the gateway."""
import base64
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8099"


def _decode(payload):
    try:
        return json.loads(payload.decode())
    except Exception:
        return payload.decode(errors="replace")[:400]


def api(method, path, body=None, auth=("root", "root"), raw=False):
    """One call to GitBucket's REST API. Returns (status, parsed-body)."""
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if auth:
        token = base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode()
        req.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(req) as r:
            payload = r.read()
            return r.status, (payload if raw else _decode(payload))
    except urllib.error.HTTPError as e:
        payload = e.read()
        return e.code, (payload if raw else _decode(payload))


class Web:
    """A cookie session. Repository settings and the merge route have no API."""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def signin(self, user, password):
        data = urllib.parse.urlencode({"userName": user, "password": password}).encode()
        req = urllib.request.Request(BASE + "/signin", data=data, method="POST")
        with self.opener.open(req) as r:
            return r.status, r.geturl(), sorted(c.name for c in self.jar)

    def get(self, path):
        try:
            with self.opener.open(BASE + path) as r:
                return r.status, r.read().decode(errors="replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")[:600]

    def post(self, path, fields):
        data = urllib.parse.urlencode(fields, doseq=True).encode()
        req = urllib.request.Request(BASE + path, data=data, method="POST")
        try:
            with self.opener.open(req) as r:
                return r.status, r.geturl()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")[:600]
