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
import { passwordRule } from './RegisterPage';

const schema = z.object({
  fullName: z.string().trim().min(2, 'Enter your full name').max(120),
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address').max(254),
  phone: z.string().trim().min(1, 'Phone number is required').regex(/^\+?[0-9][0-9 -]{6,18}[0-9]$/, 'Enter a valid phone number'),
  libraryId: z.string().min(1, 'Please choose the library you will administer'),
  designation: z.string().trim().min(2, 'Enter your designation').max(100, 'Max 100 characters'),
  employeeRef: z.string().trim().max(64, 'Max 64 characters').optional(),
  applicationNote: z.string().trim().min(20, 'Please write at least 20 characters').max(1000, 'Max 1000 characters'),
  password: passwordRule,
  confirmPassword: z.string().min(1, 'Please confirm your password'),
  confirmAuthority: z.boolean().refine((v) => v, 'You must confirm that you are authorised'),
  acceptTerms: z.boolean().refine((v) => v, 'You must accept the terms to continue'),
}).refine((d) => d.password === d.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });
type Form = z.infer<typeof schema>;

/** Application to administer one library. Access needs email verification and the Super Admin's approval. */
export default function RegisterAdminPage() {
  const navigate = useNavigate();
  const policy = useQuery({ queryKey: ['auth-policy'], queryFn: authApi.policy });
  const libraries = useQuery({ queryKey: ['public', 'libraries', ''], queryFn: () => publicApi.libraries() });
  const [serverError, setServerError] = useState<string | null>(null);
  const { register, handleSubmit, watch, setError, formState: { errors, isSubmitting } } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { fullName: '', email: '', phone: '', libraryId: '', designation: '', employeeRef: '', applicationNote: '',
      password: '', confirmPassword: '', confirmAuthority: false, acceptTerms: false },
  });
  const password = watch('password');
  const note = watch('applicationNote') ?? '';

  const onSubmit = handleSubmit(async ({ libraryId, ...v }) => {
    setServerError(null);
    try {
      const res = await authApi.registerAdmin({ ...v, employeeRef: v.employeeRef || null, libraryId: Number(libraryId) });
      navigate(`/verify-email?email=${encodeURIComponent(res.email)}&role=admin`, {
        state: { justRegistered: true, resendIn: res.resendAvailableInSeconds, otpExpiresIn: res.otpExpiresInSeconds, message: res.message },
      });
    } catch (e) {
      if (!isApiError(e)) return setServerError('Something went wrong. Please try again.');
      e.fieldErrors.forEach((f) => setError(f.field as keyof Form, { message: f.message }));
      if (e.code === 'EMAIL_ALREADY_REGISTERED') setError('email', { message: 'This email is already registered' });
      if (!e.fieldErrors.length) setServerError(e.message);
    }
  });

  if (policy.data && !policy.data.adminRegistrationOpen) {
    return (
      <AuthCard title="Admin applications are closed" subtitle="The Super Admin has paused new admin applications.">
        <RegistrationRoleTabs current="ADMIN" />
        <Link to="/" className="btn btn-outline w-full">Back to home</Link>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Apply as a library admin" subtitle="Admins manage one library: its librarians, members, policies, fines, reports and settings."
      footer={<>Already applied? <Link to="/login" className="link">Check your status by logging in</Link></>}>
      <RegistrationRoleTabs current="ADMIN" />
      <Alert tone="warning" className="mb-5">Admin access is never granted automatically. After you verify your email, the Super Admin reviews your application.</Alert>
      <div className="mb-6 rounded-xl border border-line bg-ink/40 p-4">
        <ApplicationStages stages={[
          { label: 'Submit application', state: 'current' },
          { label: 'Verify your email', state: 'waiting' },
          { label: 'Super Admin approval', state: 'waiting' },
          { label: 'Access your library admin workspace', state: 'waiting' },
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
          <Field label="Phone" htmlFor="phone" error={errors.phone?.message} required>
            <Input id="phone" type="tel" autoComplete="tel" placeholder="+91 98765 43210" invalid={!!errors.phone} {...register('phone')} />
          </Field>
        </div>
        <Field label="Library" htmlFor="libraryId" error={errors.libraryId?.message} required>
          {libraries.isPending ? <Skeleton className="h-11" /> : (
            <Select id="libraryId" invalid={!!errors.libraryId} {...register('libraryId')}>
              <option value="">Choose the library you will administer…</option>
              {libraries.data?.map((l) => <option key={l.id} value={l.id}>{l.name}{l.city ? ` — ${l.city}` : ''}</option>)}
            </Select>
          )}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Designation" htmlFor="designation" error={errors.designation?.message} required hint="Your job title">
            <Input id="designation" autoComplete="organization-title" placeholder="e.g. Head Librarian" invalid={!!errors.designation} {...register('designation')} />
          </Field>
          <Field label="Staff / employee ID" htmlFor="employeeRef" error={errors.employeeRef?.message} hint="Optional — helps the reviewer identify you">
            <Input id="employeeRef" invalid={!!errors.employeeRef} {...register('employeeRef')} />
          </Field>
        </div>
        <Field label="Why do you need admin access?" htmlFor="applicationNote" error={errors.applicationNote?.message} required
          hint={`${note.trim().length}/1000 characters (at least 20)`}>
          <Textarea id="applicationNote" rows={4} invalid={!!errors.applicationNote} {...register('applicationNote')} />
        </Field>
        <Field label="Password" htmlFor="password" error={errors.password?.message} required>
          <PasswordInput id="password" autoComplete="new-password" invalid={!!errors.password} {...register('password')} />
        </Field>
        {password && <PasswordStrength password={password} />}
        <Field label="Confirm password" htmlFor="confirmPassword" error={errors.confirmPassword?.message} required>
          <PasswordInput id="confirmPassword" autoComplete="new-password" invalid={!!errors.confirmPassword} {...register('confirmPassword')} />
        </Field>
        <div>
          <Checkbox invalid={!!errors.confirmAuthority} {...register('confirmAuthority')} label="I confirm I am authorised by the selected library to act as its administrator." />
          {errors.confirmAuthority && <p className="mt-1 text-xs text-danger" role="alert">{errors.confirmAuthority.message}</p>}
        </div>
        <div>
          <Checkbox invalid={!!errors.acceptTerms} {...register('acceptTerms')} label="I agree to the SmartLib Terms & Privacy Policy, including email verification of my account." />
          {errors.acceptTerms && <p className="mt-1 text-xs text-danger" role="alert">{errors.acceptTerms.message}</p>}
        </div>
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting} icon={<Send className="size-4" />}>Submit application</Button>
      </form>
    </AuthCard>
  );
}
