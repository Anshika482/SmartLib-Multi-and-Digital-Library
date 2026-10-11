import { useMemo } from 'react';
import { Link } from 'react-router';
import { BookUser, Check, Circle, Hourglass, ShieldCheck, UserRound, X } from 'lucide-react';
import { cn } from '@/lib/cn';

export function PasswordStrength({ password }: { password: string }) {
  const checks = useMemo(() => [
    { ok: password.length >= 8, label: 'At least 8 characters' },
    { ok: /[A-Za-z]/.test(password), label: 'A letter' },
    { ok: /\d/.test(password), label: 'A number' },
    { ok: /[^A-Za-z0-9]/.test(password) || /[A-Z]/.test(password), label: 'A symbol or capital letter (recommended)' },
  ], [password]);
  const score = checks.filter((c) => c.ok).length;
  const colors = ['bg-white/10', 'bg-danger', 'bg-warning', 'bg-accent-2', 'bg-success'];
  return (
    <div className="space-y-2" aria-live="polite">
      <div className="flex gap-1" aria-hidden>
        {[1, 2, 3, 4].map((i) => <span key={i} className={cn('h-1.5 flex-1 rounded-full transition', i <= score ? colors[score] : 'bg-white/10')} />)}
      </div>
      <ul className="grid grid-cols-1 gap-1 text-xs sm:grid-cols-2">
        {checks.map((c) => (
          <li key={c.label} className={cn('flex items-center gap-1.5', c.ok ? 'text-success' : 'text-faint')}>
            {c.ok ? <Check className="size-3.5" aria-hidden /> : <Circle className="size-3" aria-hidden />}{c.label}
            <span className="sr-only">{c.ok ? '(met)' : '(not met)'}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export type StageState = 'done' | 'current' | 'waiting' | 'failed';

/** Librarian onboarding: email verification and administrator approval are separate stages. */
export function ApplicationStages({ stages }: { stages: { label: string; detail?: string; state: StageState }[] }) {
  return (
    <ol className="space-y-3">
      {stages.map((s, i) => (
        <li key={s.label} className="flex gap-3">
          <span className={cn('mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full border text-xs font-bold',
            s.state === 'done' && 'border-success/50 bg-success/15 text-success',
            s.state === 'current' && 'border-warning/50 bg-warning/15 text-warning',
            s.state === 'waiting' && 'border-line text-faint',
            s.state === 'failed' && 'border-danger/50 bg-danger/15 text-danger')}>
            {s.state === 'done' ? <Check className="size-4" /> : s.state === 'current' ? <Hourglass className="size-3.5" /> : s.state === 'failed' ? <X className="size-4" /> : i + 1}
          </span>
          <div>
            <p className={cn('text-sm font-semibold', s.state === 'waiting' ? 'text-faint' : 'text-fg')}>{s.label}</p>
            {s.detail && <p className="text-xs text-muted">{s.detail}</p>}
          </div>
        </li>
      ))}
    </ol>
  );
}

export function AuthCard({ title, subtitle, children, footer }: { title: string; subtitle?: React.ReactNode; children: React.ReactNode; footer?: React.ReactNode }) {
  return (
    <div className="glass p-6 sm:p-8">
      <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
      {subtitle && <p className="mt-1.5 text-sm text-muted">{subtitle}</p>}
      <div className="mt-6">{children}</div>
      {footer && <div className="mt-6 border-t border-line pt-5 text-center text-sm text-muted">{footer}</div>}
    </div>
  );
}

export type RegistrationRole = 'MEMBER' | 'LIBRARIAN' | 'ADMIN';

const registrationRoles: { role: RegistrationRole; to: string; label: string; icon: React.ReactNode }[] = [
  { role: 'MEMBER', to: '/register', label: 'Member', icon: <UserRound className="size-4" aria-hidden /> },
  { role: 'LIBRARIAN', to: '/register/librarian', label: 'Librarian', icon: <BookUser className="size-4" aria-hidden /> },
  { role: 'ADMIN', to: '/register/admin', label: 'Admin', icon: <ShieldCheck className="size-4" aria-hidden /> },
];

/** Switches between the registration forms. Staff and admin roles are applications that need approval; the single Super Admin is configured in .env. */
export function RegistrationRoleTabs({ current }: { current: RegistrationRole }) {
  return (
    <nav aria-label="Account type" className="mb-5 grid grid-cols-3 gap-1 rounded-xl border border-line bg-ink/40 p-1">
      {registrationRoles.map((r) => (
        <Link key={r.role} to={r.to} aria-current={r.role === current ? 'page' : undefined}
          className={cn('flex flex-col items-center gap-1 rounded-lg px-1 py-2 text-center text-[11px] font-semibold leading-tight transition sm:flex-row sm:justify-center sm:gap-1.5 sm:text-xs',
            r.role === current ? 'bg-accent/20 text-fg shadow-[inset_0_0_0_1px_rgba(57,197,255,0.35)]' : 'text-muted hover:bg-white/[0.04] hover:text-fg')}>
          {r.icon}<span>{r.label}</span>
        </Link>
      ))}
    </nav>
  );
}
