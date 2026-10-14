import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const exhaustionErrors = new Counter('connection_exhaustion_errors');
const unexpectedStatuses = new Counter('unexpected_statuses');

if ((__ENV.CONFIRM_LOAD_TEST || '') !== 'YES') {
  throw new Error('Safety guard: set CONFIRM_LOAD_TEST=YES to run the 500-VU REST stress test.');
}
const SUPABASE_URL = (__ENV.SUPABASE_URL || '').replace(/\/+$/, '');
const API_KEY = __ENV.SUPABASE_ANON_KEY || '';
const BEARER = __ENV.SUPABASE_BEARER_TOKEN || '';
if (!SUPABASE_URL || !API_KEY) {
  throw new Error('Set SUPABASE_URL (bare project URL) and SUPABASE_ANON_KEY.');
}

const SELECT = __ENV.DIRECTORY_SELECT || 'entity_type,entity_id,name';
const LIMIT = Math.max(1, Number(__ENV.QUERY_LIMIT || 1));
const TARGET = SUPABASE_URL + '/rest/v1/hosted_public_directory?select=' +
  encodeURIComponent(SELECT) + '&limit=' + LIMIT;
const VUS = Math.max(1, Number(__ENV.CONCURRENT_USERS || 500));
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    hosted_public_directory: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<150'],
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    connection_exhaustion_errors: ['count==0'],
    unexpected_statuses: ['count==0'],
  },
};

export default function () {
  const response = http.get(TARGET, {
    headers: (() => {
      const headers = {
        apikey: API_KEY,
        Accept: 'application/json',
        'Cache-Control': 'no-store',
        'User-Agent': 'VibeSync-100k-readiness-k6/1.0',
      };
      if (BEARER) headers.Authorization = 'Bearer ' + BEARER;
      return headers;
    })(),
    timeout: __ENV.REQUEST_TIMEOUT || '10s',
    tags: { endpoint: 'hosted_public_directory', workload: '500-vu' },
  });

  const body = String(response.body || '').toLowerCase();
  const exhausted =
    body.includes('sorry, too many clients already') ||
    body.includes('too many connections') ||
    body.includes('remaining connection slots are reserved') ||
    body.includes('connection exhaustion');
  if (exhausted) exhaustionErrors.add(1);
  if (response.status !== 200) unexpectedStatuses.add(1);

  check(response, {
    'PostgREST returns HTTP 200': (r) => r.status === 200,
    'no database connection exhaustion text': () => !exhausted,
    'individual response latency < 150ms': (r) => r.timings.duration < 150,
  });
}
