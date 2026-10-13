<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="frontend/public/brand/smartlib-logo-dark.png">
    <img src="frontend/public/brand/smartlib-logo.png" alt="SmartLib, Intelligence in Every Chapter" width="420">
  </picture>
</p>

<h3 align="center">Multi-Library Management & Digital Learning Platform</h3>

<p align="center">
  Discover books across many libraries, borrow from the one you choose, and ask an AI librarian anything.
</p>

<p align="center">
  <a href="https://smartlib-xi89.onrender.com"><strong>🔗 Live Demo</strong></a>
  ·
  <a href="docs/ARCHITECTURE.md">Architecture</a>
  ·
  <a href="#3-running-locally">Run locally</a>
  ·
  <a href="#6-deployment">Deployment</a>
</p>

<p align="center">
  <a href="https://github.com/Anshika482/SmartLib-Multi-and-Digital-Library/actions/workflows/ci.yml"><img src="https://github.com/Anshika482/SmartLib-Multi-and-Digital-Library/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1">
  <img src="https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black" alt="React 19">
  <img src="https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white" alt="MySQL 8">
  <img src="https://img.shields.io/badge/Gemini-AI-8E75B2?logo=googlegemini&logoColor=white" alt="Google Gemini">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT License">
</p>

---

## 🚀 Live demo

**<https://smartlib-xi89.onrender.com>**

- **Try it without signing up.** On the login page, open **"Use a demo account"** and pick **Member**, **Librarian** or **Admin**. The form fills itself in.
- **First load can be slow.** The demo runs on Render's free plan, which puts the server to sleep after 15 minutes without visitors. The first visit after that can take a minute or more while it wakes up; after that it is fast.
- **It is demo data.** Libraries, books, loans and fines are seeded for demonstration and may be reset at any time. Payments use a built-in demo gateway, so no real money moves.

**A good path to try:** search for *Clean Code*, compare its availability across libraries on the book page, request it from **Riverside**, then sign in as the Librarian to approve and issue it. Other libraries' staff never see the request.

## ✨ What SmartLib does

SmartLib is not a single-library CRUD app. It is one platform hosting many independent libraries, built around a simple rule: **exploring is open to everyone, borrowing is always tied to one chosen library.**

| | Feature | Highlights |
|---|---|---|
| 📚 | **Cross-library catalogue** | Search by title, author or ISBN across every library at once, or filter to one. Server-side search and pagination, with state kept in the URL. |
| 🏛️ | **Per-library availability** | One book, many libraries, separate copy counts. Pick a library on the book page and request from it. |
| 🖼️ | **ISBN covers** | Local upload, then Open Library, then Google Books, then a generated placeholder. Never a broken image. |
| 🔐 | **Secure accounts** | Email verification by 6-digit OTP *or* link, JWT access tokens, rotating refresh cookies, rate limits and password reset. |
| 👥 | **Four roles** | Member, Librarian, Admin and a single Super Admin, each with its own dashboard. Librarian and Admin accounts need approval. |
| 🧱 | **Strict library isolation** | A librarian of one library can never read another library's requests, loans, fines or members. Enforced on the server and checked by an ArchUnit rule. |
| 🔄 | **Full circulation** | Request → approve/reject → issue → return, with due dates, reminders, overdue tracking and automatic fines. |
| 💳 | **Fine payments** | Razorpay (test mode) or a built-in demo gateway, both verified on the server before a fine is marked paid. |
| 📂 | **Digital resources** | PDF, EPUB, video and links, with access control. |
| 🤖 | **AI librarian (Google Gemini)** | Streams answers, talks about anything, and uses function calling to look up the *real* catalogue, your loans and your fines. It never invents a book. |
| 🔔 | **Notifications** | In-app notifications plus HTML emails for approvals, due dates, fines and payments. |
| 📊 | **Reports and audit** | Library and platform analytics from live data, and an audit log that never stores secrets. |

## 🧰 Tech stack

