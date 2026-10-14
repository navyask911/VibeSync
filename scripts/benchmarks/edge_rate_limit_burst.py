#!/usr/bin/env python3
"""Burst the verify-truecaller Edge Function with non-mutating invalid requests."""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
import json
import math
import os
import time
from typing import Any
from urllib.parse import urlsplit

import aiohttp


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


async def run_burst(
    base_url: str,
    function_name: str = "verify-truecaller",
    api_key: str = "local-test-key",
    access_token: str = "local-test-token",
    requests: int = 100,
    concurrency: int = 100,
    timeout_seconds: float = 15.0,
    expected_429_min: int = 1,
) -> dict[str, Any]:
    validate_base_url(base_url)
    if requests < 1 or concurrency < 1 or expected_429_min < 0:
        raise ValueError("requests/concurrency must be >= 1 and expected_429_min >= 0")
    url = base_url.rstrip("/") + "/functions/v1/" + function_name.strip("/")
    headers = {
        "apikey": api_key,
        "Authorization": "Bearer " + access_token,
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Cache-Control": "no-store",
    }
    timeouts = aiohttp.ClientTimeout(total=timeout_seconds, connect=timeout_seconds)
    connector = aiohttp.TCPConnector(limit=concurrency, limit_per_host=concurrency)
    semaphore = asyncio.Semaphore(concurrency)
    durations: list[float] = []
    statuses: Counter[str] = Counter()
    errors: Counter[str] = Counter()
    rate_limited = 0
    server_errors = 0
    unexpected_successes = 0
    started = time.perf_counter()

    async with aiohttp.ClientSession(timeout=timeouts, connector=connector) as session:
        async def one_request() -> None:
            nonlocal rate_limited, server_errors, unexpected_successes
            async with semaphore:
                start = time.perf_counter()
                try:
                    async with session.post(url, headers=headers, json={}) as response:
                        # Discard response bodies: never log auth/session data.
                        await response.read()
                        durations.append((time.perf_counter() - start) * 1000)
                        statuses[str(response.status)] += 1
                        if response.status == 429:
                            rate_limited += 1
                        elif response.status >= 500:
                            server_errors += 1
                            errors["server_error"] += 1
                        elif 200 <= response.status < 300:
                            # An empty OAuth body should not create a session or succeed.
                            unexpected_successes += 1
                            errors["unexpected_success"] += 1
                except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
                    durations.append((time.perf_counter() - start) * 1000)
                    statuses["transport_error"] += 1
                    errors[type(exc).__name__] += 1

        await asyncio.gather(*(one_request() for _ in range(requests)))

    elapsed_seconds = max(time.perf_counter() - started, 0.000001)
    p95 = percentile_ms(durations, 0.95)
    passed = (
        sum(errors.values()) == 0
        and rate_limited >= expected_429_min
        and unexpected_successes == 0
    )
    return {
        "test": "verify_truecaller_edge_rate_limit_burst",
        "function": function_name,
        "requests": requests,
        "concurrency": concurrency,
        "payload": "empty JSON object (safe invalid request)",
        "elapsed_seconds": round(elapsed_seconds, 3),
        "requests_per_second": round(requests / elapsed_seconds, 2),
        "http_status_counts": dict(sorted(statuses.items())),
        "rate_limited_429_count": rate_limited,
        "server_error_count": server_errors,
        "unexpected_success_count": unexpected_successes,
        "error_counts": dict(sorted(errors.items())),
        "latency_ms": {
            "p50": percentile_ms(durations, 0.50),
            "p95": p95,
            "p99": percentile_ms(durations, 0.99),
            "max": round(max(durations, default=0), 2),
        },
        "expected_429_min": expected_429_min,
        "passed": passed,
        "failure_reasons": (
            (["rate limit did not return enough HTTP 429 responses"] if rate_limited < expected_429_min else [])
            + (["unexpected HTTP 2xx response to invalid OAuth payload"] if unexpected_successes else [])
            + (["one or more transport failures or HTTP 5xx responses"] if sum(errors.values()) else [])
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--function", default=os.getenv("EDGE_FUNCTION_NAME", "verify-truecaller"))
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=100)
    parser.add_argument("--timeout-seconds", type=float, default=15.0)
    parser.add_argument("--expected-429-min", type=int, default=1)
    args = parser.parse_args()
    base_url = os.getenv("SUPABASE_URL", "").strip()
    api_key = (os.getenv("SUPABASE_PUBLISHABLE_KEY") or os.getenv("SUPABASE_ANON_KEY") or "").strip()
    access_token = os.getenv("SUPABASE_ACCESS_TOKEN", "").strip()
    if not base_url or not api_key or not access_token:
        parser.error("Set SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY (or SUPABASE_ANON_KEY), and SUPABASE_ACCESS_TOKEN.")
    if "service_role" in api_key.lower() or "sb_secret_" in access_token.lower():
        parser.error("Do not use a service-role or secret API key for this burst test.")
    report = asyncio.run(run_burst(
        base_url, args.function, api_key, access_token,
        args.requests, args.concurrency, args.timeout_seconds, args.expected_429_min,
    ))
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
