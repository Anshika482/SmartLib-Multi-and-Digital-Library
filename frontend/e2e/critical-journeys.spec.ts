import { expect, test } from '@playwright/test';
import { DEMO_PASSWORD, NEW_PASSWORD, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, apiToken, libraryIds, login, loginAndLand, otpFor, unique, visibleLibraries } from './helpers';

test.describe.configure({ mode: 'serial' });
test.skip(!DEMO_PASSWORD, 'DEMO_USER_PASSWORD is required (demo seed accounts)');

test('§79 search spans all libraries; the library filter only restricts; clearing restores everything', async ({ page }) => {
  await page.goto('/catalogue');
  const total = page.getByText(/books? in/).first();
  await expect(total).toContainText('all libraries');
  const allBooks = await page.locator('p[aria-live="polite"] b').first().textContent();

  await page.getByLabel('Search by title, author or ISBN').fill('java');
  await expect(page).toHaveURL(/q=java/);
  await expect(page.getByText('for “java” in all libraries')).toBeVisible();
  expect((await visibleLibraries(page)).size).toBeGreaterThanOrEqual(3);

  await page.getByRole('combobox', { name: 'Library' }).selectOption({ label: 'Riverside Library' });
  await expect(page.getByText('for “java” in Riverside Library')).toBeVisible();
  expect([...(await visibleLibraries(page))]).toEqual(['Riverside Library']);
  await expect(page).toHaveURL(/q=java/);

  await page.getByRole('combobox', { name: 'Library' }).selectOption({ label: 'All Libraries' });
  await expect(page.getByText('for “java” in all libraries')).toBeVisible();
  expect((await visibleLibraries(page)).size).toBeGreaterThanOrEqual(3);

  await page.getByRole('button', { name: 'Clear search' }).click();
  await expect(page).toHaveURL(/\/catalogue$/);
  await expect(page.locator('p[aria-live="polite"] b').first()).toHaveText(allBooks ?? '');

  // deep link + refresh keep state
  await page.goto('/catalogue?q=java&library=1');
  await page.reload();
  await expect(page.getByLabel('Search by title, author or ISBN')).toHaveValue('java');
});

