import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * k6 load test for ChurchGenius Pro. Drives the public login surface and a
 * protected API under concurrent load and asserts latency + correctness
 * thresholds. The thresholds below are the PERFORMANCE GATE: if any is breached,
 * k6 exits non-zero and CI blocks the production promotion.
 *
 * Run:  k6 run -e BASE_URL=http://localhost:8080 load/smoke.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const errorRate = new Rate('app_errors');
const loginLatency = new Trend('login_latency', true);

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: 20 },  // ramp up
        { duration: '40s', target: 20 },  // sustain
        { duration: '10s', target: 0 },   // ramp down
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // ── Production promotion gate ──
    http_req_duration: ['p(95)<800', 'p(99)<1500'],  // 95% under 800ms
    http_req_failed: ['rate<0.01'],                   // <1% transport failures
    app_errors: ['rate<0.01'],                        // <1% unexpected responses
    checks: ['rate>0.99'],
  },
};

export default function () {
  // 1) Public login page must serve fast and return 200.
  const login = http.get(`${BASE_URL}/login.html`);
  loginLatency.add(login.timings.duration);
  const okLogin = check(login, {
    'login 200': (r) => r.status === 200,
    'login has form': (r) => typeof r.body === 'string' && r.body.includes('password'),
  });
  errorRate.add(!okLogin);

  // 2) Protected API must reject anonymous callers with 401 (auth filter active).
  const session = http.get(`${BASE_URL}/api/session`);
  const okSession = check(session, { 'session 401 when anonymous': (r) => r.status === 401 });
  errorRate.add(!okSession);

  sleep(1);
}
