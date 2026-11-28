#!/usr/bin/env python3
"""Current-semantics LabFlow lock A/B workload; run only against a disposable local stack."""
from __future__ import annotations

import argparse
import json
import os
import threading
import time
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlencode, urlsplit

from bench_reservation import Api, error_code, http_json, iso, summarize_latencies, utc_now


def request(api, token, method, path, body=None):
    return http_json(method, api.url(path), {"Authorization": "Bearer " + token},
                     body, timeout=api.timeout)


def expect(result, status):
    actual, data, _, _ = result
    if actual != status or not isinstance(data, dict):
        raise RuntimeError(f"Expected HTTP {status}, got {actual}, code={error_code(data)}")
    return data


def batch(tasks):
    """Start all measured HTTP requests together; setup and result checks are outside timing."""
    ready = threading.Barrier(len(tasks) + 1)

    def worker(task):
        ready.wait(timeout=60)
        return task()

    with ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        futures = [pool.submit(worker, task) for task in tasks]
        # Threads may take measurable time to reach the barrier. The wall clock
        # starts only once every measured request is released together.
        ready.wait(timeout=60)
        start = time.perf_counter()
        rows = [f.result() for f in futures]
        wall = time.perf_counter() - start
    return rows, wall


def record(result):
    status, data, latency, _ = result
    return {"http": status, "code": error_code(data), "latency_ms": latency,
            "id": data.get("id") if isinstance(data, dict) else None,
            "state": data.get("status") if isinstance(data, dict) else None}


def summarize(rows, wall, success_status):
    counts = Counter(str(row["http"]) for row in rows)
    successes = sum(row["http"] == success_status for row in rows)
    return {"requests": rows, "status_counts": dict(counts),
            "error_code_counts": dict(Counter(row["code"] or f"HTTP_{row['http']}" for row in rows)),
            "wall_seconds": wall, "all_responses_per_second": len(rows) / wall,
            "successful_operations_per_second": successes / wall,
            "latency_all": summarize_latencies([r["latency_ms"] for r in rows]),
            "latency_success": summarize_latencies([r["latency_ms"] for r in rows if r["http"] == success_status])}


def list_reservations(api, token, equipment_id):
    """Read every reservation for one equipment item.

    Offset pages sorted by a non-unique column can skip or repeat rows. ``id`` is
    unique, and the API caps page size at 100, so overlap is dropped rather than
    counted twice.
    """
    page = 0
    rows = []
    seen = set()
    while True:
        query = urlencode({
            "equipmentId": equipment_id,
            "page": page,
            "size": 100,
            "sort": "id,asc",
        })
        data = expect(request(api, token, "GET", "/api/reservations?" + query), 200)
        content = data["content"]
        for row in content:
            if row["id"] in seen:
                continue
            seen.add(row["id"])
            rows.append(row)
        page += 1
        if not content or page >= data["totalPages"]:
            return rows


def approval_ok(rows, persisted, expected_ids):
    winners = [r for r in rows if r["http"] == 200 and r["state"] == "APPROVED"]
    approved = [r["id"] for r in persisted if r["status"] == "APPROVED"]
    persisted_ids = [r["id"] for r in persisted]
    winner_id = winners[0]["id"] if len(winners) == 1 else None
    return (len(rows) >= 2 and len(winners) == 1 and approved == [winner_id]
            and winner_id in expected_ids
            and sorted(persisted_ids) == sorted(expected_ids)
            and all((r["status"] == "APPROVED") if r["id"] == winner_id else r["status"] == "PENDING"
                    for r in persisted)
            and all(r is winners[0] or (r["http"] == 409 and r["code"] == "RESERVATION_CONFLICT")
                    for r in rows))