test('§78/§80 register -> verify (OTP) -> request from the chosen library -> only that library sees it -> approve -> issue -> return', async ({ page, browser, request }) => {
  const email = unique('member');
  const name = `E2E Reader ${Date.now()}`;
  // Registration + email verification
  await page.goto('/register');
  await page.getByLabel('Full name').fill(name);
  await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await page.locator('#password').fill(NEW_PASSWORD);
  await page.locator('#confirmPassword').fill(NEW_PASSWORD);
  await page.getByText('I agree to the SmartLib Terms').click();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/verify-email/);
  // unverified login is refused
  const otp = await otpFor(request, email);
  await page.getByLabel('Digit 1').click();
  await page.keyboard.type(otp, { delay: 30 });
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByRole('heading', { name: 'Email verified!' })).toBeVisible();
  await page.getByRole('link', { name: 'Continue to login' }).click();
  await page.locator('#password').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page).toHaveURL(/\/app\/member/);

  // Explore -> book details -> compare libraries -> choose Riverside
  await page.goto('/catalogue?q=clean%20code');
  await page.getByRole('link', { name: /Clean Code — view details/ }).first().click();
  await expect(page.getByRole('heading', { name: 'Clean Code', level: 1 })).toBeVisible();
  const westEnd = page.locator('li', { hasText: 'West End Library' }).filter({ hasText: 'Loan' });
  await expect(westEnd.getByRole('button', { name: 'Unavailable' })).toBeDisabled();
  await page.getByRole('button', { name: 'Request from Riverside Library' }).click();
  await expect(page.getByRole('dialog')).toContainText('From Riverside Library');
  await page.getByRole('button', { name: 'Confirm request' }).click();
  await expect(page.getByRole('heading', { name: 'Request sent' })).toBeVisible();
  await expect(page.getByRole('dialog')).toContainText('Awaiting approval');

  // Central's librarian must NOT receive it
  const central = await browser.newPage();
  await loginAndLand(central, 'librarian.central@smartlib.demo', /\/app\/librarian/);
  await central.goto('/app/librarian/requests?status=ALL');
  await central.getByLabel('Search requests').fill(name);
  await expect(central.getByText('No requests found')).toBeVisible();
  await central.close();

  // Riverside's librarian receives it, approves and issues
  const riverside = await browser.newPage();
  await loginAndLand(riverside, 'librarian.riverside@smartlib.demo', /\/app\/librarian/);
  await riverside.goto('/app/librarian/requests');
  const row = riverside.locator('tr', { hasText: name });
  await row.getByRole('button', { name: 'Approve' }).click();
  await riverside.getByRole('button', { name: 'Approve & reserve copy' }).click();
  await riverside.goto('/app/librarian/requests?status=APPROVED');
  await riverside.locator('tr', { hasText: name }).getByRole('button', { name: 'Issue' }).click();
  await riverside.getByRole('button', { name: 'Issue book' }).click();
  await expect(riverside.getByText(/Issued — due/)).toBeVisible();

  // Member sees the loan from the right library
  await page.goto('/app/member/borrowings');
  await expect(page.getByText('Riverside Library').first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'Clean Code' })).toBeVisible();

  // Return (on time -> no fine), restoring the copy for the next run
  await riverside.goto('/app/librarian/borrowings');
  await riverside.locator('tr', { hasText: name }).getByRole('button', { name: 'Process return' }).click();
  await riverside.getByRole('button', { name: 'Record return' }).click();
  await expect(riverside.getByText('Return recorded. No fine due.')).toBeVisible();
  await riverside.close();
  await page.goto('/app/member/notifications');
  await expect(page.getByText('Return recorded: Clean Code')).toBeVisible();
});

test('§81 librarian: verify email, wait for approval, get approved, access only the assigned library', async ({ page, browser, request }) => {
  const email = unique('librarian');
  await page.goto('/register/librarian');
  await page.getByLabel('Full name').fill('E2E Librarian');
  await page.getByLabel('Work email').fill(email);
  await page.getByRole('combobox', { name: 'Library', exact: true }).selectOption({ label: 'Central Library — Pune' });
  await page.locator('#password').fill(NEW_PASSWORD);
  await page.locator('#confirmPassword').fill(NEW_PASSWORD);
  await page.getByText('I confirm I work at the selected library').click();
  await page.getByRole('button', { name: 'Submit application' }).click();
  await expect(page).toHaveURL(/\/verify-email/);

  await login(page, email, NEW_PASSWORD);
  await expect(page.getByText('Your email is not verified')).toBeVisible();

  await page.goto(`/verify-email?email=${encodeURIComponent(email)}`);
  const code = await otpFor(request, email);
  await page.getByLabel('Digit 1').click();
  await page.keyboard.type(code, { delay: 30 });
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByText('Administrator approval')).toBeVisible();

  await login(page, email, NEW_PASSWORD);
  await expect(page.getByRole('heading', { name: 'Waiting for approval' })).toBeVisible();

  const admin = await browser.newPage();
  await loginAndLand(admin, 'admin.central@smartlib.demo', /\/app\/admin/);
  await admin.goto('/app/admin/librarians');
  await admin.locator('tr', { hasText: email }).getByRole('button', { name: 'Approve' }).click();
  await admin.getByRole('button', { name: 'Approve librarian' }).click();
  await expect(admin.getByText('Librarian approved')).toBeVisible();
  await admin.close();

  await loginAndLand(page, email, /\/app\/librarian/, NEW_PASSWORD);
  await expect(page.getByRole('heading', { name: 'Central Library' })).toBeVisible();
  await page.goto('/app/admin');
  await expect(page.getByText("You don't have access to this area")).toBeVisible();

  // Backend isolation: this librarian's token cannot read another library's operations
  const ids = await libraryIds(request);
  const token = await apiToken(request, email, NEW_PASSWORD);
  const own = await request.get(`/api/manage/libraries/${ids['Central Library']}/requests`, { headers: { Authorization: `Bearer ${token}` } });
  const other = await request.get(`/api/manage/libraries/${ids['West End Library']}/requests`, { headers: { Authorization: `Bearer ${token}` } });
  expect(own.status()).toBe(200);
  expect(other.status()).toBe(403);
});

