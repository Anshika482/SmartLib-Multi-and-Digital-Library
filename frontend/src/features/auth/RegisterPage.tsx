import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { KeyRound, MailCheck, ShieldCheck, UserPlus } from 'lucide-react';
import { authApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { safeNext } from '@/app/guards';
import { Alert, Button } from '@/components/ui';
import { Checkbox, Field, Input, PasswordInput } from '@/components/form';
import { AuthCard, PasswordStrength, RegistrationRoleTabs } from './shared';

export const passwordRule = z.string().min(8, 'Password must be at least 8 characters').max(72, 'Password is too long')
  .regex(/[A-Za-z]/, 'Password must contain a letter').regex(/\d/, 'Password must contain a number');
export const phoneRule = z.string().trim().regex(/^$|^\+?[0-9][0-9 -]{6,18}[0-9]$/, 'Enter a valid phone number').optional();

const schema = z.object({
  fullName: z.string().trim().min(2, 'Enter your full name').max(120),
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address').max(254),
  phone: phoneRule,
  password: passwordRule,
  confirmPassword: z.string().min(1, 'Please confirm your password'),
  acceptTerms: z.boolean().refine((v) => v, 'You must accept the terms to continue'),
}).refine((d) => d.password === d.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });
type Form = z.infer<typeof schema>;

export default function RegisterPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const [serverError, setServerError] = useState<{ message: string; unverified?: boolean; email?: string } | null>(null);
  const { register, handleSubmit, watch, setError, formState: { errors, isSubmitting } } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { fullName: '', email: '', phone: '', password: '', confirmPassword: '', acceptTerms: false },
  });
  const password = watch('password');

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      const res = await authApi.registerMember({ ...values, email: values.email.trim(), phone: values.phone || null });
      navigate(`/verify-email?email=${encodeURIComponent(res.email)}${next ? `&next=${encodeURIComponent(next)}` : ''}`, {
        state: { justRegistered: true, resendIn: res.resendAvailableInSeconds, otpExpiresIn: res.otpExpiresInSeconds, message: res.message },
      });
    } catch (e) {
      if (!isApiError(e)) return setServerError({ message: 'Something went wrong. Please try again.' });
      if (e.fieldErrors.length) e.fieldErrors.forEach((f) => setError(f.field as keyof Form, { message: f.message }));
      if (e.code === 'EMAIL_ALREADY_REGISTERED') {
        setError('email', { message: 'This email is already registered' });
        setServerError({ message: e.message, unverified: !!e.details.unverified, email: values.email.trim() });
      } else if (!e.fieldErrors.length) setServerError({ message: e.message });
    }
  });

  return (
    <AuthCard title="Create your account" subtitle="Join SmartLib to request books from any library, track loans and access digital resources."
      footer={<>Already have an account? <Link to={`/login${next ? `?next=${encodeURIComponent(next)}` : ''}`} className="link">Log in</Link></>}>
      <RegistrationRoleTabs current="MEMBER" />
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {serverError && (
          <Alert tone="error" action={serverError.unverified ? (
            <Link className="link text-sm" to={`/verify-email?email=${encodeURIComponent(serverError.email ?? '')}`}>Verify this email instead →</Link>
          ) : serverError.email ? <Link className="link text-sm" to={`/login?email=${encodeURIComponent(serverError.email)}`}>Go to login →</Link> : undefined}>
            {serverError.message}
          </Alert>
        )}
        <Field label="Full name" htmlFor="fullName" error={errors.fullName?.message} required>
          <Input id="fullName" autoComplete="name" invalid={!!errors.fullName} {...register('fullName')} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Email" htmlFor="email" error={errors.email?.message} required>
            <Input id="email" type="email" autoComplete="email" invalid={!!errors.email} {...register('email')} />
          </Field>
          <Field label="Phone" htmlFor="phone" error={errors.phone?.message} hint="Optional">
            <Input id="phone" type="tel" autoComplete="tel" placeholder="+91 98765 43210" invalid={!!errors.phone} {...register('phone')} />
          </Field>
        </div>
        <Field label="Password" htmlFor="password" error={errors.password?.message} required>
          <PasswordInput id="password" autoComplete="new-password" invalid={!!errors.password} {...register('password')} />
        </Field>
        {password && <PasswordStrength password={password} />}
        <Field label="Confirm password" htmlFor="confirmPassword" error={errors.confirmPassword?.message} required>
          <PasswordInput id="confirmPassword" autoComplete="new-password" invalid={!!errors.confirmPassword} {...register('confirmPassword')} />
        </Field>
        <div>
          <Checkbox invalid={!!errors.acceptTerms} {...register('acceptTerms')}
            label={<>I agree to the SmartLib Terms & Conditions and Privacy Policy, including email verification of my account.</>} />
          {errors.acceptTerms && <p className="mt-1 text-xs text-danger" role="alert">{errors.acceptTerms.message}</p>}
        </div>
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting} icon={<UserPlus className="size-4" />}>Create account</Button>
        <ul className="grid gap-2 rounded-xl border border-line bg-ink/40 p-3 text-xs text-muted">
          <li className="flex items-center gap-2"><MailCheck className="size-4 text-accent-2" aria-hidden />We'll email a 6-digit code and a secure link — either verifies your account.</li>
          <li className="flex items-center gap-2"><KeyRound className="size-4 text-accent-2" aria-hidden />Passwords are stored only as secure hashes.</li>
          <li className="flex items-center gap-2"><ShieldCheck className="size-4 text-accent-2" aria-hidden />Library staff or administrator? Choose your role in the tabs above.</li>
        </ul>
      </form>
    </AuthCard>
  );
}
