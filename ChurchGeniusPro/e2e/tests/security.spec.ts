import { test, expect } from '@playwright/test';

/**
 * Security checks that run against the live app — complements the OWASP ZAP
 * baseline scan and the dependency vulnerability scan in CI.
 */

test('security response headers are present (CSP filter active)', async ({ request }) => {
  const r = await request.get('/login.html');
  expect(r.status()).toBe(200);
  const headers = r.headers();
  // CspFilter sets Content-Security-Policy on every response.
  expect(headers['content-security-policy'] || headers['content-security-policy-report-only']).toBeTruthy();
});

test('protected pages are not reachable without auth (no data leak)', async ({ request }) => {
  // Direct API access must be denied for anonymous users.
  for (const path of ['/api/session', '/api/help/articles', '/api/followups']) {
    const r = await request.get(path);
    expect(r.status(), `${path} should require auth`).toBe(401);
  }
});

test('directory traversal is not served', async ({ request }) => {
  const r = await request.get('/../../etc/passwd');
  expect(r.status()).toBeGreaterThanOrEqual(400);
});
