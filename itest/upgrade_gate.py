"""
Installation, upgrade and restart, through GitBucket's own plugin mechanism.

    python3 itest/upgrade_gate.py --war <gitbucket.war> \
        --previous <pre-oauth.jar> --candidate <candidate.jar>

The direct Liquibase tests under `src/test` establish what the changelog does
to a database. They say nothing about whether GitBucket ever runs it: a
migration can be written, committed, tested and still never execute, because
what runs is what the loaded `Plugin` class declares and what Solidbase finds
packaged in the JAR.

So this gate never touches the changelog. It starts real GitBucket processes
and asks the questions an operator asks:

1.  the previous plugin runs, and data created under it is real;
2.  the candidate replaces it and GitBucket's own machinery migrates;
3.  the data created under the previous plugin is still there;
4.  an OAuth grant issued after the upgrade survives a restart;
5.  a revocation performed before a restart is still in force after it.

Point 5 is the one that distinguishes persistence from a cache. A revocation
that lived only in memory would come back to life here, and the credential
would start working again.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import check, summary
from checks import GateError, require

PORT = 8099
OBASE = f"http://localhost:{PORT}"
BASE = f"http://127.0.0.1:{PORT}"

# oauth_gate reads these at import time, so the addresses have to agree.
import oauth_gate
from oauth_gate import ClientHost, pkce, authorize, redeem, revoke, signin, tool, outcome

ADMIN = ("root", "root")
TIMEZONE = os.environ.get("ITEST_TZ", "Europe/Kyiv")


# ── running GitBucket ───────────────────────────────────────────────────────

class Instance:
    """One GitBucket process over one GITBUCKET_HOME."""

    def __init__(self, war, home):
        self.war, self.home, self.proc = war, home, None
        self.log = os.path.join(home, "server.log")

    def install(self, jar, name):
        plugins = os.path.join(self.home, "plugins")
        os.makedirs(plugins, exist_ok=True)
        shutil.copyfile(jar, os.path.join(plugins, name))

    def remove_plugin(self, name):
        """Take a plugin out of both the scan directory and the staging area."""
        gone = False
        for d in (os.path.join(self.home, "plugins"),
                  os.path.join(self.home, "plugins", ".installed")):
            p = os.path.join(d, name)
            if os.path.isfile(p):
                os.remove(p)
                gone = True
        return gone

    @staticmethod
    def port_is_free():
        import socket
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("127.0.0.1", PORT))
                return True
            except OSError:
                return False

    def start(self):
        require(self.proc is None, "this instance is already running")
        # Said plainly here rather than discovered as a readiness timeout two
        # minutes later: a leftover server on this port answers health checks
        # and would be mistaken for the instance under test.
        require(Instance.port_is_free(),
                f"127.0.0.1:{PORT} is already in use. Something else is serving this "
                f"port; this gate will not attribute its answers to the artifact "
                f"under test.")
        out = open(self.log, "ab")
        self.proc = subprocess.Popen(
            ["java",
             # A real zone with a DST offset, not UTC: the timestamp handling
             # under test renders in the JVM's default zone, and a UTC host is
             # exactly the configuration that hid a defect here before. The
             # IANA spelling matters too — PostgreSQL 16 rejects the
             # deprecated "Europe/Kiev" the JDBC driver would otherwise send.
             f"-Duser.timezone={TIMEZONE}",
             f"-Dgitbucket.mcp.oauth.baseUrl={OBASE}",
             "-Dgitbucket.mcp.oauth.allowLoopbackClients=true",
             "-jar", self.war,
             f"--port={PORT}", "--host=127.0.0.1", f"--gitbucket.home={self.home}"],
            stdout=out, stderr=subprocess.STDOUT)
        for _ in range(120):
            if self.proc.poll() is not None:
                raise GateError(f"GitBucket exited at startup; see {self.log}")
            try:
                with urllib.request.urlopen(BASE + "/plugin-mcp/health", timeout=3) as r:
                    if r.status == 200:
                        return self
            except Exception:
                time.sleep(1)
        raise GateError(f"GitBucket did not become ready; see {self.log}")

    def stop(self):
        if self.proc is None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=90)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=30)
        self.proc = None
        # The plugin watch thread and H2 both need the port and the lock file
        # released before the next process claims them.
        time.sleep(3)

    def log_text(self):
        with open(self.log, encoding="utf-8", errors="replace") as fh:
            return fh.read()


# ── talking to it without the plugin's help ─────────────────────────────────

def api(method, path, body=None, creds=ADMIN):
    import base64
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
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


def rpc_basic(method, params, creds):
    import base64
    payload = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    req = urllib.request.Request(BASE + "/plugin-mcp/", data=json.dumps(payload).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Basic " +
                   base64.b64encode(f"{creds[0]}:{creds[1]}".encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]


def packaged_version(jar):
    import configparser
    with zipfile.ZipFile(jar) as z:
        raw = z.read("gitbucket-plugin.properties").decode("utf-8")
    p = configparser.ConfigParser()
    p.read_string("[d]\n" + raw)
    v = p["d"].get("pluginversion", "").strip()
    require(bool(v), f"{jar} packages a descriptor with no pluginVersion")
    return v


def packages_migration(jar):
    with zipfile.ZipFile(jar) as z:
        return [n for n in z.namelist() if n.startswith("update/") and n.endswith(".xml")]


def grant_token(scope="mcp:read mcp:write"):
    """A full authorization through the real consent screen. Returns the token."""
    verifier, challenge = pkce()
    jar = signin(*ADMIN)
    code, _ = authorize(jar, CLIENT_ID, oauth_gate.REDIRECT, scope, challenge)
    status, payload = redeem(code, CLIENT_ID, oauth_gate.REDIRECT, verifier)
    require(status == 200 and "access_token" in payload,
            f"the token endpoint answered {status}: {payload}")
    return payload["access_token"]


def token_works(access_token):
    _, _, payload = tool(access_token, "whoami", {})
    kind, _ = outcome(payload)
    return kind == "ok"


# ── the gate ────────────────────────────────────────────────────────────────

CLIENT_ID = None


def run(war, previous, candidate, home):
    global CLIENT_ID
    prev_name = "gitbucket-mcp-plugin-previous.jar"
    cand_name = "gitbucket-mcp-plugin-candidate.jar"
    cand_version = packaged_version(candidate)

    check("the previous plugin packages no migration", packages_migration(previous), [])
    check("the candidate packages its migration",
          packages_migration(candidate), ["update/mcp-plugin_1.0.xml"])

    host = ClientHost()
    started = []

    def launch():
        gb = Instance(war, home)
        started.append(gb)
        return gb

    try:
        CLIENT_ID = host.publish("/client.json", [oauth_gate.REDIRECT],
                                 name="Upgrade gate client")

        # ── 1. the previous plugin, and real data under it ──────────────────
        print("## 1. the previous plugin")
        gb = launch()
        gb.install(previous, prev_name)
        gb.start()
        check("the previous plugin initializes",
              f"Initialize {prev_name}" in gb.log_text(), True)
        st, _ = rpc_basic("tools/list", {}, ADMIN)
        check("its MCP endpoint answers over Basic", st, 200)
        check("it serves no authorization-server metadata",
              api("GET", "/.well-known/oauth-authorization-server")[0], 404)

        st, _ = api("POST", "/api/v3/user/repos",
                    {"name": "carried", "description": "created under the previous plugin",
                     "private": False, "auto_init": True})
        require(st in (200, 201), f"could not create the repository ({st})")
        st, _ = api("POST", "/api/v3/repos/root/carried/issues",
                    {"title": "created before the upgrade", "body": "survives?"})
        require(st in (200, 201), f"could not create the issue ({st})")
        st, issues = api("GET", "/api/v3/repos/root/carried/issues?state=all")
        check("data exists under the previous plugin", (st, len(issues)), (200, 1))
        gb.stop()

        # ── 2. the upgrade, through GitBucket's own mechanism ───────────────
        print("\n## 2. the upgrade")
        check("the previous JAR is removed from the scan and the staging area",
              gb.remove_plugin(prev_name), True)
        gb.install(candidate, cand_name)
        gb = launch()
        gb.start()
        text = gb.log_text()
        check("the candidate initializes on the existing home",
              f"Initialize {cand_name}" in text, True)
        check("nothing failed during startup",
              [l for l in text.splitlines()
               if ("ERROR" in l or "Exception" in l) and "mcp" in l.lower()], [])

        # ── 3. the migration actually ran ───────────────────────────────────
        print("\n## 3. the migration")
        check("the authorization server is now published",
              api("GET", "/.well-known/oauth-authorization-server")[0], 200)
        check("initialize reports the candidate's packaged version",
              rpc_basic("initialize", {"protocolVersion": "2024-11-05", "capabilities": {}},
                        ADMIN)[1]["result"]["serverInfo"]["version"], cand_version)
        # The tables are not inspected directly. A grant is stored, read back
        # on a later request and survives a restart — which is the property,
        # and it fails if the migration did not run.
        access = grant_token()
        check("a grant can be issued against the migrated schema",
              token_works(access), True)

        # The Basic connection that existed before the upgrade has to keep
        # working, and keep working unscoped: those credentials are the
        # account itself, not a delegation of part of it.
        st, body = rpc_basic("tools/call", {"name": "whoami", "arguments": {}}, ADMIN)
        check("a Basic caller still reaches the dispatcher after the upgrade", st, 200)
        check("and is still identified as the authenticating account",
              json.loads(body["result"]["content"][0]["text"]).get("login"), ADMIN[0])
        st, body = rpc_basic("tools/call",
                             {"name": "update_pull_request",
                              "arguments": {"owner": "root", "repo": "carried", "number": 99}},
                             ADMIN)
        check("a Basic caller is not refused for scope",
              "insufficient_scope" in json.dumps(body), False)

        # ── 4. data created under the previous plugin ───────────────────────
        print("\n## 4. existing data")
        st, issues = api("GET", "/api/v3/repos/root/carried/issues?state=all")
        check("the repository and its issue survived the upgrade",
              (st, len(issues), issues[0]["title"] if issues else None),
              (200, 1, "created before the upgrade"))
        st, _ = api("GET", "/api/v3/repos/root/carried")
        check("the repository itself is intact", st, 200)

        # ── 5. restart: the grant persists ──────────────────────────────────
        print("\n## 5. restart with persisted state")
        gb.stop()
        gb = launch()
        gb.start()
        check("the token issued before the restart still works",
              token_works(access), True)

        # ── 6. restart: the revocation persists ─────────────────────────────
        print("\n## 6. restart with persisted revocation")
        status, _ = revoke(access)
        check("revocation answers 200", status, 200)
        check("the token stops working immediately", token_works(access), False)
        gb.stop()
        gb = launch()
        gb.start()
        check("the revocation is still in force after a restart",
              token_works(access), False)
        fresh = grant_token()
        check("a new grant still works after all of it", token_works(fresh), True)
        gb.stop()
    finally:
        host.stop()
        for gb in started:
            try:
                gb.stop()
            except Exception:
                pass

    failed = summary("UPGRADE GATE")
    ran = len(__import__("harness").RESULTS)
    if ran < 22:
        raise GateError(f"only {ran} checks executed; this gate has no result below 22")
    return 1 if failed else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--war", required=True)
    ap.add_argument("--previous", required=True)
    ap.add_argument("--candidate", required=True)
    ap.add_argument("--home", required=True)
    ap.add_argument("--db-url", help=(
        "JDBC URL for a blank database. Omitted, GitBucket uses its bundled H2. "
        "Supplied, the migration is executed against that backend instead, which "
        "is the only way the PostgreSQL path is actually tested rather than "
        "generated."))
    ap.add_argument("--db-user", default="")
    ap.add_argument("--db-password", default="")
    a = ap.parse_args()
    for p in (a.war, a.previous, a.candidate):
        require(os.path.isfile(p), f"not a readable file: {p}")
    require(not os.path.exists(a.home) or not os.listdir(a.home),
            f"{a.home} is not empty; this gate needs a blank GITBUCKET_HOME")
    os.makedirs(a.home, exist_ok=True)
    if a.db_url:
        # Written before the first start, so the previous plugin and every
        # migration after it run against this backend from the beginning.
        with open(os.path.join(a.home, "database.conf"), "w", encoding="utf-8") as fh:
            fh.write("db {\n"
                     f'  url = "{a.db_url}"\n'
                     f'  user = "{a.db_user}"\n'
                     f'  password = "{a.db_password}"\n'
                     "}\n")
        print(f"backend: {a.db_url}\n")
    else:
        print("backend: bundled H2\n")
    try:
        sys.exit(run(a.war, a.previous, a.candidate, a.home))
    except GateError as exc:
        print(f"\nGATE ABORTED: {exc}", file=sys.stderr)
        print("No result may be read from this run.", file=sys.stderr)
        sys.exit(2)
