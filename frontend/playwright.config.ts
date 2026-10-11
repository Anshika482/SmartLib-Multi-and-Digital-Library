import { defineConfig } from '@playwright/test';

/**
 * End-to-end journeys against the running stack (backend :8080, Vite :5173, Mailpit :8025, demo seed data).
 * Uses the system Microsoft Edge by default so no browser download is needed: E2E_CHANNEL=chrome or chromium to change.
 */
export default defineConfig({
  testDir: './e2e',
  // Generous limits: the dev server, browser, backend and MySQL often share one laptop CPU during a run.
  timeout: 180_000,
  expect: { timeout: 30_000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    channel: process.env.E2E_CHANNEL ?? 'msedge',
    headless: true,
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
