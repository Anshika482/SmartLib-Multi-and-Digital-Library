import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

const here = path.dirname(fileURLToPath(import.meta.url));
const envFile = path.resolve(here, '../../.env');
const env: Record<string, string> = fs.existsSync(envFile)
  ? Object.fromEntries(fs.readFileSync(envFile, 'utf8').split(/\r?\n/).filter((l) => l.includes('=') && !l.startsWith('#'))
    .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]))
  : {};

/** Password of the seeded *.smartlib.demo accounts (DEMO_USER_PASSWORD in .env). */
export const DEMO_PASSWORD = process.env.DEMO_USER_PASSWORD ?? env.DEMO_USER_PASSWORD ?? '';
export const NEW_PASSWORD = 'E2e-Passw0rd!';
/** The single Super Admin configured in .env (BOOTSTRAP_SUPER_ADMIN_*). */
export const SUPER_ADMIN_EMAIL = (process.env.BOOTSTRAP_SUPER_ADMIN_EMAIL ?? env.BOOTSTRAP_SUPER_ADMIN_EMAIL ?? '').trim();
export const SUPER_ADMIN_PASSWORD = (process.env.BOOTSTRAP_SUPER_ADMIN_PASSWORD ?? env.BOOTSTRAP_SUPER_ADMIN_PASSWORD ?? '').trim();
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';

export const unique = (prefix: string) => `${prefix}.${Date.now()}.${Math.floor(Math.random() * 1000)}@e2e.smartlib.test`;

/** Reads the newest 6-digit SmartLib code sent to `email` from Mailpit's API. */
export async function otpFor(request: APIRequestContext, email: string): Promise<string> {
  for (let i = 0; i < 30; i++) {
    const res = await request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`);
    if (res.ok()) {
      const data = await res.json();
      const id = data.messages?.[0]?.ID;
      if (id) {
        const msg = await (await request.get(`${MAILPIT}/api/v1/message/${id}`)).json();
        const m = /Your verification code: (\d{6})/.exec(msg.Text ?? '');
        if (m) return m[1];
      }
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`No verification email for ${email} in Mailpit`);
}

export async function login(page: Page, email: string, password = DEMO_PASSWORD) {
  await page.goto('/login');
  await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
}

export async function loginAndLand(page: Page, email: string, path: RegExp, password = DEMO_PASSWORD) {
  await login(page, email, password);
  await expect(page).toHaveURL(path);
}

export async function apiToken(request: APIRequestContext, email: string, password = DEMO_PASSWORD) {
  const res = await request.post('/api/auth/login', { data: { email, password } });
  expect(res.ok()).toBeTruthy();
  return (await res.json()).accessToken as string;
}

export async function libraryIds(request: APIRequestContext): Promise<Record<string, number>> {
  const res = await request.get('/api/public/libraries');
  const list = (await res.json()) as { id: number; name: string }[];
  return Object.fromEntries(list.map((l) => [l.name, l.id]));
}

/** Library names visible in the availability strips of the current result page. */
export async function visibleLibraries(page: Page) {
  const items = await page.getByRole('list', { name: 'Availability by library' }).getByRole('listitem').allTextContents();
  return new Set(items.map((t) => t.match(/^(.*? Library)/)?.[1]).filter(Boolean));
}
