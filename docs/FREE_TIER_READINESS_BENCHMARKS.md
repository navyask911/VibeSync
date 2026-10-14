# VibeSync Free-Tier Readiness Benchmarks

Revision date: 15 October 2026.

## Current Supabase Free quotas (checked 15 October 2026)

Supabase's published plan documentation currently lists these Free-plan quotas: 50,000 monthly active users; 500,000 Edge Function invocations; 500 MB database size per project; 1 GB file storage; 5 GB egress; 2 million Realtime messages; and 200 peak Realtime connections. The compute table lists the Free/Nano instance at 60 database connections and 200 connection-pooler clients. Verify these numbers again in the live dashboard before a release because plan limits can change.

Official references: [Supabase pricing](https://supabase.com/pricing), [billing and plan quotas](https://supabase.com/docs/guides/platform/billing-on-supabase), and [compute and connection limits](https://supabase.com/docs/guides/platform/compute-and-disk).

Consequently, 100,000 **monthly active users** exceeds the published Free MAU quota. A benchmark cannot increase that quota. 100,000 registered accounts is a different metric, but the actual active-user count, concurrent load, storage, egress, Realtime messages/connections, and database load still must remain within the limits. Do not publish a “100k-user Free-tier ready” claim on the basis of these scripts.

These scripts are measurement tools, not evidence that a free-tier plan can sustain 100,000 active users. Registered-user count is not a concurrency target. Do not state 100k-user readiness until realistic ramp/soak traffic, the target project's current plan limits, and production-like data distributions have been measured.

## Connection types matter

- The HTTPS PostgREST endpoint at /rest/v1 is not PostgreSQL protocol and cannot be sent to Supavisor port 6543.
- Port 6543 is the Supavisor pooled PostgreSQL endpoint. The SQL test opens PostgreSQL connections and issues only SELECT 1.
- Edge Function tests call /functions/v1/<name> over HTTPS.

The REST test uses a read-only request to hosted_public_directory; it discards response bodies and reports no tokens or response data. The Edge burst test sends an empty JSON object to verify-truecaller, an intentionally invalid OAuth payload that should be rejected without creating a user session or calling Truecaller token/userinfo APIs. Use staging first: a burst can consume shared rate limits and database capacity.

## Install

~~~bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/benchmarks/requirements.txt
python -m unittest discover -s scripts/benchmarks -p 'test_*.py' -v
~~~

Local mock-server tests need no Supabase credentials and do not contact production. The CI workflow runs this mock suite and Python syntax checks only.

## 1. 500-way REST/PostgREST read stress

Set a publishable/anon API key and a valid authenticated VibeSync/Supabase access JWT. Never use a service-role key for a client-path benchmark.

~~~bash
export SUPABASE_URL="https://YOUR_PROJECT.supabase.co"
export SUPABASE_PUBLISHABLE_KEY="YOUR_PUBLISHABLE_KEY"
export SUPABASE_ACCESS_TOKEN="YOUR_VALID_AUTHENTICATED_ACCESS_JWT"

python scripts/benchmarks/supabase_rest_stress.py \
  --requests 500 --concurrency 500 --max-p95-ms 150
~~~

Default request path: /rest/v1/hosted_public_directory?select=*&limit=20. Override with SUPABASE_REST_PATH or --path. The script exits non-zero if any request is not 2xx, any transport error occurs, a connection-exhaustion response is detected, or measured p95 is not strictly below 150 ms. It reports p50/p95/p99/max, throughput, status counts, and error counts.

## 2. 500-way Supavisor port-6543 connection test

Use the pooled PostgreSQL connection string from Supabase for port 6543, with a dedicated benchmark/database credential of minimum required scope. This test creates and closes one TLS-verified connection per request; it does not perform writes.

~~~bash
export SUPABASE_DATABASE_URL="postgresql://POOLER_USER:DB_PASSWORD@YOUR_POOLER_HOST:6543/postgres?sslmode=require"

python scripts/benchmarks/supavisor_sql_stress.py \
  --requests 500 --concurrency 500 --max-p95-ms 150
~~~

The script rejects a non-6543 port and requires certificate-verified TLS. High connection churn is intentionally stressful; run on staging first and stop if database health degrades. The strict p95 target is a test gate, not a claim that every Supavisor setup can meet it.

## 3. 100-request Edge Function rate-limit burst

~~~bash
python scripts/benchmarks/edge_rate_limit_burst.py \
  --function verify-truecaller --requests 100 --concurrency 100
~~~

This sends an empty JSON object, counts HTTP 429, rejects HTTP 5xx, and fails if invalid requests ever receive a 2xx. A pass requires at least one 429 by default. Set --expected-429-min 0 only for diagnostics when intentionally probing a staging function without an active limiter.

Interpretation caution: the current verify-truecaller source contains an in-memory per-isolate limiter. A 100-request burst that reaches multiple Edge isolates may not give a reliable global quota guarantee even when one isolate returns 429. No 429 result is a failure signal; a 429 result is a smoke-test signal, not proof of globally enforced quotas. Cross-isolate production guarantees need a shared/atomic limiter or gateway-level policy and a multi-isolate test.

## Acceptance gates

| Test | Gate |
|---|---|
| REST/PostgREST | 500 attempted, concurrency 500; zero transport/non-2xx/connection-exhaustion errors; p95 < 150 ms |
| Supavisor SQL pool | 500 attempts, concurrency 500 on port 6543; zero connection/query failures; p95 < 150 ms |
| Edge burst | 100 attempted concurrently; at least one 429, zero 5xx, zero unexpected 2xx to invalid payload |

A successful benchmark is valid only for the exact commit, Supabase project/region/plan, time window, schema/data size, and credentials tested. Save the JSON output securely with commit SHA and test conditions, but never save access tokens, database URLs, profile phone numbers, request bodies, or private response payloads in artifacts.

## 100,000-user scale qualification

Before making a 100k-user readiness claim, run a planned staging ramp and soak test with an explicit active-user/message/read distribution, for example a staged ramp from 50 to 100, 250, then 500 simultaneous clients followed by a sustained run. Extend only within current provider limits and budget. Observe Postgres CPU/IO and query latency, connection-pool waits, active/idle connections, lock waits, RLS/view plans, Realtime connections/egress, Edge Function cold starts and errors, Storage egress, push backlog, and p95/p99 latency. Validate hot queries with EXPLAIN (ANALYZE, BUFFERS) on representative staging data; avoid running expensive explain-analyze workloads against live production.

Provider quotas, suspension/egress limits and compute limits may change; check the current Supabase plan documentation/dashboard at the time of testing. This repository intentionally does not declare a 100k-user capacity without those measurements. Run benchmarks in a maintenance window, review status/error summaries, and stop if elevated 5xx, pool exhaustion, or production latency persists.
