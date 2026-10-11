import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { BookUser, ChevronDown, Crown, LogIn, Mail, MailWarning, ShieldCheck, UserRound } from 'lucide-react';
import { authApi, publicApi } from '@/api/endpoints';
import { isApiError, type ApiError } from '@/api/http';
import type { DemoAccount, Role } from '@/api/types';
import { cn } from '@/lib/cn';
import { roleHome, useSession } from '@/stores/session';
import { safeNext } from '@/app/guards';
import { Alert, Button } from '@/components/ui';
import { Field, Input, PasswordInput } from '@/components/form';
import { date } from '@/lib/format';
import { ApplicationStages, AuthCard } from './shared';

/** Codes for staff/admin applications that are not approved yet (librarians keep their own codes). */
const approvalCodes = ['LIBRARIAN_APPROVAL_PENDING', 'LIBRARIAN_REJECTED', 'APPROVAL_PENDING', 'APPLICATION_REJECTED'];

const demoIcon: Record<Role, React.ReactNode> = {
  MEMBER: <UserRound className="size-4 shrink-0 text-accent-2" aria-hidden />,
  LIBRARIAN: <BookUser className="size-4 shrink-0 text-accent-2" aria-hidden />,
  ADMIN: <ShieldCheck className="size-4 shrink-0 text-accent-2" aria-hidden />,
  SUPER_ADMIN: <Crown className="size-4 shrink-0 text-accent-2" aria-hidden />,
};

const schema = z.object({
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required'),
});
type Form = z.infer<typeof schema>;

