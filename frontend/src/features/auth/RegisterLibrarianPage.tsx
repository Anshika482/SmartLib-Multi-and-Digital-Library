import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery } from '@tanstack/react-query';
import { Send } from 'lucide-react';
import { authApi, publicApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { Alert, Button, Skeleton } from '@/components/ui';
import { Checkbox, Field, Input, PasswordInput, Select, Textarea } from '@/components/form';
import { ApplicationStages, AuthCard, PasswordStrength, RegistrationRoleTabs } from './shared';
import { passwordRule, phoneRule } from './RegisterPage';

const schema = z.object({
  fullName: z.string().trim().min(2, 'Enter your full name').max(120),
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address'),
  phone: phoneRule,
  libraryId: z.string().min(1, 'Please choose the library you work at'),
  employeeRef: z.string().trim().max(64).optional(),
  applicationNote: z.string().trim().max(1000, 'Max 1000 characters').optional(),
  password: passwordRule,
  confirmPassword: z.string().min(1, 'Please confirm your password'),
  acceptTerms: z.boolean().refine((v) => v, 'You must accept the terms to continue'),
}).refine((d) => d.password === d.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });
type Form = z.infer<typeof schema>;

export default function RegisterLibrarianPage() {
  const navigate = useNavigate();
  const policy = useQuery({ queryKey: ['auth-policy'], queryFn: authApi.policy });
  const libraries = useQuery({ queryKey: ['public', 'libraries', ''], queryFn: () => publicApi.libraries() });
  const [serverError, setServerError] = useState<string | null>(null);
  const { register, handleSubmit, watch, setError, formState: { errors, isSubmitting } } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { fullName: '', email: '', phone: '', libraryId: '', employeeRef: '', applicationNote: '', password: '', confirmPassword: '', acceptTerms: false },
  });
  const password = watch('password');

  const onSubmit = handleSubmit(async (v) => {
    setServerError(null);
    try {
      const res = await authApi.registerLibrarian({ ...v, libraryId: Number(v.libraryId), phone: v.phone || null });
      navigate(`/verify-email?email=${encodeURIComponent(res.email)}&role=librarian`, {
        state: { justRegistered: true, resendIn: res.resendAvailableInSeconds, otpExpiresIn: res.otpExpiresInSeconds, message: res.message, librarian: true },
      });
    } catch (e) {
      if (!isApiError(e)) return setServerError('Something went wrong. Please try again.');
      e.fieldErrors.forEach((f) => setError(f.field as keyof Form, { message: f.message }));
      if (e.code === 'EMAIL_ALREADY_REGISTERED') setError('email', { message: 'This email is already registered' });
      if (!e.fieldErrors.length) setServerError(e.message);
    }
  });

  if (policy.data && !policy.data.librarianRegistrationOpen) {
    return (
      <AuthCard title="Librarian applications are closed" subtitle="The platform administrator has paused new librarian applications.">
        <RegistrationRoleTabs current="LIBRARIAN" />
        <Link to="/" className="btn btn-outline w-full">Back to home</Link>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Apply as a librarian" subtitle="Librarian access needs two separate steps: verifying your email, then approval by your library's administrator."
      footer={<>Already applied? <Link to="/login" className="link">Check your status by logging in</Link></>}>
      <RegistrationRoleTabs current="LIBRARIAN" />
      <div className="mb-6 rounded-xl border border-line bg-ink/40 p-4">
        <ApplicationStages stages={[
          { label: 'Submit application', state: 'current' },
          { label: 'Verify your email', state: 'waiting' },
          { label: 'Administrator approval', state: 'waiting' },
          { label: 'Access your library workspace', state: 'waiting' },
        ]} />
      </div>
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {serverError && <Alert tone="error">{serverError}</Alert>}
        <Field label="Full name" htmlFor="fullName" error={errors.fullName?.message} required>
          <Input id="fullName" autoComplete="name" invalid={!!errors.fullName} {...register('fullName')} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Work email" htmlFor="email" error={errors.email?.message} required>
            <Input id="email" type="email" autoComplete="email" invalid={!!errors.email} {...register('email')} />
          </Field>
          <Field label="Phone" htmlFor="phone" error={errors.phone?.message} hint="Optional">
            <Input id="phone" type="tel" autoComplete="tel" invalid={!!errors.phone} {...register('phone')} />
          </Field>
        </div>
        <Field label="Library" htmlFor="libraryId" error={errors.libraryId?.message} required>
          {libraries.isPending ? <Skeleton className="h-11" /> : (
            <Select id="libraryId" invalid={!!errors.libraryId} {...register('libraryId')}>
              <option value="">Choose the library you work at…</option>
              {libraries.data?.map((l) => <option key={l.id} value={l.id}>{l.name}{l.city ? ` — ${l.city}` : ''}</option>)}
            </Select>
          )}
        </Field>
        <Field label="Staff / employee ID" htmlFor="employeeRef" error={errors.employeeRef?.message} hint="Optional — helps the administrator identify you">
          <Input id="employeeRef" {...register('employeeRef')} />
        </Field>
        <Field label="Note to the administrator" htmlFor="applicationNote" error={errors.applicationNote?.message} hint="Optional">
          <Textarea id="applicationNote" rows={3} {...register('applicationNote')} />
        </Field>
        <Field label="Password" htmlFor="password" error={errors.password?.message} required>
          <PasswordInput id="password" autoComplete="new-password" invalid={!!errors.password} {...register('password')} />
        </Field>
        {password && <PasswordStrength password={password} />}
        <Field label="Confirm password" htmlFor="confirmPassword" error={errors.confirmPassword?.message} required>
          <PasswordInput id="confirmPassword" autoComplete="new-password" invalid={!!errors.confirmPassword} {...register('confirmPassword')} />
        </Field>
        <div>
          <Checkbox invalid={!!errors.acceptTerms} {...register('acceptTerms')} label="I confirm I work at the selected library and agree to the SmartLib Terms & Privacy Policy." />
          {errors.acceptTerms && <p className="mt-1 text-xs text-danger" role="alert">{errors.acceptTerms.message}</p>}
        </div>
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting} icon={<Send className="size-4" />}>Submit application</Button>
      </form>
    </AuthCard>
  );
}
