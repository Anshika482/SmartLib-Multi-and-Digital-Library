import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, Link2, MailOpen, RefreshCcw, ShieldCheck } from 'lucide-react';
import { authApi } from '@/api/endpoints';
import { isApiError, type ApiError } from '@/api/http';
import type { VerificationResult } from '@/api/types';
import { safeNext } from '@/app/guards';
import { Alert, Button, Spinner } from '@/components/ui';
import { Field, Input, OtpInput } from '@/components/form';
import { ApplicationStages, AuthCard } from './shared';

function useCountdown(initial: number) {
  const [left, setLeft] = useState(initial);
  useEffect(() => {
    if (left <= 0) return;
    const t = setTimeout(() => setLeft((l) => l - 1), 1000);
    return () => clearTimeout(t);
  }, [left]);
  return [left, setLeft] as const;
}

const mmss = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;

function Verified({ result, next }: { result: VerificationResult; next: string | null }) {
  const login = `/login?email=${encodeURIComponent(result.email)}${next ? `&next=${encodeURIComponent(next)}` : ''}`;
  if (result.nextStep === 'AWAIT_APPROVAL') {
    const subtitle = result.role === 'SUPER_ADMIN' ? 'Your Super Admin application is now waiting for approval by an existing Super Admin.'
      : result.role === 'ADMIN' ? 'Your admin application is now with a Super Admin for review.'
        : 'Your librarian application is now with the library administrator.';
    return (
      <AuthCard title="Email verified" subtitle={subtitle}>
        <ApplicationStages stages={[
          { label: 'Application submitted', state: 'done' },
          { label: 'Email verified', state: 'done' },
          { label: result.role === 'LIBRARIAN' ? 'Administrator approval' : 'Super Admin approval', state: 'current', detail: "You'll get an email when it's reviewed" },
          { label: result.role === 'SUPER_ADMIN' ? 'Access the platform workspace' : 'Access your workspace', state: 'waiting' },
        ]} />
        <Link to="/" className="btn btn-outline mt-6 w-full">Back to home</Link>
      </AuthCard>
    );
  }
  return (
    <AuthCard title="Email verified!" subtitle={result.message}>
      <div className="flex flex-col items-center gap-4">
        <span className="flex size-16 items-center justify-center rounded-2xl border border-success/40 bg-success/10 text-success"><CheckCircle2 className="size-8" /></span>
        <Link to={login} className="btn btn-primary btn-lg w-full">Continue to login</Link>
      </div>
    </AuthCard>
  );
}

