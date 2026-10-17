#!/usr/bin/env python3
"""Opt-in 500-client Supavisor transaction-pool benchmark (PostgreSQL wire protocol)."""
from __future__ import annotations

import asyncio
import math
import os
import sys
import time
from urllib.parse import urlsplit

try:
    import asyncpg
except ImportError:
    print("Missing dependency: pip install -r tests/benchmarks/requirements.txt", file=sys.stderr)
    raise SystemExit(2)

EXHAUSTION_MARKERS = (
    "sorry, too many clients already",
    "too many connections",
    "remaining connection slots are reserved",
    "cannot connect now",
    "53300",
)


def percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(quantile * len(ordered)) - 1)] if ordered else float("inf")


async def main() -> int:
    if os.getenv("CONFIRM_LOAD_TEST") != "YES":
        print("Safety guard: set CONFIRM_LOAD_TEST=YES before opening 500 pooled clients.", file=sys.stderr)
        return 2

    dsn = os.getenv("DATABASE_URL", "")
    if not dsn:
        print("Set DATABASE_URL to the Supavisor transaction-pool URI from the dashboard.", file=sys.stderr)
        return 2
    parsed = urlsplit(dsn)
    if parsed.port != 6543:
        print(f"Refusing to run: DATABASE_URL must use Supavisor transaction port 6543 (got {parsed.port}).", file=sys.stderr)
        return 2
    if "sslmode=require" not in parsed.query and "sslmode=verify-full" not in parsed.query:
        print("Refusing plaintext DB traffic: DATABASE_URL must include sslmode=require (or verify-full).", file=sys.stderr)
        return 2

    concurrency = max(1, int(os.getenv("CONCURRENCY", "500")))
    timeout = max(1.0, float(os.getenv("POOL_ACQUIRE_TIMEOUT_SECONDS", "10")))
    query = os.getenv(
        "POOL_QUERY",
        "SELECT entity_type, entity_id, name FROM public.hosted_public_directory LIMIT 1",
    ).strip()
    if not query.upper().startswith("SELECT ") or ";" in query.rstrip(";"):
        print("POOL_QUERY must be one read-only SELECT statement.", file=sys.stderr)
        return 2

    durations: list[float] = []
    errors: list[str] = []
    exhaustion_count = 0
    successes = 0
    start = time.perf_counter()

    try:
        pool = await asyncpg.create_pool(
            dsn=dsn,
            min_size=1,
            max_size=concurrency,
            timeout=timeout,
            command_timeout=timeout,
            statement_cache_size=0,
        )
    except Exception as exc:
        print(f"Pool startup failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    async def one_request(index: int) -> None:
        nonlocal successes, exhaustion_count
        request_start = time.perf_counter()
        try:
            async with pool.acquire(timeout=timeout) as connection:
                await connection.fetch(query)
            successes += 1
        except Exception as exc:
            message = f"{type(exc).__name__}: {exc}"
            lower = message.lower()
            if any(marker in lower for marker in EXHAUSTION_MARKERS):
                exhaustion_count += 1
            if len(errors) < 20:
                errors.append(f"request {index}: {message[:250]}")
        finally:
            durations.append((time.perf_counter() - request_start) * 1000)

    try:
        await asyncio.gather(*(one_request(i) for i in range(concurrency)))
    finally:
        await pool.close()

    elapsed = time.perf_counter() - start
    p50, p95, p99 = (percentile(durations, q) for q in (0.50, 0.95, 0.99))
    print("Supavisor transaction-pool results")
    print(f"  concurrent clients : {concurrency}")
    print(f"  successes/errors   : {successes}/{concurrency - successes}")
    print(f"  connection exhaustion errors: {exhaustion_count}")
    print(f"  latency p50/p95/p99: {p50:.2f}/{p95:.2f}/{p99:.2f} ms")
    print(f"  elapsed             : {elapsed:.2f} s")
    for error in errors:
        print(f"  error: {error}")

    passed = successes == concurrency and exhaustion_count == 0 and p95 < 150
    print(f"  result              : {'PASS' if passed else 'FAIL'}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
