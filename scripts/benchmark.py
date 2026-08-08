#!/usr/bin/env python3
"""
Measures what the Redis balance cache is actually worth.

The ledger exposes the same balance two ways -- /balance, which reads through
Redis, and /balance/uncached, which always goes to Postgres. They return
identical data, so the only difference between them is the cache. That is the
whole design of this benchmark: no synthetic workload, no mocked datastore,
just one endpoint measured against its own uncached twin on the same running
instance.

The two are measured in interleaved rounds rather than one after the other, so
that JIT warm-up, connection-pool growth and page-cache warming land on both
equally instead of flattering whichever went second.

    ./scripts/benchmark.py [base-url] [--requests N] [--concurrency C]

Standard library only, so it runs anywhere the service does.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

DEFAULT_BASE = "http://localhost:8080"


@dataclass
class Result:
    label: str
    latencies_ms: list[float]
    errors: int
    wall_seconds: float

    def percentile(self, p: float) -> float:
        if not self.latencies_ms:
            return float("nan")
        ordered = sorted(self.latencies_ms)
        # Nearest-rank, which needs no interpolation and cannot report a
        # latency that was never actually observed.
        index = min(len(ordered) - 1, int(round(p / 100 * len(ordered) + 0.5)) - 1)
        return ordered[index]

    @property
    def mean(self) -> float:
        return statistics.fmean(self.latencies_ms) if self.latencies_ms else float("nan")

    @property
    def rps(self) -> float:
        return len(self.latencies_ms) / self.wall_seconds if self.wall_seconds else 0.0


def call(url: str) -> float:
    started = time.perf_counter()
    with urllib.request.urlopen(url, timeout=10) as response:
        response.read()
    return (time.perf_counter() - started) * 1000


def measure(label: str, url: str, requests: int, concurrency: int) -> Result:
    latencies: list[float] = []
    errors = 0

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        for outcome in pool.map(lambda _: safe_call(url), range(requests)):
            if outcome is None:
                errors += 1
            else:
                latencies.append(outcome)
    wall = time.perf_counter() - started

    return Result(label, latencies, errors, wall)


def safe_call(url: str) -> float | None:
    try:
        return call(url)
    except (urllib.error.URLError, OSError):
        return None


def post(url: str, body: dict, headers: dict | None = None) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read() or "{}")


def seed(base: str) -> str:
    """Creates an account with some history, so the query is not trivially empty."""
    run = int(time.time())
    cash, wallet = f"bench{run}:cash", f"bench{run}:wallet"

    for code, kind, negative in ((cash, "ASSET", True), (wallet, "LIABILITY", False)):
        post(f"{base}/v1/accounts", {
            "code": code, "name": code, "currency": "USD",
            "type": kind, "allowNegative": negative,
        })

    for i in range(25):
        post(f"{base}/v1/transfers", {
            "currency": "USD",
            "description": "benchmark seed",
            "legs": [
                {"accountCode": cash, "direction": "DEBIT", "amountMinor": 1000},
                {"accountCode": wallet, "direction": "CREDIT", "amountMinor": 1000},
            ],
        }, headers={"Idempotency-Key": f"bench-{run}-{i}"})

    return wallet


def report(cached: Result, uncached: Result) -> None:
    print()
    print(f"{'':22}{'mean':>10}{'p50':>10}{'p95':>10}{'p99':>10}{'req/s':>12}{'errors':>9}")
    print("-" * 83)
    for r in (uncached, cached):
        print(f"{r.label:22}{r.mean:>9.2f}ms{r.percentile(50):>8.2f}ms"
              f"{r.percentile(95):>8.2f}ms{r.percentile(99):>8.2f}ms"
              f"{r.rps:>12.0f}{r.errors:>9}")

    if not cached.latencies_ms or not uncached.latencies_ms:
        print("\nnot enough successful samples to compare")
        return

    faster = (1 - cached.mean / uncached.mean) * 100
    p95_faster = (1 - cached.percentile(95) / uncached.percentile(95)) * 100
    throughput = (cached.rps / uncached.rps - 1) * 100

    print()
    print(f"  mean latency    {faster:+.1f}%   ({uncached.mean:.2f}ms -> {cached.mean:.2f}ms)")
    print(f"  p95 latency     {p95_faster:+.1f}%   "
          f"({uncached.percentile(95):.2f}ms -> {cached.percentile(95):.2f}ms)")
    print(f"  throughput      {throughput:+.1f}%   "
          f"({uncached.rps:.0f} -> {cached.rps:.0f} req/s)")
    print()
    print("  Quote the number you measured on the hardware you measured it on.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", nargs="?", default=DEFAULT_BASE)
    parser.add_argument("--requests", type=int, default=2000)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--rounds", type=int, default=4)
    args = parser.parse_args()

    print(f"seeding an account with history on {args.base} ...")
    try:
        wallet = seed(args.base)
    except urllib.error.URLError as e:
        print(f"could not reach {args.base}: {e}", file=sys.stderr)
        return 1

    cached_url = f"{args.base}/v1/accounts/{wallet}/balance"
    uncached_url = f"{args.base}/v1/accounts/{wallet}/balance/uncached"

    print(f"warming up both paths ...")
    measure("warmup", cached_url, 200, args.concurrency)
    measure("warmup", uncached_url, 200, args.concurrency)

    per_round = max(1, args.requests // args.rounds)
    cached = Result("cached (Redis)", [], 0, 0.0)
    uncached = Result("uncached (Postgres)", [], 0, 0.0)

    for round_number in range(args.rounds):
        print(f"round {round_number + 1}/{args.rounds} "
              f"({per_round} requests each, concurrency {args.concurrency}) ...")

        # Alternate which path goes first each round, so neither is
        # consistently advantaged by whatever the other left warm.
        order = [(uncached, uncached_url), (cached, cached_url)]
        if round_number % 2:
            order.reverse()

        for accumulator, url in order:
            r = measure(accumulator.label, url, per_round, args.concurrency)
            accumulator.latencies_ms.extend(r.latencies_ms)
            accumulator.errors += r.errors
            accumulator.wall_seconds += r.wall_seconds

    report(cached, uncached)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
