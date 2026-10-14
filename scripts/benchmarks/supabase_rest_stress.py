#!/usr/bin/env python3
"""Concurrent authenticated read-only benchmark for Supabase PostgREST."""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
import json
import math
import os
import re
import time
from typing import Any
from urllib.parse import urlsplit

import aiohttp

DEFAULT_PATH = "rest/v1/hosted_public_directory?select=*&limit=20"
CONNECTION_EXHAUSTION = re.compile(
    r"sorry,\s*too many clients already|too many connections|connection pool exhausted|"
    r"pool.acquire.*timeout|failed to obtain.*connection",
    re.IGNORECASE,
)


def percentile_ms(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(percentile * len(ordered)) - 1))
    return round(ordered[index], 2)


def validate_base_url(value: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme == "https" and parsed.hostname:
        return
    if parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}:
        return
    raise ValueError("SUPABASE_URL must be HTTPS (HTTP is allowed only for a loopback test server).")


async def run_benchmark(
    base_url: str,
    rest_path: str,
    api_key: str,
    access_token: str,
    requests: int = 500,
    concurrency: int = 500,
    timeout_seconds: float = 15.0,
    max_p95_ms: float = 150.0,
) -> dict[str, Any]:
    validate_base_url(base_url)
    if requests < 1 or concurrency < 1:
        raise ValueError("requests and concurrency must both be >= 1")
    url = base_url.rstrip("/") + "/" + rest_path.lstrip("/")
    headers = {
        "apikey": api_key,
        "Authorization": "Bearer " + access_token,
        "Accept": "application/json",
        "Cache-Control": "no-cache",
    }
    timeouts = aiohttp.ClientTimeout(total=timeout_seconds, connect=timeout_seconds)
    connector = aiohttp.TCPConnector(limit=concurrency, limit_per_host=concurrency)
    semaphore = asyncio.Semaphore(concurrency)
    durations: list[float] = []
    statuses: Counter[str] = Counter()
    errors: Counter[str] = Counter()
    exhaustion_count = 0
    started = time.perf_counter()

    async with aiohttp.ClientSession(timeout=timeouts, connector=connector) as session:
        async def one_request() -> None:
            nonlocal exhaustion_count
            async with semaphore:
                start = time.perf_counter()
                try:
                    async with session.get(url, headers=headers) as response:
                        body = await response.text()
                        elapsed = (time.perf_counter() - start) * 1000
                        durations.append(elapsed)
                        statuses[str(response.status)] += 1
                        if CONNECTION_EXHAUSTION.search(body):
                            exhaustion_count += 1
                            errors["connection_exhaustion"] += 1
                        if not 200 <= response.status < 300:
                            errors["non_2xx_http_response"] += 1
                except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
                    durations.append((time.perf_counter() - start) * 1000)
                    statuses["transport_error"] += 1
                    errors[type(exc).__name__] += 1

        await asyncio.gather(*(one_request() for _ in range(requests)))

    elapsed_seconds = max(time.perf_counter() - started, 0.000001)
    p95 = percentile_ms(durations, 0.95)
    passed = sum(errors.values()) == 0 and p95 < max_p95_ms
    return {
        "test": "supabase_postgrest_read_stress",
        "url_path": "/" + rest_path.lstrip("/").split("?", 1)[0],
        "requests": requests,
        "concurrency": concurrency,
        "elapsed_seconds": round(elapsed_seconds, 3),
        "requests_per_second": round(requests / elapsed_seconds, 2),
        "http_status_counts": dict(sorted(statuses.items())),
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
            (["one or more requests failed or returned a non-2xx response"] if sum(errors.values()) else [])
            + ([f"p95 latency {p95}ms is not below {max_p95_ms}ms"] if p95 >= max_p95_ms else [])
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--concurrency", type=int, default=500)
    parser.add_argument("--timeout-seconds", type=float, default=15.0)
    parser.add_argument("--max-p95-ms", type=float, default=150.0)
    parser.add_argument("--path", default=os.getenv("SUPABASE_REST_PATH", DEFAULT_PATH))
    args = parser.parse_args()

    base_url = os.getenv("SUPABASE_URL", "").strip()
    api_key = (os.getenv("SUPABASE_PUBLISHABLE_KEY") or os.getenv("SUPABASE_ANON_KEY") or "").strip()
    access_token = os.getenv("SUPABASE_ACCESS_TOKEN", "").strip()
    if not base_url or not api_key or not access_token:
        parser.error("Set SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY (or SUPABASE_ANON_KEY), and SUPABASE_ACCESS_TOKEN.")
    if "service_role" in api_key.lower() or "sb_secret_" in access_token.lower():
        parser.error("Do not use a service-role or secret API key for this client-path test.")
    report = asyncio.run(run_benchmark(
        base_url, args.path, api_key, access_token,
        args.requests, args.concurrency, args.timeout_seconds, args.max_p95_ms,
    ))
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
