import { test, expect } from '@playwright/test';

/**
 * Smoke E2E suite — verifies the app serves, the login page renders, protected
 * pages require authentication, and public pages load. These run against the
 * booted application and gate deployment in CI.
 */

test.describe('Public + auth surfaces', () => {
  test('root redirects to the login page', async ({ page }) => {
    const resp = await page.goto('/');
    expect(resp?.status()).toBeLessThan(400);
    // Root forwards to /login.html — a password field should be present.
    await expect(page.locator('input[type="password"]')).toBeVisible();
  });

  test('login page exposes username + password fields', async ({ page }) => {
    await page.goto('/login.html');
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.locator('text=/log\\s*in/i').first()).toBeVisible();
  });

  test('temporary login page offers NTAG login', async ({ page }) => {
    await page.goto('/tempLogin');
    await expect(page.locator('#ntagStartBtn')).toBeVisible();
  });

  test('protected API rejects anonymous callers (401)', async ({ request }) => {
    const r = await request.get('/api/session');
    expect(r.status()).toBe(401);
  });

  test('protected page route redirects unauthenticated users away from the app', async ({ page }) => {
    await page.goto('/helpCenter');
    // Unauthenticated users are redirected to login (no help content rendered).
    await expect(page.locator('input[type="password"]')).toBeVisible();
  });
});