test('admin applications need email verification and approval by the single Super Admin', async ({ page, browser, request }) => {
  test.skip(!SUPER_ADMIN_EMAIL || !SUPER_ADMIN_PASSWORD, 'BOOTSTRAP_SUPER_ADMIN_* is required');
  const adminEmail = unique('admin');
  await page.goto('/register');
  await page.getByRole('navigation', { name: 'Account type' }).getByRole('link', { name: 'Admin', exact: true }).click();
  await expect(page).toHaveURL(/\/register\/admin$/);
  await page.getByRole('button', { name: 'Submit application' }).click();
  await expect(page.getByText('Phone number is required')).toBeVisible();
  await expect(page.getByText('Please choose the library you will administer')).toBeVisible();

  await page.getByLabel('Full name').fill('E2E Admin');
  await page.getByLabel('Work email').fill(adminEmail);
  await page.getByLabel('Phone').fill('+91 98765 43210');
  await page.getByRole('combobox', { name: 'Library', exact: true }).selectOption({ label: 'Riverside Library — Bengaluru' });
  await page.getByLabel('Designation').fill('Branch Manager');
  await page.getByLabel(/Why do you need admin access/).fill('I manage the Riverside branch and need to approve its librarians.');
  await page.locator('#password').fill(NEW_PASSWORD);
  await page.locator('#confirmPassword').fill(NEW_PASSWORD);
  await page.getByText('I confirm I am authorised by the selected library').click();
  await page.getByText('I agree to the SmartLib Terms').click();
  await page.getByRole('button', { name: 'Submit application' }).click();
  await expect(page).toHaveURL(/\/verify-email/);
  const adminCode = await otpFor(request, adminEmail);
  await page.getByLabel('Digit 1').click();
  await page.keyboard.type(adminCode, { delay: 30 });
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByText('Your admin application is now with a Super Admin for review.')).toBeVisible();

  await login(page, adminEmail, NEW_PASSWORD);
  await expect(page.getByRole('heading', { name: 'Waiting for approval' })).toBeVisible();
  await expect(page.getByText('Super Admin review')).toBeVisible();

  // Only a Super Admin can see and decide admin applications
  const riversideAdmin = await apiToken(request, 'admin.riverside@smartlib.demo');
  expect((await request.get('/api/platform/applications', { headers: { Authorization: `Bearer ${riversideAdmin}` } })).status()).toBe(403);

  const boss = await browser.newPage();
  await loginAndLand(boss, SUPER_ADMIN_EMAIL, /\/app\/super-admin/, SUPER_ADMIN_PASSWORD);
  await boss.goto('/app/super-admin/applications');
  await boss.getByLabel('Search applications').fill(adminEmail);
  await boss.locator('tr', { hasText: adminEmail }).getByRole('button', { name: 'Approve' }).click();
  await boss.getByRole('button', { name: 'Approve admin' }).click();
  await expect(boss.getByText('Admin approved')).toBeVisible();

  await loginAndLand(page, adminEmail, /\/app\/admin/, NEW_PASSWORD);
  await expect(page.getByRole('heading', { name: 'Riverside Library' })).toBeVisible();

  // Keep the demo data tidy: revoke the test admin again
  const bossToken = await apiToken(request, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD);
  const approved = await (await request.get(`/api/platform/applications?status=APPROVED&q=${encodeURIComponent(adminEmail)}`,
    { headers: { Authorization: `Bearer ${bossToken}` } })).json();
  const ids = await libraryIds(request);
  expect((await request.post(`/api/manage/libraries/${ids['Riverside Library']}/staff/${approved.content[0].userId}/revoke`,
    { headers: { Authorization: `Bearer ${bossToken}` } })).status()).toBe(204);
  await boss.close();
});

