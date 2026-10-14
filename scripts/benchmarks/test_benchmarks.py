from __future__ import annotations

import unittest

from aiohttp import web

from edge_rate_limit_burst import run_burst
from supabase_rest_stress import percentile_ms as rest_percentile_ms, run_benchmark


class BenchmarkScriptsTests(unittest.IsolatedAsyncioTestCase):
    async def _server(self, handler):
        app = web.Application()
        app.router.add_route("*", "/{tail:.*}", handler)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, "127.0.0.1", 0)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        self.addAsyncCleanup(runner.cleanup)
        return f"http://127.0.0.1:{port}"

    async def test_rest_benchmark_passes_on_local_success_responses(self):
        async def handler(request):
            return web.json_response([{"profile_id": "mock"}], status=200)

        base_url = await self._server(handler)
        report = await run_benchmark(
            base_url, "rest/v1/hosted_public_directory?select=*&limit=1",
            "mock-publishable-key", "mock-access-token",
            requests=20, concurrency=10, timeout_seconds=2, max_p95_ms=1500,
        )
        self.assertTrue(report["passed"], report)
        self.assertEqual(report["requests"], 20)
        self.assertEqual(report["http_status_counts"].get("200"), 20)

    async def test_rest_benchmark_fails_closed_on_connection_exhaustion(self):
        async def handler(request):
            return web.json_response({"message": "sorry, too many clients already"}, status=503)

        base_url = await self._server(handler)
        report = await run_benchmark(
            base_url, "rest/v1/hosted_public_directory?select=*&limit=1",
            "mock-publishable-key", "mock-access-token",
            requests=12, concurrency=12, timeout_seconds=2, max_p95_ms=1500,
        )
        self.assertFalse(report["passed"])
        self.assertEqual(report["connection_exhaustion_count"], 12)

    async def test_edge_burst_requires_429_without_server_errors(self):
        counter = {"count": 0}

        async def handler(request):
            counter["count"] += 1
            if counter["count"] > 8:
                return web.json_response({"error": "rate_limited"}, status=429)
            return web.json_response({"error": "invalid_truecaller_oauth_request"}, status=400)

        base_url = await self._server(handler)
        report = await run_burst(
            base_url, "verify-truecaller", "mock-publishable-key", "mock-access-token",
            requests=20, concurrency=20, timeout_seconds=2, expected_429_min=1,
        )
        self.assertTrue(report["passed"], report)
        self.assertGreaterEqual(report["rate_limited_429_count"], 1)
        self.assertEqual(report["server_error_count"], 0)

    async def test_percentile_uses_nearest_rank(self):
        self.assertEqual(rest_percentile_ms([1, 2, 3, 4], 0.50), 2)
        self.assertEqual(rest_percentile_ms([1, 2, 3, 4], 0.95), 4)


if __name__ == "__main__":
    unittest.main()
