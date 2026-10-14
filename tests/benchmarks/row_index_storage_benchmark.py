#!/usr/bin/env python3
"""Seed 100k rows into transaction-scoped TEMP tables, verify B-tree plans and relation size."""
from __future__ import annotations

import asyncio
import json
import os
import sys
from urllib.parse import urlsplit

try:
    import asyncpg
except ImportError:
    print("Missing dependency: pip install -r tests/benchmarks/requirements.txt", file=sys.stderr)
    raise SystemExit(2)

ROW_COUNT = max(100_000, int(os.getenv("ROW_COUNT", "100000")))
MAX_EXECUTION_MS = float(os.getenv("MAX_INDEX_EXECUTION_MS", "10"))
MAX_STORAGE_MIB = float(os.getenv("MAX_STORAGE_MIB", "250"))


def unpack_explain(value):
    if isinstance(value, str):
        value = json.loads(value)
    return value[0] if isinstance(value, list) else value


def walk_plan(node):
    yield node
    for child in node.get("Plans", []):
        yield from walk_plan(child)


async def run_explain(connection, sql: str) -> dict:
    row = await connection.fetchrow("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)
    if not row:
        raise RuntimeError("EXPLAIN returned no plan.")
    return unpack_explain(row[0])


async def main() -> int:
    if os.getenv("CONFIRM_BENCHMARK") != "YES":
        print("Safety guard: set CONFIRM_BENCHMARK=YES to seed two 100k-row TEMP tables.", file=sys.stderr)
        return 2

    dsn = os.getenv("DATABASE_URL", "")
    if not dsn:
        print("Set DATABASE_URL to the Supavisor connection URI.", file=sys.stderr)
        return 2
    parsed = urlsplit(dsn)
    if parsed.port != 6543:
        print(f"Refusing to run: expected transaction-pool port 6543, got {parsed.port}.", file=sys.stderr)
        return 2
    if "sslmode=require" not in parsed.query and "sslmode=verify-full" not in parsed.query:
        print("Refusing plaintext DB traffic: add sslmode=require (or verify-full) to DATABASE_URL.", file=sys.stderr)
        return 2

    connection = await asyncpg.connect(
        dsn=dsn,
        timeout=15,
        command_timeout=60,
        statement_cache_size=0,
    )
    results = {}
    try:
        # All work is in one transaction, pinning a backend in transaction mode. ON COMMIT DROP
        # leaves no benchmark table or row in the persistent schema.
        async with connection.transaction():
            await connection.execute("""
                CREATE TEMP TABLE profiles (
                    id bigint PRIMARY KEY,
                    phone_number text NOT NULL,
                    display_name text NOT NULL,
                    created_at timestamptz NOT NULL
                ) ON COMMIT DROP
            """)
            await connection.execute("""
                CREATE TEMP TABLE hosted_public_directory (
                    profile_id bigint PRIMARY KEY,
                    handle text NOT NULL,
                    display_name text NOT NULL,
                    is_published boolean NOT NULL,
                    category text NOT NULL,
                    updated_at timestamptz NOT NULL
                ) ON COMMIT DROP
            """)

            await connection.execute("""
                INSERT INTO profiles (id, phone_number, display_name, created_at)
                SELECT g,
                       'mock-phone-' || lpad(g::text, 6, '0'),
                       'Mock Member ' || g,
                       now() - (g * interval '1 second')
                FROM generate_series(1, $1::bigint) AS g
            """, ROW_COUNT)
            await connection.execute("""
                INSERT INTO hosted_public_directory
                    (profile_id, handle, display_name, is_published, category, updated_at)
                SELECT g,
                       'user-' || lpad(g::text, 6, '0'),
                       'Mock Directory Member ' || g,
                       (g % 10 <> 0),
                       'category-' || (g % 20),
                       now() - (g * interval '1 second')
                FROM generate_series(1, $1::bigint) AS g
            """, ROW_COUNT)

            await connection.execute(
                "CREATE UNIQUE INDEX idx_profiles_phone_number_bench ON profiles (phone_number)"
            )
            await connection.execute("""
                CREATE INDEX idx_hosted_public_directory_handle_bench
                ON hosted_public_directory (handle)
                WHERE is_published IS TRUE
            """)
            await connection.execute("ANALYZE profiles")
            await connection.execute("ANALYZE hosted_public_directory")

            profiles_plan = await run_explain(
                connection,
                "SELECT id, display_name FROM profiles WHERE phone_number = 'mock-phone-100000'",
            )
            directory_plan = await run_explain(
                connection,
                "SELECT profile_id, display_name FROM hosted_public_directory "
                "WHERE handle = 'user-099999' AND is_published IS TRUE",
            )
            sizes = await connection.fetchrow("""
                SELECT
                    pg_total_relation_size(to_regclass('pg_temp.profiles'))::bigint AS profiles_bytes,
                    pg_total_relation_size(to_regclass('pg_temp.hosted_public_directory'))::bigint AS directory_bytes
            """)
            results["profiles"] = profiles_plan
            results["hosted_public_directory"] = directory_plan
            results["storage_bytes"] = int(sizes["profiles_bytes"]) + int(sizes["directory_bytes"])
    finally:
        await connection.close()

    checks = []
    for table_name in ("profiles", "hosted_public_directory"):
        explain = results[table_name]
        nodes = list(walk_plan(explain["Plan"]))
        index_nodes = [
            node for node in nodes
            if node.get("Node Type") in ("Index Scan", "Index Only Scan", "Bitmap Index Scan")
        ]
        execution_ms = float(explain.get("Execution Time", float("inf")))
        uses_btree = any(node.get("Index Name", "").endswith("_bench") for node in index_nodes)
        passed = uses_btree and execution_ms < MAX_EXECUTION_MS
        checks.append(passed)
        print(f"{table_name}:")
        print(f"  execution time : {execution_ms:.3f} ms (target < {MAX_EXECUTION_MS:.2f} ms)")
        print(f"  plan node      : {', '.join(node.get('Node Type', '?') for node in nodes)}")
        print(f"  index used     : {uses_btree}")
        print(f"  planning time  : {float(explain.get('Planning Time', 0)):.3f} ms")

    storage_mib = results["storage_bytes"] / (1024 * 1024)
    storage_ok = storage_mib < MAX_STORAGE_MIB
    print("Mock storage footprint (tables + indexes + TOAST):")
    print(f"  profiles + hosted_public_directory: {storage_mib:.2f} MiB")
    print(f"  target: < {MAX_STORAGE_MIB:.2f} MiB")
    print(f"Overall result: {'PASS' if all(checks) and storage_ok else 'FAIL'}")
    return 0 if all(checks) and storage_ok else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
