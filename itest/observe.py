"""
Observing stored state, so that "nothing was written" is something the gate
has actually seen.

The defect this module exists for: the gate's witness for a refused write was

    def issue_count(repo):
        r = api("GET", f"/api/v3/repos/owner1/{repo}/issues?state=all")[1]
        return len(r) if isinstance(r, list) else -1

which discarded the HTTP status and mapped an unavailable control to `-1`.
When the read failed on both sides of the operation, `after - before` was
**zero** — indistinguishable from a genuine no-write. So a refusal check
reported that nothing was stored while the gate had not observed storage at
all, and an unintended insert underneath would have been reported as a pass.

The rule here is that an unavailable witness is never a value. There is no
sentinel: a read that cannot be trusted raises, and the run stops. A
successful empty list stays a lawful count of zero, because "no issues yet"
is a real observation and must not be confused with "could not look".

Everything takes its API callable as an argument, so `selftest.py` can drive
the real code path with doubles and no server. That matters: this defect was a
bypass at the *call site*, which a test of `require_ok` in isolation could not
have caught.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from checks import GateError, require_ok


def stored_issue_count(api_fn, owner, repo, auth=None):
    """
    How many issues GitBucket is storing for a repository.

    Raises unless GitBucket answered 200 with a JSON list. Returns 0 for a
    successful empty list.
    """
    what = f"read the stored issues of {owner}/{repo}"
    path = f"/api/v3/repos/{owner}/{repo}/issues?state=all"
    status, body = api_fn("GET", path) if auth is None else api_fn("GET", path, None, auth)
    # Only 200 is a reading. 204, a redirect or any error means the witness is
    # unavailable, which is not a count.
    require_ok(status, body, what, ok=(200,))
    if not isinstance(body, list):
        raise GateError(
            f"{what}: GitBucket answered HTTP {status} with "
            f"{type(body).__name__}, not a JSON list. The storage witness is "
            "unavailable, and an unavailable witness is not a count of zero."
        )
    return len(body)


def observe_issue_write(api_fn, operation, owner, repo, auth=None):
    """
    Count the stored issues, perform `operation`, count again.

    Returns `(kind, payload, delta)`. Both reads go through
    `stored_issue_count`, so a failure on either side ends the run instead of
    cancelling out into a delta of zero.
    """
    before = stored_issue_count(api_fn, owner, repo, auth)
    kind, payload = operation()
    after = stored_issue_count(api_fn, owner, repo, auth)
    if after < before:
        raise GateError(
            f"{owner}/{repo}: the stored issue count fell from {before} to "
            f"{after} across one operation; the witness is not describing this run"
        )
    return kind, payload, after - before
