import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const rateLimitedResponses = new Counter('edge_rate_limited_429');
const malformedRateLimitResponses = new Counter('malformed_rate_limit_responses');
const unexpectedStatuses = new Counter('unexpected_edge_statuses');

if ((__ENV.CONFIRM_LOAD_TEST || '') !== 'YES') {
  throw new Error('Safety guard: set CONFIRM_LOAD_TEST=YES to issue the 100-request Edge burst.');
}
const SUPABASE_URL = (__ENV.SUPABASE_URL || '').replace(/\/+$/, '');
const API_KEY = __ENV.SUPABASE_ANON_KEY || '';
const BEARER = __ENV.SUPABASE_BEARER_TOKEN || '';
if (!SUPABASE_URL || !API_KEY) {
  throw new Error('Set SUPABASE_URL (bare project URL) and SUPABASE_ANON_KEY.');
}

const ITERATIONS = Math.max(1, Number(__ENV.BURST_REQUESTS || 100));
const MIN_429 = Math.max(1, Number(__ENV.MIN_429_RESPONSES || 1));
export const options = {
  scenarios: {
    single_client_burst: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: ITERATIONS,
      maxDuration: __ENV.MAX_DURATION || '60s',
    },
  },
  thresholds: {
    edge_rate_limited_429: ['count>=' + MIN_429],
    malformed_rate_limit_responses: ['count==0'],
    unexpected_edge_statuses: ['count==0'],
  },
};

export default function () {
  // Invalid payload: requests admitted by the rate gate stop with HTTP 400 before createClient().
  // Truecaller is never called and the benchmark writes no nonce/profile/session rows.
  const response = http.post(
    SUPABASE_URL + '/functions/v1/verify-truecaller',
    JSON.stringify({}),
    {
      headers: (() => {
        const headers = {
          apikey: API_KEY,
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'Cache-Control': 'no-store',
          'User-Agent': 'VibeSync-Edge-Rate-Limit-Burst/1.0',
        };
        if (BEARER) headers.Authorization = 'Bearer ' + BEARER;
        return headers;
      })(),
      timeout: '10s',
      tags: { endpoint: 'verify-truecaller', workload: 'rate-limit-burst' },
    },
  );

  if (response.status === 429) {
    rateLimitedResponses.add(1);
    let payload = {};
    try { payload = JSON.parse(response.body || '{}'); } catch (_) {}
    const retryAfter = Number(response.headers['Retry-After'] || response.headers['retry-after'] || 0);
    const wellFormed = payload.error === 'rate_limited' && retryAfter > 0;
    if (!wellFormed) malformedRateLimitResponses.add(1);
    check(response, {
      '429 response is rate_limited JSON with Retry-After': () => wellFormed,
    });
  } else {
    if (response.status !== 400) unexpectedStatuses.add(1);
    check(response, {
      'non-limited request stops at invalid payload (HTTP 400)': (r) => r.status === 400,
    });
  }
}
