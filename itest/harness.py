import json, sys
RESULTS = []

def check(name, got, want, note=""):
    ok = got == want
    RESULTS.append((ok, name, got, want, note))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"\n        got : {got!r}\n        want: {want!r}"))
    return ok

def verdict(kind, payload):
    """Collapse a tool outcome to a comparable verdict."""
    if kind == "ok":
        return "allowed"
    msg = payload.get("message", "") if isinstance(payload, dict) else str(payload)
    code = payload.get("code") if isinstance(payload, dict) else None
    if "not found or not readable" in msg:
        return "not-found"
    if "may not open an issue" in msg or "does not have write access" in msg or "does not have developer access" in msg or "may edit only their own" in msg:
        return "forbidden"
    return f"error:{code}:{msg[:70]}"

def summary(title):
    bad = [r for r in RESULTS if not r[0]]
    print()
    print(f"=== {title}: {len(RESULTS)-len(bad)} passed, {len(bad)} failed ===")
    for r in bad:
        print("   FAILED:", r[1])
    return len(bad)
