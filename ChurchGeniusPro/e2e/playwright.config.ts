import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E configuration. Targets the running Spring Boot app (default
 * http://localhost:8080). In CI the app is started before this runs; locally,
 * start it with `./mvnw spring-boot:run` (or set BASE_URL to a deployed env).
 */
const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 7_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }], ['junit', { outputFile: 'results/e2e-junit.xml' }]],
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-chrome', use: { ...devices['Pixel 7'] } },
  ],
  // Optional: let Playwright boot the app itself when RUN_APP=1 and a built jar exists.
  webServer: process.env.RUN_APP
    ? {
        command: 'java -jar ../target/ChurchGeniusPro-0.0.1-SNAPSHOT.jar --spring.profiles.active=local',
        url: BASE_URL + '/login.html',
        timeout: 120_000,
        reuseExistingServer: !process.env.CI,
      }
    : undefined,
});
