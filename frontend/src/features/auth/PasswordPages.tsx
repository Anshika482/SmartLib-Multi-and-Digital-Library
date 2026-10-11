import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { KeyRound, MailCheck } from 'lucide-react';
import { authApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { Alert, Button, Spinner } from '@/components/ui';
import { Field, Input, PasswordInput } from '@/components/form';
import { AuthCard, PasswordStrength } from './shared';
import { passwordRule } from './RegisterPage';

export function ForgotPasswordPage() {
  const [sent, setSent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<{ email: string }>({
    resolver: zodResolver(z.object({ email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address') })),
  });
  const onSubmit = handleSubmit(async ({ email }) => {
    setError(null);
    try {
      setSent((await authApi.forgot(email.trim())).message);
    } catch (e) {
      setError(isApiError(e) ? e.message : 'Something went wrong.');
    }
  });
  if (sent) {
    return (
      <AuthCard title="Check your email" subtitle={sent}>
        <div className="flex flex-col items-center gap-4">
          <span className="flex size-16 items-center justify-center rounded-2xl border border-line-strong bg-accent/10 text-accent-2"><MailCheck className="size-8" /></span>
          <Link to="/login" className="btn btn-outline w-full">Back to login</Link>
        </div>
      </AuthCard>
    );
  }
  return (
    <AuthCard title="Forgot password" subtitle="Enter your email and we'll send you a secure reset link." footer={<Link to="/login" className="link">Back to login</Link>}>
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {error && <Alert tone="error">{error}</Alert>}
        <Field label="Email address" htmlFor="email" error={errors.email?.message} required>
          <Input id="email" type="email" autoComplete="email" invalid={!!errors.email} {...register('email')} />
        </Field>
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting}>Send reset link</Button>
      </form>
    </AuthCard>
  );
}

const resetSchema = z.object({ newPassword: passwordRule, confirmPassword: z.string().min(1, 'Please confirm your password') })
  .refine((d) => d.newPassword === d.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });
type ResetForm = z.infer<typeof resetSchema>;

function NewPasswordForm({ token, submit, cta }: { token: string; submit: (b: { token: string } & ResetForm) => Promise<{ message: string }>; cta: string }) {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const { register, handleSubmit, watch, setError: setFieldError, formState: { errors, isSubmitting } } = useForm<ResetForm>({ resolver: zodResolver(resetSchema) });
  const pw = watch('newPassword') ?? '';
  const onSubmit = handleSubmit(async (v) => {
    setError(null);
    try {
      const r = await submit({ token, ...v });
      toast.success(r.message);
      navigate('/login', { replace: true });
    } catch (e) {
      if (!isApiError(e)) return setError('Something went wrong.');
      e.fieldErrors.forEach((f) => setFieldError(f.field as keyof ResetForm, { message: f.message }));
      if (!e.fieldErrors.length) setError(e.message);
    }
  });
  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {error && <Alert tone="error">{error}</Alert>}
      <Field label="New password" htmlFor="newPassword" error={errors.newPassword?.message} required>
        <PasswordInput id="newPassword" autoComplete="new-password" invalid={!!errors.newPassword} {...register('newPassword')} />
      </Field>
      {pw && <PasswordStrength password={pw} />}
      <Field label="Confirm new password" htmlFor="confirmPassword" error={errors.confirmPassword?.message} required>
        <PasswordInput id="confirmPassword" autoComplete="new-password" invalid={!!errors.confirmPassword} {...register('confirmPassword')} />
      </Field>
      <Button type="submit" size="lg" className="w-full" loading={isSubmitting} icon={<KeyRound className="size-4" />}>{cta}</Button>
    </form>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const check = useQuery({ queryKey: ['reset-token', token], queryFn: () => authApi.validateReset(token), enabled: !!token, retry: false });
  if (!token || check.data?.valid === false || check.isError) {
    const expired = check.data?.reason === 'RESET_TOKEN_EXPIRED';
    return (
      <AuthCard title={expired ? 'Reset link expired' : 'Invalid reset link'}
        subtitle={expired ? 'For your security, reset links expire after 30 minutes.' : 'This link is invalid or has already been used.'}>
        <Link to="/forgot-password" className="btn btn-primary w-full">Request a new link</Link>
      </AuthCard>
    );
  }
  if (check.isPending) return <AuthCard title="Checking your link…"><div className="flex justify-center py-4"><Spinner /></div></AuthCard>;
  return (
    <AuthCard title="Choose a new password" subtitle="You'll be signed out of other devices after resetting.">
      <NewPasswordForm token={token} cta="Reset password" submit={(b) => authApi.reset({ token: b.token, newPassword: b.newPassword, confirmPassword: b.confirmPassword })} />
    </AuthCard>
  );
}

export function AcceptInvitePage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const check = useQuery({ queryKey: ['invite-token', token], queryFn: () => authApi.validateInvite(token), enabled: !!token, retry: false });
  if (!token || check.data?.valid === false || check.isError) {
    return (
      <AuthCard title="Invitation unavailable" subtitle="This invitation link is invalid, expired or already used. Ask the platform administrator for a new one.">
        <Link to="/" className="btn btn-outline w-full">Back to home</Link>
      </AuthCard>
    );
  }
  if (check.isPending) return <AuthCard title="Checking your invitation…"><div className="flex justify-center py-4"><Spinner /></div></AuthCard>;
  return (
    <AuthCard title="Activate your administrator account"
      subtitle={<>Welcome {check.data.fullName}! Set a password for <b className="text-fg">{check.data.email}</b>{check.data.libraryName ? <> to administer <b className="text-fg">{check.data.libraryName}</b></> : null}.</>}>
      <NewPasswordForm token={token} cta="Activate account" submit={(b) => authApi.acceptInvite({ token: b.token, newPassword: b.newPassword, confirmPassword: b.confirmPassword })} />
    </AuthCard>
  );
}
