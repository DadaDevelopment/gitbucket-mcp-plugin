"""
Assertions the gate cannot skip.

Every one of these raises instead of returning a verdict, because the two
defects this module exists for were both *skips*: a precondition that failed
quietly and a comparison whose input went missing. A gate that can decline to
run a check is not a gate, so the check either runs or the run stops.

These functions are pure and need no server, which is what lets
`selftest.py` prove each one fires.
"""


class GateError(RuntimeError):
    """A precondition failed, or a comparison lost its input. Never caught."""


def require(condition, message):
    if not condition:
        raise GateError(message)


def require_ok(status, body, what, ok=(200, 201, 202, 204)):
    """
    A setup step that the rest of the run depends on.

    `fixtures.py` used to ignore these, so a repository created without its
    collaborators, or a fork whose inherited roles were not removed, would
    still have produced a green run — passing for the wrong reason.
    """
    if status not in ok:
        raise GateError(f"{what}: expected HTTP {ok}, got {status}: {str(body)[:200]}")
    return body


def native_decision(status, what):
    """
    GitBucket's own allow/deny verdict, used as the control for the gateway's.

    A server error is not a verdict. Reading 500 as "refused" is how a broken
    server turns into agreement: the comparison would pass with GitBucket
    answering nothing at all.
    """
    if status in (200, 201):
        return "allowed"
    if status in (401, 403):
        return "denied"
    raise GateError(
        f"{what}: GitBucket answered HTTP {status}, which is neither an allow nor a refusal; "
        "the comparison has no control and the run is void"
    )


def require_same_set(name, got, want):
    """
    Full-set equality before any element-wise comparison.

    The previous version compared only the pull requests that appeared in both
    answers, so a gateway that dropped every row compared nothing and passed.
    """
    got, want = set(got), set(want)
    if got != want:
        missing, extra = sorted(want - got), sorted(got - want)
        raise GateError(
            f"{name}: sets differ — missing from the gateway: {missing}; "
            f"not in GitBucket's answer: {extra}"
        )
    if not got:
        raise GateError(f"{name}: both sets are empty, so there is nothing to compare")
    return sorted(got)


# ── timestamps ──────────────────────────────────────────────────────────────
# The gateway publishes dates as Java `Date.toString()`
# ("Thu Oct 23 11:59:31 UTC 2026"); GitBucket's REST API publishes ISO 8601
# ("2026-10-23T11:59:31Z"). Comparing a non-empty string against a non-empty
# string proves nothing, so both are reduced to an instant.

_MONTHS = {m: i + 1 for i, m in enumerate(
    "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}


def java_date_to_epoch(text, what="timestamp"):
    import re, calendar
    m = re.match(
        r"^\w{3} (\w{3}) (\d{1,2}) (\d{2}):(\d{2}):(\d{2}) (\S+) (\d{4})$", (text or "").strip())
    if not m:
        raise GateError(f"{what}: cannot read {text!r} as a Java Date")
    mon, day, hh, mm, ss, zone, year = m.groups()
    if zone not in ("UTC", "GMT", "Z"):
        raise GateError(
            f"{what}: {text!r} is in zone {zone!r}; this comparison assumes the "
            "server runs in UTC and will not guess an offset")
    return calendar.timegm((int(year), _MONTHS[mon], int(day), int(hh), int(mm), int(ss), 0, 0, 0))


def iso_to_epoch(text, what="timestamp"):
    import re, calendar
    m = re.match(r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})", (text or "").strip())
    if not m:
        raise GateError(f"{what}: cannot read {text!r} as an ISO 8601 instant")
    y, mo, d, hh, mm, ss = (int(x) for x in m.groups())
    return calendar.timegm((y, mo, d, hh, mm, ss, 0, 0, 0))


def any_date_to_epoch(text, what="timestamp"):
    """
    An instant from either format the gateway has published.

    ISO 8601 is what it publishes now. The Java form is still accepted so this
    harness can be pointed at an older build, and so the zone guard above
    stays exercised.
    """
    import re
    if re.match(r"^\s*\d{4}-\d{2}-\d{2}", text or ""):
        return iso_to_epoch(text, what)
    return java_date_to_epoch(text, what)


def same_instant(gateway_text, native_text, what, tolerance=1):
    """True when the two timestamps name the same moment, to the second."""
    a = any_date_to_epoch(gateway_text, f"{what} (gateway)")
    b = iso_to_epoch(native_text, f"{what} (GitBucket)")
    return abs(a - b) <= tolerance


def require_iso_utc(text, what):
    """
    The gateway's own timestamps must be machine-readable, not locale- and
    zone-dependent. `Date.toString` passed a truthiness check while being
    unusable to a client and incomparable with GitBucket's own answer.
    """
    import re
    if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", (text or "").strip()):
        raise GateError(f"{what}: {text!r} is not an ISO 8601 instant in UTC")
    return iso_to_epoch(text, what)