test('login page demo accounts sign in to every role', async ({ page, request }) => {
  const demo = await (await request.get('/api/public/demo-accounts')).json();
  test.skip(!demo.enabled, 'DEMO_LOGIN_ENABLED is off');
  const homes: Record<string, RegExp> = { Member: /\/app\/member/, Librarian: /\/app\/librarian/, Admin: /\/app\/admin/ };
  await page.goto('/login');
  await expect(page.getByRole('button', { name: /^Super Admin/ })).toHaveCount(0); // the real Super Admin is never offered
  for (const [label, home] of Object.entries(homes)) {
    await page.goto('/login');
    await page.getByRole('button', { name: new RegExp(`^${label}`) }).click();
    await page.getByRole('button', { name: 'Log in' }).click();
    await expect(page).toHaveURL(home);
    await page.context().clearCookies(); // sign out for the next role
  }
});

test('fines are shown and payments are only completed by server verification', async ({ page, request }) => {
  await loginAndLand(page, 'member@smartlib.demo', /\/app\/member/);
  await page.goto('/app/member/fines');
  const payButton = page.getByRole('button', { name: 'Pay now' }).first();
  await expect(payButton).toBeVisible();
  const token = await apiToken(request, 'member@smartlib.demo');
  const fines = await (await request.get('/api/me/fines?status=UNPAID', { headers: { Authorization: `Bearer ${token}` } })).json();
  const probe = await request.post(`/api/me/fines/${fines[0].id}/payment-order`, { headers: { Authorization: `Bearer ${token}` } });
  if (probe.status() === 503) {
    // Razorpay keys not configured: the UI explains it clearly and nothing is marked paid
    await payButton.click();
    await expect(page.getByText('Online payments are not configured yet')).toBeVisible();
  } else if ((await probe.json()).provider === 'DEMO') {
    // Built-in demo gateway: a declined payment leaves the fine unpaid (keeps the demo fine for the next run)
    await payButton.click();
    const checkout = page.getByRole('dialog');
    await expect(checkout).toContainText('Demo mode');
    await checkout.getByRole('button', { name: 'Simulate a failed payment' }).click();
    await expect(page.getByText('Payment not completed')).toBeVisible();
    await expect(page.getByText('declined by the bank')).toBeVisible();
    await expect(page.getByRole('button', { name: /Retry payment|Pay now/ }).first()).toBeVisible();
  } else {
    expect(probe.ok()).toBeTruthy();
    expect((await probe.json()).keyId).toMatch(/^rzp_/);
  }
  // A forged signature can never mark a fine paid
  const forged = await request.post('/api/payments/razorpay/verify', { headers: { Authorization: `Bearer ${token}` },
    data: { razorpayOrderId: 'order_fake', razorpayPaymentId: 'pay_fake', razorpaySignature: 'deadbeef' } });
  expect([400, 404]).toContain(forged.status());
});

test('AI assistant shows a clear state and never hangs', async ({ page, request }) => {
  const status = await (await request.get('/api/public/ai/status')).json();
  await page.goto('/assistant');
  if (!status.configured || !status.enabled) {
    await expect(page.getByText(/Not configured yet|Turned off by the administrator/)).toBeVisible();
    await expect(page.getByLabel('Message the SmartLib assistant')).toBeDisabled();
    return;
  }
  await page.getByLabel('Message the SmartLib assistant').fill('Which libraries have Clean Code?');
  await page.keyboard.press('Enter');
  await expect(page.getByText('Which libraries have Clean Code?')).toBeVisible();
  await expect(page.getByLabel('Books from the SmartLib catalogue').or(page.getByRole('alert'))).toBeVisible({ timeout: 60_000 });
});
