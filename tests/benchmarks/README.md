# VibeSync 100k-user readiness benchmarks

These are opt-in, read-only / temporary-data checks. They intentionally fail closed until the operator opts in.

## Important: REST and Supavisor are different protocols

- `/rest/v1/hosted_public_directory` is PostgREST over HTTPS through the project Data API URL, normally port 443.
- Supavisor's transaction pool on port `6543` accepts PostgreSQL wire-protocol clients; it cannot receive an HTTP `/rest/v1` request.

Run both directory tests so the result separates the HTTPS API layer from the database pooler instead of confusing the endpoints. Supabase's connection docs confirm this split: https://supabase.com/docs/guides/database/connecting-to-postgres

## Prerequisites

- k6 for the two HTTP tests.
- Python 3.10+ and `pip install -r tests/benchmarks/requirements.txt` for the Supavisor/SQL tests.
- Use a staging project or an approved low-risk test window. The 500-client pool test creates up to 500 short-lived client connections; the API load test generates 500 concurrent virtual users.
- Copy the exact Supavisor **Transaction pooler** URI from Supabase Dashboard > Connect. Do not construct the pooler host manually. Keep credentials in environment variables and never commit them.

Example environment setup (use your actual values in a local shell, not in source control):

```bash
export SUPABASE_URL='https://YOUR_PROJECT_REF.supabase.co'
export SUPABASE_ANON_KEY='YOUR_PUBLISHABLE_OR_ANON_KEY'
export DATABASE_URL='postgresql://postgres.YOUR_PROJECT_REF:DB_PASSWORD@YOUR_POOLER_HOST:6543/postgres?sslmode=require'
export CONFIRM_LOAD_TEST=YES
```

## 1. HTTPS PostgREST directory stress (500 concurrent VUs)

```bash
k6 run tests/k6/supavisor_directory_stress.js
```

Default duration is 30s and default concurrency is 500. The test uses `GET /rest/v1/hosted_public_directory?select=entity_type,entity_id,name&limit=1`, checks every response is HTTP 200 and below 150ms, requires p95 below 150ms, and records zero connection-exhaustion errors. The projection intentionally avoids fetching every exposed directory column.

Relevant overrides: `CONCURRENT_USERS`, `DURATION`, `QUERY_LIMIT`, `REQUEST_TIMEOUT`, `DIRECTORY_SELECT`, and `SUPABASE_BEARER_TOKEN`.

## 2. Direct Supavisor transaction-pool stress (500 clients)

```bash
python3 tests/benchmarks/supavisor_pool_stress.py
```

The async test opens up to 500 client sessions through port 6543, runs a single read-only `SELECT` per task, and fails on any request failure, a connection-exhaustion error, or p95 latency at/above 150ms. It sets `statement_cache_size=0`, which is required for asyncpg when using transaction mode.

Relevant overrides: `CONCURRENCY` (default 500), `POOL_ACQUIRE_TIMEOUT_SECONDS` (default 10), and `POOL_QUERY` (must be one read-only SELECT). The default query is `SELECT entity_type, entity_id, name FROM public.hosted_public_directory LIMIT 1`.

## 3. Edge rate-limit burst (100 rapid requests)

```bash
k6 run tests/k6/verify_truecaller_burst.js
```

The test executes 100 sequential requests with one VU to reduce burst distribution across edge instances. Requests deliberately contain an empty object: accepted requests must stop with HTTP 400 before the function constructs a Supabase client, while rate-limited requests must return HTTP 429, `error=rate_limited`, and a positive `Retry-After` header. This avoids Truecaller calls and database writes during the test.

The Edge function currently stores buckets in an isolate-local in-memory `Map`; a burst routed across multiple isolates may see fewer 429 responses. The default acceptance check requires at least one 429, configurable using `MIN_429_RESPONSES`. Use staging and review isolate distribution before interpreting this as a globally shared quota guarantee. The 429 path is located before client creation / database access in `supabase/functions/verify-truecaller/index.ts`.

## 4. 100k-row B-tree and storage benchmark

```bash
export CONFIRM_BENCHMARK=YES
python3 tests/benchmarks/row_index_storage_benchmark.py
```

The script starts one explicit transaction, creates TEMP tables named `profiles` and `hosted_public_directory`, seeds 100,000 synthetic records in each using `generate_series`, creates B-tree indexes, runs `ANALYZE` and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`, and sums table + index + TOAST footprints. Both tables are `ON COMMIT DROP`, so no persistent tables or user rows are changed. Explicit transaction scope keeps the temporary objects on the same backend when port 6543 transaction pooling is used.

Pass criteria: each selective query must use a benchmark B-tree index and execute in under 10ms; combined relation size must remain below 250 MiB. Run under a representative staging load; cold-cache and free-tier contention can legitimately cause a measured failure. Optional overrides: `MAX_INDEX_EXECUTION_MS`, `MAX_STORAGE_MIB`. The row-count defaults to 100,000 and is never allowed below 100,000.

## 5. WebRTC signaling teardown unit test

```bash
./gradlew :app:testDebugUnitTest --tests com.example.util.WebRtcCallClientSignalingTeardownTest
```

The one-shot teardown gate is called synchronously by both WebRTC connected callbacks. It queues Phoenix `phx_leave` on the private Realtime topic before closing the WebSocket, and ignores late ICE-candidate callbacks after release. The unit test verifies immediate, idempotent unsubscribe/disconnect behavior.

## Interpretation

These scripts measure technical readiness signals only. Passing them does not establish capacity for 100,000 registered users by itself: peak active users, request rate, row-level-security complexity, cache hit rate, image/media storage, Edge isolate distribution, and Free Plan quotas remain separate capacity constraints. Save k6 output alongside database `EXPLAIN` output, and rerun after schema or Edge Function changes.
