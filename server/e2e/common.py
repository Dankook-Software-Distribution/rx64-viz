"""Helpers shared by the end-to-end scripts: HTTP calls, checks, tree search."""
import json
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
failures = 0


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def get(path):
    return call("GET", path)


def check(cond, msg):
    global failures
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        failures += 1


def state(rid, step):
    return get(f"/api/recordings/{rid}/state?step={step}")


def find_all(rid, pred, node=-1, limit=200):
    """Breadth-first over the tree (grouped entries expanded), nodes matching pred."""
    out, queue = [], [node]
    while queue and len(out) < limit:
        n = queue.pop(0)
        for k in get(f"/api/recordings/{rid}/tree?node={n}"):
            if k["count"] > 1:
                queue.extend(k["members"])
                continue
            if pred(k):
                out.append(k)
            if k["children"]:
                queue.append(k["id"])
    return out[:limit]


def first(rid, label, node=-1):
    found = find_all(rid, lambda k: k["label"] == label, node, limit=1)
    return found[0] if found else None


def finish():
    print("FAILED" if failures else "ALL OK", f"({failures} failures)")
    sys.exit(1 if failures else 0)