export default function LoginPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { status, user, setSession } = useSession();
  const next = safeNext(params.get('next'));
  const [problem, setProblem] = useState<ApiError | null>(null);
  const [resending, setResending] = useState(false);
  // Only offered while the server has DEMO_LOGIN_ENABLED=true; otherwise the panel never renders.
  const demo = useQuery({ queryKey: ['demo-accounts'], queryFn: publicApi.demoAccounts, staleTime: Infinity, retry: false });
  const [demoFilled, setDemoFilled] = useState<string | null>(null);
  const { register, handleSubmit, formState: { errors, isSubmitting }, getValues, setValue } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { email: params.get('email') ?? '', password: '' },
  });

  if (status === 'authenticated' && user && !problem) return <Navigate to={next ?? roleHome(user.role)} replace />;

  const onSubmit = handleSubmit(async ({ email, password }) => {
    setProblem(null);
    try {
      const res = await authApi.login(email.trim(), password);
      setSession(res.accessToken, res.user);
      toast.success(`Welcome back, ${res.user.fullName.split(' ')[0]}!`);
      navigate(next ?? roleHome(res.user.role), { replace: true });
    } catch (e) {
      if (isApiError(e)) setProblem(e);
      else setProblem(null);
    }
  });

  const fillDemo = (account: DemoAccount) => {
    setProblem(null);
    setValue('email', account.email, { shouldValidate: true });
    setValue('password', demo.data?.password ?? '', { shouldValidate: true });
    setDemoFilled(account.email);
  };

  const email = (problem?.details.email as string) ?? getValues('email');
  const verifyHref = `/verify-email?email=${encodeURIComponent(email)}${next ? `&next=${encodeURIComponent(next)}` : ''}`;

  if (problem?.code === 'EMAIL_NOT_VERIFIED') {
    return (
      <AuthCard title="Your email is not verified" subtitle={<>We need to confirm <b className="text-fg">{email}</b> before you can sign in.</>}>
        <div className="flex flex-col gap-3">
          <span className="mx-auto mb-2 flex size-14 items-center justify-center rounded-2xl border border-warning/40 bg-warning/10 text-warning"><MailWarning className="size-7" /></span>
          <Button onClick={() => navigate(verifyHref)} icon={<Mail className="size-4" />}>Verify email</Button>
          <Button variant="outline" loading={resending} onClick={async () => {
            setResending(true);
            try {
              const r = await authApi.resend(email);
              toast.success(r.message);
              navigate(verifyHref, { state: { resendIn: r.resendAvailableInSeconds, otpExpiresIn: r.otpExpiresInSeconds } });
            } catch (e) {
              if (isApiError(e) && e.code === 'RESEND_COOLDOWN') navigate(verifyHref, { state: { resendIn: e.details.retryAfterSeconds } });
              else toast.error(isApiError(e) ? e.message : 'Could not resend the email.');
            } finally {
              setResending(false);
            }
          }}>Resend verification</Button>
          <button type="button" className="mt-2 text-sm text-muted hover:text-fg" onClick={() => setProblem(null)}>Use a different account</button>
        </div>
      </AuthCard>
    );
  }

  if (problem && approvalCodes.includes(problem.code)) {
    const rejected = problem.code === 'LIBRARIAN_REJECTED' || problem.code === 'APPLICATION_REJECTED';
    const role = String(problem.details.role ?? 'LIBRARIAN');
    const reviewer = role === 'SUPER_ADMIN' ? 'an existing Super Admin' : role === 'ADMIN' ? 'a Super Admin' : 'an administrator of this library';
    const library = String(problem.details.libraryName ?? 'your library');
    return (
      <AuthCard title={rejected ? 'Application not approved' : 'Waiting for approval'}
        subtitle={role === 'SUPER_ADMIN' ? 'Super Admin application for the SmartLib platform'
          : <>{role === 'ADMIN' ? 'Admin' : 'Librarian'} application for <b className="text-fg">{library}</b></>}>
        <ApplicationStages stages={[
          { label: 'Application submitted', state: 'done', detail: problem.details.appliedAt ? date(String(problem.details.appliedAt)) : undefined },
          { label: 'Email verified', state: 'done' },
          { label: role === 'LIBRARIAN' ? 'Administrator review' : 'Super Admin review', state: rejected ? 'failed' : 'current',
            detail: rejected ? `Reviewed by ${reviewer}` : `${reviewer.charAt(0).toUpperCase()}${reviewer.slice(1)} will review your application` },
          { label: rejected ? 'Not approved' : 'Approved — access granted', state: rejected ? 'failed' : 'waiting' },
        ]} />
        {rejected && problem.details.reason ? <Alert tone="error" title="Reason" className="mt-5">{String(problem.details.reason)}</Alert> : null}
        {!rejected && <Alert tone="info" className="mt-5">You'll receive an email as soon as your application is reviewed. Email verification and approval are separate steps.</Alert>}
        <Button variant="outline" className="mt-5 w-full" onClick={() => setProblem(null)}>Back to login</Button>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Welcome back" subtitle="Sign in to your SmartLib account"
      footer={<>New to SmartLib? <Link to={`/register${next ? `?next=${encodeURIComponent(next)}` : ''}`} className="link">Create an account</Link>
        <span className="mx-2 text-faint">·</span>Staff or admin? <Link to="/register/librarian" className="link">Apply for access</Link></>}>
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {problem && (
          <Alert tone="error">
            {problem.code === 'INVALID_CREDENTIALS' ? 'Incorrect email or password.' : problem.message}
            {problem.code === 'RATE_LIMITED' && problem.details.retryAfterSeconds ? ` (${problem.details.retryAfterSeconds}s)` : ''}
          </Alert>
        )}
        <Field label="Email" htmlFor="email" error={errors.email?.message} required>
          <Input id="email" type="email" autoComplete="email" invalid={!!errors.email} {...register('email')} />
        </Field>
        <Field label={<span className="flex w-full items-center justify-between">Password<Link to="/forgot-password" className="text-xs font-semibold text-accent-2 hover:underline">Forgot password?</Link></span>}
          htmlFor="password" error={errors.password?.message} required>
          <PasswordInput id="password" autoComplete="current-password" invalid={!!errors.password} {...register('password')} />
        </Field>
        <Button type="submit" className="w-full" size="lg" loading={isSubmitting} icon={<LogIn className="size-4" />}>Log in</Button>
      </form>
      {demo.data?.enabled && demo.data.accounts.length > 0 && (
        <details open className="group mt-5 rounded-xl border border-dashed border-line-strong bg-ink/40 p-4">
          <summary className="flex cursor-pointer list-none items-center gap-2 text-sm font-semibold text-fg [&::-webkit-details-marker]:hidden">
            <ChevronDown className="size-4 -rotate-90 transition-transform group-open:rotate-0" aria-hidden />Use a demo account
          </summary>
          <p className="mt-2 text-xs text-muted">These demo accounts are already verified and approved. Pick a role to fill in the form, then click Log in.</p>
          <div className="mt-3 grid grid-cols-2 gap-2">
            {demo.data.accounts.map((a) => (
              <button key={a.email} type="button" title={a.email} onClick={() => fillDemo(a)}
                className={cn('flex items-start gap-2 rounded-lg border px-3 py-2 text-left transition',
                  demoFilled === a.email ? 'border-accent-2/60 bg-accent/15' : 'border-line bg-ink/60 hover:border-line-strong hover:bg-white/[0.03]')}>
                <span className="mt-0.5">{demoIcon[a.role]}</span>
                <span className="min-w-0">
                  <span className="block text-sm font-semibold text-fg">{a.label}</span>
                  <span className="block text-[11px] leading-snug text-faint">{a.description}</span>
                </span>
              </button>
            ))}
          </div>
          {demoFilled && <p className="mt-3 text-xs text-success" role="status">Filled in with {demoFilled}. Click Log in.</p>}
        </details>
      )}
    </AuthCard>
  );
}