| Part | Stack |
|---|---|
| Backend | Java 21 bytecode, Spring Boot 4.1, Spring Security 7 (JWT), Spring Data JPA + JdbcTemplate, Flyway, MySQL 8 |
| Frontend | React 19, TypeScript, Vite 6, React Router 7, TanStack Query 5, Zustand, React Hook Form + Zod, Tailwind CSS 4 |
| Integrations | Google Gemini (REST streaming + function calling), Razorpay (Orders API + signature/webhook verification), Open Library / Google Books (covers), SMTP |
| Tests | JUnit 5, Spring Boot Test, MockMvc, ArchUnit; Vitest, Testing Library, MSW; Playwright |
| Hosting (demo) | Docker on Render, MySQL on Aiven, email through Brevo SMTP |

The design and the reasoning behind it are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 or newer | Compiled with `--release 21`. Built and tested on JDK 24; an LTS JDK (21 or 25) is recommended. Maven is not needed because the wrapper `mvnw` downloads it. |
| Node.js | 20.18 or newer | 22 LTS is recommended (Node 20 is end-of-life). |
| MySQL | 8.0 or newer | Tested on 8.0.43. |
| Mailpit | any | Catches development email (OTP codes, links, notifications). Download it from [mailpit.axllent.org](https://mailpit.axllent.org). |
| Microsoft Edge or Chrome | current | Only needed for the Playwright end-to-end tests. |

## 2. One-time setup

### Database

Run this as a MySQL administrator, choosing your own password:

```sql
CREATE DATABASE IF NOT EXISTS smartlib      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS smartlib_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'smartlib'@'localhost' IDENTIFIED BY '<choose-a-password>';
GRANT ALL PRIVILEGES ON smartlib.*      TO 'smartlib'@'localhost';
GRANT ALL PRIVILEGES ON smartlib_test.* TO 'smartlib'@'localhost';
```

On first start, Flyway creates the schema (`backend/src/main/resources/db/migration`).

> `smartlib_test` belongs to the automated tests, which **wipe and re-create it** on every run. Never point `SMARTLIB_TEST_DB_URL` at real data.

### Environment file

Copy `.env.example` to `.env` in the project root and fill it in. The backend reads `../.env` automatically. **Never commit `.env`**: it is listed in `.gitignore`.

| Variable | What to put |
|---|---|
| `SMARTLIB_DB_PASSWORD` | The password you chose above |
| `JWT_SECRET` | At least 32 random bytes, base64 encoded (command below) |
| `APP_TOKEN_PEPPER` | Another random string. It keys the HMAC used to store one-time codes. |
| `DEMO_USER_PASSWORD` | The shared password for the demo accounts, e.g. a 16+ character random string |
| `GEMINI_API_KEY` | Optional. See [Optional integrations](#4-optional-integrations). |
| `RAZORPAY_*` | Optional. See [Optional integrations](#4-optional-integrations). |

To generate a secret:

```bash
python -c "import secrets,base64;print(base64.b64encode(secrets.token_bytes(48)).decode())"
```

The database variables carry a `SMARTLIB_` prefix on purpose: a machine-wide `DB_PASSWORD` from another project can't silently override them.

## 3. Running locally

Start each of the three processes in its own terminal:

```bash
# 1. Email catcher. Inbox at http://localhost:8025
mailpit --smtp 127.0.0.1:1025 --listen 127.0.0.1:8025

# 2. Backend at http://localhost:8080 (health: /actuator/health)
cd backend
./mvnw spring-boot:run          # Windows PowerShell: .\mvnw.cmd spring-boot:run

# 3. Frontend at http://localhost:5173 (proxies /api to :8080)
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>. On a slower laptop the backend needs about 30 seconds to start.

**On Windows, the easy way:** double-click **`start-smartlib.bat`** in the project folder. It opens the backend, the website and Mailpit in their own windows and opens the browser when everything is ready. Close those windows to stop SmartLib.

### Demo data

When `SEED_DEMO_DATA=true` and the database has no libraries yet, the backend seeds clearly marked demo data:

- 5 libraries: Central (Pune), West End (Mumbai), Riverside (Bengaluru), Innovation (Hyderabad) and Eastwood (New Delhi).
- 63 real books with real ISBNs, spread across the libraries with different copy counts.
- Requests, active and overdue loans, unpaid fines, digital resources and notifications, so every dashboard has real data to show.

Every demo account below signs in with the `DEMO_USER_PASSWORD` from your `.env`.

**One-click demo logins:** with `DEMO_LOGIN_ENABLED=true` in `.env`, the login page shows a **"Use a demo account"** panel. Its **Member, Librarian and Admin** buttons fill in the email and password, so anyone can explore the project. The password is only sent by the backend while this switch is on; it is never written into the frontend code. **Set `DEMO_LOGIN_ENABLED=false` for production**, and the panel disappears.

The demo accounts:

| Role | Email | What it shows |
|---|---|---|
| Super Admin | The account in `BOOTSTRAP_SUPER_ADMIN_EMAIL` / `_PASSWORD` (your `.env`) | **The only Super Admin**: all libraries, users, settings, global reports and audit. It is not a demo account and never appears in the demo panel. |
| Admin | `admin.central@smartlib.demo`, `admin.westend@…`, `admin.riverside@…` | One library: staff approval, fine waivers, reports, settings |
| Librarian | `librarian.central@smartlib.demo`, `librarian.westend@…`, `librarian.riverside@…`, `librarian.innovation@…`, `librarian.eastwood@…` | One library: requests, issue/return, books, copies, resources |
| Librarian (pending) | `pending.librarian@smartlib.demo` | Login shows "Waiting for approval" |
| Librarian (rejected) | `rejected.librarian@smartlib.demo` | Login shows the rejection state |
| Member | `member@smartlib.demo` (Ananya Iyer) | Requests, loans, overdue fines, payments, resources |
| Members | `aarav.sharma@…`, `priya.singh@…`, `rohan.mehta@…`, `sneha.patel@…` | Extra members, so staff dashboards and reports have data |

**A good demo path is *Clean Code*:**
- Central has 2 copies, one of them on loan.
- West End's only copy is on loan, so it shows **Unavailable**.
- Riverside has one available copy.

Search for it, compare the libraries on the book page, request it from Riverside, then sign in as `librarian.riverside@smartlib.demo` to approve and issue it. Central's librarian never sees the request.

To re-seed from scratch, drop and re-create the `smartlib` database and restart the backend.

### Registering new accounts

The registration page has three tabs: **Member**, **Librarian** and **Admin** (`/register`, `/register/librarian`, `/register/admin`). Each new account receives a 6-digit code and a verification link. Both appear in Mailpit, or in the real inbox when Gmail is configured. There is no Super Admin registration; see [The single Super Admin](#the-single-super-admin).

Only members can use their account straight after verifying their email. The other three roles are **applications**:

| Role | Extra required fields | Who approves |
|---|---|---|
| Librarian | Library | That library's Admin (or a Super Admin if the library has no Admin) |
| Admin | Phone, library, designation, reason (20+ characters), authority confirmation | The Super Admin only, never the library's own Admin |

Until an application is approved, the applicant cannot sign in and sees a "Waiting for approval" screen. A rejected applicant sees the reason. The Super Admin reviews Admin applications under **Admin Applications** in their dashboard, and can switch Librarian and Admin applications on or off under **Configuration**.

### The single Super Admin

SmartLib has exactly **one Super Admin**: the account set in `.env` with `BOOTSTRAP_SUPER_ADMIN_EMAIL` and `BOOTSTRAP_SUPER_ADMIN_PASSWORD` (and optionally `BOOTSTRAP_SUPER_ADMIN_NAME`). On every start, the backend does the following:

- **Creates or converts the account.** If no account uses that email, it creates one; if one exists (for example a member account), it converts it into the Super Admin.
- **Makes it usable.** The account is made active and verified, and its password is set to the one in `.env`. To change the Super Admin password, change it in `.env` and restart. Spaces at the start or end are ignored.
- **Disables every other Super Admin account**, including the seeded `superadmin@smartlib.demo`. Those accounts cannot log in, cannot be re-activated from the Users page, and no Super Admin application can be approved.

If the password in `.env` breaks the password rules (at least 8 characters with a letter and a number, not common, and not containing the email name), the backend changes nothing and logs the reason.

For a deployment without demo data, also set `SEED_DEMO_DATA=false`.

## 4. Optional integrations

Everything works without these keys. Each feature says clearly that it isn't configured instead of failing or pretending.

| Feature | Without a key | To enable |
|---|---|---|
| **AI assistant** | `/assistant` shows "Not configured yet" and the input is disabled | Create a key at [Google AI Studio](https://aistudio.google.com/apikey), then set `GEMINI_API_KEY`. `GEMINI_MODEL` defaults to `gemini-flash-lite-latest` with minimal thinking: the fastest model measured, with the first words of an answer in about 1–1.5 s. The Super Admin can also switch the assistant off in Platform settings. |
| **Online fine payment** | With `PAYMENTS_DEMO_MODE=true`, "Pay now" opens SmartLib's **built-in demo gateway**: UPI, card or net banking, labelled **Demo mode**, with no real money. The server signs and verifies the result exactly as it does for Razorpay, and demo payments are labelled "Demo" in the payment history. With demo mode off, "Pay now" explains that online payments are not configured. Fines are never marked paid without server verification. | Set the Razorpay **test-mode** keys `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET`. A payment is completed only after the server verifies the signature. For webhooks, also set `RAZORPAY_WEBHOOK_SECRET` and expose `POST /api/webhooks/razorpay` through a tunnel such as cloudflared or ngrok. The verify endpoint alone is enough for a demo. |
| **Book covers** | Covers fall back to a generated placeholder (never a broken image) | Covers are resolved by ISBN from Open Library, then Google Books. Set `COVERS_ENABLED=false` to stay fully offline. |
| **Real email** (OTP codes, links, notifications in real inboxes) | Mail goes to Mailpit | See [Sending real email with Gmail](#sending-real-email-with-gmail) |

### Sending real email with Gmail

This works on your own machine. On Render's free plan, use Brevo instead (see [Deployment](#6-deployment)).

1. **Turn on 2-Step Verification** on the Gmail account that will send the emails.
2. **Create an App Password** at Google Account → Security → **App passwords** (name it "SmartLib"). Google shows a 16-character password once; copy it.
3. **Update `.env`.** In `.env` in the project root, replace the seven `MAIL_*` lines with:

   ```properties
   MAIL_HOST=smtp.gmail.com
   MAIL_PORT=587
   MAIL_USERNAME=your.address@gmail.com
   MAIL_PASSWORD=your16characterapppassword
   MAIL_SMTP_AUTH=true
   MAIL_STARTTLS=true
   MAIL_FROM=your.address@gmail.com
   ```

4. **Restart the backend.** The startup log should say `Email: SMTP smtp.gmail.com:587 delivers to real inboxes`.

Notes:
- **Demo and test addresses are skipped.** These are the seeded `@smartlib.demo` accounts and `.test`/`.example` test users. They can't receive mail and would only bounce back to your Gmail, so the log notes each skipped email instead.
- **Real addresses receive everything:** verification codes, links, approvals and notifications.
- **Sending limit:** Gmail allows about 500 emails a day, which is plenty for a project or demo.
- **The end-to-end tests read codes from Mailpit.** Before running `npm run e2e`, start the backend with Mailpit settings. Environment variables override `.env`:

  ```bash
  MAIL_HOST=localhost MAIL_PORT=1025 MAIL_SMTP_AUTH=false MAIL_STARTTLS=false ./mvnw spring-boot:run
  ```

## 5. Tests

| Suite | Command | Needs | Result on 2026-10-09 |
|---|---|---|---|
| Backend unit, integration, security and architecture tests | `cd backend && ./mvnw clean test` | MySQL with the `smartlib_test` schema | 55 tests passed |
| Frontend type check | `cd frontend && npm run typecheck` | — | Passed |
| Frontend unit and component tests | `cd frontend && npm test` | — | 20 tests passed |
| Frontend production build | `cd frontend && npm run build` | — | Passed |
| End-to-end journeys | `cd frontend && npm run e2e` | The full stack running as in §3, with Mailpit and demo data | 6 journeys passed |

The backend tests stub Gemini and Razorpay with a local HTTP server, so no API keys or network access are needed. The same checks run on GitHub Actions for every push and pull request (`.github/workflows/ci.yml`): backend tests against a MySQL 8 service, plus the frontend typecheck, unit tests and production build.

The end-to-end suite uses the installed Microsoft Edge by default. Set `E2E_CHANNEL=chrome` (or `chromium` after `npx playwright install chromium`) to change it. It covers:
- registration with an OTP read from Mailpit, then a request to the chosen library, with checks that only that library sees it, followed by approve, issue and return;
- librarian onboarding and approval, with checks that the new librarian can reach only their own library;
- an Admin application approved by the single Super Admin from `.env`;
- the payment safeguards;
- the assistant's states.

## 6. Deployment

The live demo runs as **one Docker container** that serves both the API and the React app.

### How the image is built

The root [`Dockerfile`](Dockerfile) has three stages:

1. **Frontend:** `npm install` and `npm run build` with Node 22.
2. **Backend:** copies the built `dist/` into `backend/src/main/resources/static/` and runs `mvn clean package`.
3. **Runtime:** a slim Java 21 JRE image runs the jar on `$PORT` (default `10000`).

Spring Boot serves the files under `/assets` and `/brand` directly, and forwards every other non-API path to `index.html`, so deep links and refreshes work.

### Hosting the demo for free

| Piece | Service | Notes |
|---|---|---|
| App | [Render](https://render.com) Web Service, runtime **Docker** | Uses the root `Dockerfile`. Auto-deploys on every push to `main`. |
| Database | [Aiven](https://aiven.io) for MySQL (free plan) | Put its host, port and database in `SMARTLIB_DB_URL`. Flyway creates the schema on first start. |
| Email | [Brevo](https://www.brevo.com) SMTP relay (free plan) | Render's free plan **blocks outbound SMTP ports 25, 465 and 587**, so Gmail on port 587 times out there. Brevo also listens on port **2525**, which works. |

### Environment variables on the host

Set these in the host's environment settings, never in a committed file:

| Variable | Value for the demo |
|---|---|
| `SMARTLIB_DB_URL`, `SMARTLIB_DB_USERNAME`, `SMARTLIB_DB_PASSWORD` | The Aiven connection details |
| `JWT_SECRET`, `APP_TOKEN_PEPPER` | New random values (do not reuse your local ones) |
| `FRONTEND_BASE_URL`, `CORS_ALLOWED_ORIGINS` | The public URL, e.g. `https://smartlib-xi89.onrender.com` |
| `COOKIE_SECURE` | `true` |
| `MAIL_HOST` / `MAIL_PORT` | `smtp-relay.brevo.com` / `2525` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Brevo's SMTP login and an SMTP key (not an API key) |
| `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` | `true` / `true` |
| `MAIL_FROM` | A sender address verified in Brevo |
| `GEMINI_API_KEY` | Your Google AI Studio key. Leave `GEMINI_MODEL` unset to use the fast default. |
| `SEED_DEMO_DATA`, `DEMO_USER_PASSWORD`, `DEMO_LOGIN_ENABLED` | `true`, a strong password, and `true` only while the public demo panel should be shown |
| `PAYMENTS_DEMO_MODE` | `true` for the demo gateway, or set the Razorpay test keys |
| `BOOTSTRAP_SUPER_ADMIN_EMAIL`, `BOOTSTRAP_SUPER_ADMIN_PASSWORD` | The single Super Admin account |

**Demo data on a hosted database:** the seeder only runs when the database has no libraries. To re-seed, empty the database (drop its tables) and redeploy. This deletes everything stored there.

**Cold starts:** a free Render instance sleeps after 15 minutes idle. A free uptime pinger hitting `/actuator/health` every 10 minutes keeps it awake.

### A real production deployment

- Set `DEMO_LOGIN_ENABLED=false`, `SEED_DEMO_DATA=false` and `PAYMENTS_DEMO_MODE=false`.
- Serve the app over HTTPS with `COOKIE_SECURE=true`.
- Set `FRONTEND_BASE_URL` and `CORS_ALLOWED_ORIGINS` to the public URL.
- Use a real SMTP provider with an authenticated sending domain.
- Keep secrets in the host's secret store rather than in a file.

### Building the jar by hand

```bash
cd frontend && npm run build
# copy the SPA into the backend so Spring Boot serves it (deep links forward to index.html)
cp -r dist/* ../backend/src/main/resources/static/
cd ../backend && ./mvnw clean package
java -jar target/smartlib-backend-1.0.0.jar
```

## 7. Project layout

```
SmartLib/
├── backend/                 Spring Boot application (modular monolith)
│   └── src/main/java/com/smartlib/
│       ├── auth/ user/ security/       registration, verification, JWT + refresh rotation, RBAC, library isolation
│       ├── library/ catalogue/          libraries, books, holdings, copies, search, ISBN covers
│       ├── circulation/ fine/ payment/  requests, loans, returns, fines, Razorpay
│       ├── resource/ notification/ mail/ digital resources, in-app notifications, email outbox
│       ├── ai/                          Gemini streaming, tools, conversation store
│       ├── report/ audit/ platform/ seed/
│       └── common/                      errors, config, storage, events, web utilities
├── frontend/                React SPA
│   ├── src/app, layouts, components, features/<area>, api, stores, styles
│   └── e2e/                 Playwright journeys
├── docs/ARCHITECTURE.md
├── Dockerfile               one-container build used for the live demo
├── start-smartlib.bat       one-click local start on Windows
└── .env.example
```

## 8. Security notes

- **Secrets:** they live only in `.env` on the server. The frontend has no secrets, and the Razorpay key *id* is the only payment value sent to the browser.
- **Sessions:** the short-lived access token stays in memory. The refresh token is an httpOnly, SameSite=Strict cookie that is rotated on every use, with reuse detection.
- **Authorization:** checked on the server for every request. Library-scoped endpoints go through a single guard: another library's endpoints return 403, and another library's record ids return 404. An ArchUnit rule fails the build if a library endpoint skips the guard.
- **Admin roles:** an Admin registration never grants access by itself. It needs a verified email and approval by the Super Admin, and a library's own Admin cannot approve another Admin. There is exactly one Super Admin, configured in `.env`, and it is never self-registered or shown as a demo account.
- **Verification codes:** OTPs and links are stored only as HMAC hashes, are single-use, expire, and have attempt and resend limits.
- **Audit logs:** they never record passwords, tokens, codes or payment secrets.

## 9. License

The SmartLib source code is released under the [MIT License](LICENSE).

Third-party material keeps its own terms and is **not** covered by that license:
- **Assistant avatar** (`frontend/public/brand/assistant-avatar.webp`): adapted from artwork owned by a third party. Replace it, or obtain permission, before any public or commercial use.
- **Book covers and metadata**: loaded at runtime from Open Library and Google Books, under their terms.
- **Fonts and libraries**: Plus Jakarta Sans and Caveat (SIL Open Font License), plus the npm and Maven dependencies, under their own licenses.

## 👩‍💻 Author

**Anshika Verma** · [GitHub @Anshika482](https://github.com/Anshika482)

If you find the project useful, a ⭐ on the repository is appreciated.
