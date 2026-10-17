#!/usr/bin/env python3
"""Stress the Supavisor PostgreSQL pool on port 6543 using read-only SELECT 1."""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
import json
import math
import os
import re
import ssl
import time
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

CONNECTION_EXHAUSTION = re.compile(
    r"sorry,\s*too many clients already|too many connections|remaining connection slots|"
    r"connection pool exhausted|pool.acquire.*timeout|failed to obtain.*connection",
    re.IGNORECASE,
)


def percentile_ms(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(percentile * len(ordered)) - 1))
    return round(ordered[index], 2)


def normalized_dsn(dsn: str) -> tuple[str, str, int | None]:
    parsed = urlsplit(dsn)
    if parsed.scheme not in {"postgres", "postgresql"} or not parsed.hostname:
        raise ValueError("SUPABASE_DATABASE_URL must be a PostgreSQL URL.")
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("Database URL contains an invalid port.") from exc
    if port != 6543:
        raise ValueError("Use the Supavisor pooler connection string on port 6543, not the direct database port.")
    # asyncpg uses the verified SSLContext below; strip psql/libpq sslmode options from the DSN.
    query = [(k, v) for k, v in parse_qsl(parsed.query, keep_blank_values=True)
             if k not in {"sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword"}]
    clean = urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), parsed.fragment))
    return clean, parsed.hostname, port


async def run_benchmark(
    dsn: str,
    requests: int = 500,
    concurrency: int = 500,
    timeout_seconds: float = 15.0,
    max_p95_ms: float = 150.0,
) -> dict[str, Any]:
    try:
        import asyncpg
    except ImportError as exc:
        raise RuntimeError("Install scripts/benchmarks/requirements.txt to run the SQL pool test.") from exc
    if requests < 1 or concurrency < 1:
        raise ValueError("requests and concurrency must both be >= 1")
    clean_dsn, host, port = normalized_dsn(dsn)
    tls = ssl.create_default_context()
    semaphore = asyncio.Semaphore(concurrency)
    durations: list[float] = []
    statuses: Counter[str] = Counter()
    errors: Counter[str] = Counter()
    exhaustion_count = 0
    started = time.perf_counter()

    async def one_request() -> None:
        nonlocal exhaustion_count
        async with semaphore:
            start = time.perf_counter()
            connection = None
            try:
                connection = await asyncpg.connect(
                    dsn=clean_dsn,
                    timeout=timeout_seconds,
                    command_timeout=timeout_seconds,
                    ssl=tls,
                )
                await connection.execute("SELECT 1")
                statuses["success"] += 1
            except Exception as exc:
                error_text = str(exc)
                if CONNECTION_EXHAUSTION.search(error_text):
                    exhaustion_count += 1
                    errors["connection_exhaustion"] += 1
                else:
                    errors[type(exc).__name__] += 1
            finally:
                if connection is not None:
                    try:
                        await connection.close(timeout=timeout_seconds)
                    except Exception as exc:
                        errors[type(exc).__name__] += 1
                durations.append((time.perf_counter() - start) * 1000)

    await asyncio.gather(*(one_request() for _ in range(requests)))
    elapsed_seconds = max(time.perf_counter() - started, 0.000001)
    p95 = percentile_ms(durations, 0.95)
    passed = sum(errors.values()) == 0 and p95 < max_p95_ms
    return {
        "test": "supavisor_sql_pool_stress",
        "host": host,
        "port": port,
        "operation": "SELECT 1",
        "requests": requests,
        "concurrency": concurrency,
        "elapsed_seconds": round(elapsed_seconds, 3),
        "requests_per_second": round(requests / elapsed_seconds, 2),
        "result_counts": dict(sorted(statuses.items())),
        "error_counts": dict(sorted(errors.items())),
        "connection_exhaustion_count": exhaustion_count,
        "latency_ms": {
            "p50": percentile_ms(durations, 0.50),
            "p95": p95,
            "p99": percentile_ms(durations, 0.99),
            "max": round(max(durations, default=0), 2),
        },
        "p95_threshold_ms": max_p95_ms,
        "passed": passed,
        "failure_reasons": (
            (["one or more SQL connections or SELECT operations failed"] if sum(errors.values()) else [])
            + ([f"p95 latency {p95}ms is not below {max_p95_ms}ms"] if p95 >= max_p95_ms else [])
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--concurrency", type=int, default=500)
    parser.add_argument("--timeout-seconds", type=float, default=15.0)
    parser.add_argument("--max-p95-ms", type=float, default=150.0)
    args = parser.parse_args()
    dsn = (os.getenv("SUPABASE_DATABASE_URL") or os.getenv("DATABASE_URL") or "").strip()
    if not dsn:
        parser.error("Set SUPABASE_DATABASE_URL to the Supavisor pooled PostgreSQL URL (port 6543).")
    report = asyncio.run(run_benchmark(
        dsn, args.requests, args.concurrency, args.timeout_seconds, args.max_p95_ms,
    ))
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