def run(args, credentials):
    api = Api(args.base_url, timeout=args.timeout)
    admin = api.login(credentials["admin"]["username"], credentials["admin"]["password"])
    accounts = credentials["students"][:args.concurrency]
    if len(accounts) != args.concurrency or len({a["username"] for a in accounts}) != len(accounts):
        raise ValueError("Supply a distinct student account for each concurrent request")
    students = [api.login(a["username"], a["password"]) for a in accounts]
    run_id = uuid.uuid4().hex[:16]
    equipment_count = args.concurrency if args.scenario == "independent" else 1
    equipment = [api.create_equipment(admin, f"AB-{run_id}-{i}", f"lock-ab-{i}")
                 for i in range(equipment_count)]
    start = utc_now() + timedelta(days=4)
    payloads = [{"equipmentId": equipment[i] if equipment_count > 1 else equipment[0],
                 "purpose": f"lock-ab-{run_id}-{i}", "startTime": iso(start),
                 "endTime": iso(start + timedelta(hours=1))}
                for i in range(args.concurrency)]

    def create(i):
        return record(request(api, students[i], "POST", "/api/reservations", payloads[i]))

    if args.scenario == "approval":
        # Sequential setup: isolate approval correctness from the application lock benchmark.
        prepared = [create(i) for i in range(args.concurrency)]
        if any(r["http"] != 201 or r["state"] != "PENDING" or r["id"] is None for r in prepared):
            raise RuntimeError("Approval setup failed: " + json.dumps(prepared, ensure_ascii=False))
        ids = [r["id"] for r in prepared]
        rows, wall = batch([lambda rid=rid: record(request(api, admin, "PATCH",
                            f"/api/reservations/{rid}/decision", {"decision": "APPROVED"}))
                            for rid in ids])
        persisted = list_reservations(api, admin, equipment[0])
        valid = approval_ok(rows, persisted, ids)
        result = summarize(rows, wall, 200)
        result["setup"] = prepared
    else:
        rows, wall = batch([lambda i=i: create(i) for i in range(args.concurrency)])
        persisted = [row for eid in equipment for row in list_reservations(api, admin, eid)]
        created = [r for r in rows if r["http"] == 201]
        # Lock timeouts are measured outcomes, not interval conflicts. Other failures invalidate a run.
        valid = (all((r["http"] == 201 and r["state"] == "PENDING" and r["id"] is not None)
                     or (r["http"] == 409 and r["code"] == "RESERVATION_LOCK_TIMEOUT") for r in rows)
                 and len(created) > 0 and len(persisted) == len(created)
                 and {r["id"] for r in persisted} == {r["id"] for r in created}
                 and all(r["status"] == "PENDING" for r in persisted))
        result = summarize(rows, wall, 201)

    return {**result, "valid": valid, "scenario": args.scenario, "declared_lock_mode": args.lock_mode,
            "commit": args.commit, "run_id": run_id, "round": args.round,
            "concurrency": args.concurrency, "equipment_ids": equipment,
            "persisted": [{"id": r["id"], "equipmentId": r["equipmentId"], "status": r["status"]}
                          for r in persisted]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base-url", default="http://127.0.0.1:18080")
    p.add_argument("--lock-mode", choices=["local", "redis"], required=True)
    p.add_argument("--scenario", choices=["contention", "independent", "approval"], required=True)
    p.add_argument("--concurrency", type=int, required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--round", type=int, default=1)
    p.add_argument("--timeout", type=float, default=30)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--disposable-db", action="store_true", required=True)
    args = p.parse_args()
    url = urlsplit(args.base_url)
    if url.scheme != "http" or url.hostname not in {"localhost", "127.0.0.1", "::1"} or url.username or url.password or url.path not in {"", "/"} or url.query or url.fragment:
        p.error("Use only a local disposable HTTP stack")
    if args.concurrency < 2 or args.round < 1 or args.timeout <= 0:
        p.error("concurrency >= 2, round >= 1, timeout > 0 required")
    if args.out.exists():
        p.error("Output already exists; never overwrite a run")
    credentials = json.loads(os.environ["LABFLOW_BENCH_ACCOUNTS"])
    result = run(args, credentials)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("x", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(f"valid={result['valid']} output={args.out}")
    return 0 if result["valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