export default function VerifyEmailPage() {
  const [params] = useSearchParams();
  const location = useLocation();
  const state = (location.state ?? {}) as { resendIn?: number; otpExpiresIn?: number; message?: string; justRegistered?: boolean };
  const token = params.get('token');
  const next = safeNext(params.get('next'));
  const policy = useQuery({ queryKey: ['auth-policy'], queryFn: authApi.policy, staleTime: Infinity });

  const [email, setEmail] = useState(params.get('email') ?? '');
  const [otp, setOtp] = useState('');
  const [busy, setBusy] = useState(false);
  const [resending, setResending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(state.message ?? null);
  const [result, setResult] = useState<VerificationResult | null>(null);
  const [cooldown, setCooldown] = useCountdown(state.resendIn ?? 0);
  const [expiresIn, setExpiresIn] = useCountdown(state.otpExpiresIn ?? 0);

  // Link flow: auto-verify the token exactly once.
  const linkStarted = useRef(false);
  const [linkState, setLinkState] = useState<'idle' | 'verifying' | 'failed'>(token ? 'verifying' : 'idle');
  useEffect(() => {
    if (!token || linkStarted.current) return;
    linkStarted.current = true;
    authApi.verifyLink(token).then(setResult).catch((e) => {
      setLinkState('failed');
      if (isApiError(e)) {
        setError(e);
        if (e.details.email) setEmail(String(e.details.email));
      }
    });
  }, [token]);

  const verify = async () => {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      setResult(await authApi.verifyOtp(email.trim(), otp));
    } catch (e) {
      if (isApiError(e)) {
        setError(e);
        if (e.code === 'INVALID_OTP') setOtp('');
      }
    } finally {
      setBusy(false);
    }
  };

  const resend = async () => {
    setError(null);
    setResending(true);
    try {
      const r = await authApi.resend(email.trim());
      setNotice(r.message);
      setCooldown(r.resendAvailableInSeconds);
      setExpiresIn(r.otpExpiresInSeconds);
      setOtp('');
    } catch (e) {
      if (isApiError(e)) {
        setError(e);
        if (e.details.retryAfterSeconds) setCooldown(Number(e.details.retryAfterSeconds));
      }
    } finally {
      setResending(false);
    }
  };

  if (result) return <Verified result={result} next={next} />;

  if (token && linkState === 'verifying') {
    return (
      <AuthCard title="Verifying your email…" subtitle="Checking your secure verification link.">
        <div className="flex justify-center py-6"><Spinner className="size-8" /></div>
      </AuthCard>
    );
  }

  const errorText = (() => {
    if (!error) return null;
    switch (error.code) {
      case 'INVALID_OTP': return `That code is incorrect.${error.details.attemptsRemaining != null ? ` ${error.details.attemptsRemaining} attempt(s) left.` : ''}`;
      case 'OTP_EXPIRED': return 'This code has expired. Request a new one below.';
      case 'TOO_MANY_OTP_ATTEMPTS': return 'Too many incorrect attempts. Request a new code to continue.';
      case 'NO_ACTIVE_CODE': return 'There is no active code for this email. Request a new one.';
      case 'RESEND_COOLDOWN': return `Please wait ${error.details.retryAfterSeconds ?? ''} seconds before requesting another code.`;
      case 'TOO_MANY_RESENDS': return 'You have requested too many codes today. Please try again later.';
      case 'INVALID_LINK': return error.message;
      case 'LINK_EXPIRED': return 'This verification link has expired. Request a new email below.';
      default: return error.message;
    }
  })();

  if (error?.code === 'ALREADY_VERIFIED') {
    return (
      <AuthCard title="Already verified" subtitle="This email address is already verified.">
        <Link to={`/login?email=${encodeURIComponent(email)}`} className="btn btn-primary btn-lg w-full">Go to login</Link>
      </AuthCard>
    );
  }

  const cooldownTotal = policy.data?.resendCooldownSeconds ?? 60;
  return (
    <AuthCard title="Verify your email"
      subtitle={email ? <>We sent a 6-digit code to <b className="text-fg">{email}</b>.</> : 'Enter your email and the 6-digit code we sent you.'}
      footer={<>Wrong address? <Link to="/register" className="link">Register again</Link><span className="mx-2 text-faint">·</span><Link to="/login" className="link">Back to login</Link></>}>
      <div className="mb-5 flex justify-center">
        <span className="relative flex size-16 items-center justify-center rounded-2xl border border-line-strong bg-accent/10 text-accent-2 shadow-[0_0_40px_-10px_rgba(57,197,255,0.7)]">
          <MailOpen className="size-8" aria-hidden />
        </span>
      </div>
      <form className="space-y-5" onSubmit={(e) => { e.preventDefault(); if (otp.length === 6) void verify(); }}>
        {notice && !error && <Alert tone="success">{notice}</Alert>}
        {errorText && <Alert tone="error">{errorText}</Alert>}
        {!params.get('email') && (
          <Field label="Email" htmlFor="verify-email">
            <Input id="verify-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" />
          </Field>
        )}
        <OtpInput value={otp} onChange={setOtp} autoFocus invalid={error?.code === 'INVALID_OTP'} disabled={busy} />
        {expiresIn > 0 && <p className="text-center text-xs text-faint">Code expires in <span className="font-semibold text-muted">{mmss(expiresIn)}</span></p>}
        <Button type="submit" size="lg" className="w-full" loading={busy} disabled={otp.length !== 6 || !email}>Verify email</Button>
      </form>
      <div className="mt-6 rounded-xl border border-line bg-ink/40 p-4 text-sm">
        <p className="font-medium">Didn't receive it?</p>
        <p className="mt-1 text-xs text-muted">Check spam or promotions. A new email cancels the previous code and link
          {policy.data ? ` (codes last ${Math.round(policy.data.otpTtlSeconds / 60)} min, links ${Math.round(policy.data.linkTtlSeconds / 3600)} h).` : '.'}</p>
        <Button variant="outline" className="mt-3 w-full" icon={<RefreshCcw className="size-4" />} loading={resending}
          disabled={cooldown > 0 || !email} onClick={resend}>
          {cooldown > 0 ? `Resend code in ${cooldown}s` : 'Resend code'}
        </Button>
        {cooldown > 0 && (
          <div className="mt-2 h-1 overflow-hidden rounded-full bg-white/5" aria-hidden>
            <div className="h-full bg-accent/60 transition-all" style={{ width: `${Math.max(0, 100 - (cooldown / cooldownTotal) * 100)}%` }} />
          </div>
        )}
      </div>
      <ul className="mt-4 space-y-1.5 text-xs text-faint">
        <li className="flex items-center gap-2"><Link2 className="size-3.5" aria-hidden />You can also verify by clicking the secure link in the email.</li>
        <li className="flex items-center gap-2"><ShieldCheck className="size-3.5" aria-hidden />Codes are single-use; SmartLib staff will never ask for them.</li>
      </ul>
    </AuthCard>
  );
}
