import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { KeyRound, LogOut, UserRound } from 'lucide-react';
import { authApi, meApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { useSession } from '@/stores/session';
import { Alert, Avatar, Badge, Button, KeyValue, PageHeader, Panel } from '@/components/ui';
import { Field, Input, PasswordInput } from '@/components/form';
import { PasswordStrength } from '@/features/auth/shared';
import { passwordRule, phoneRule } from '@/features/auth/RegisterPage';
import { useLogout } from '@/app/useLogout';
import { date, titleCase } from '@/lib/format';

const profileSchema = z.object({ fullName: z.string().trim().min(2, 'Enter your full name').max(120), phone: phoneRule });
const passwordSchema = z.object({ currentPassword: z.string().min(1, 'Current password is required'), newPassword: passwordRule, confirmPassword: z.string().min(1, 'Please confirm') })
  .refine((d) => d.newPassword === d.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });

export default function ProfilePage() {
  const qc = useQueryClient();
  const { setSession, setUser } = useSession();
  const logout = useLogout();
  const me = useQuery({ queryKey: ['me', 'profile'], queryFn: meApi.profile });
  const memberships = useQuery({ queryKey: ['me', 'memberships'], queryFn: meApi.memberships, enabled: me.data?.role === 'MEMBER' });
  const [profileError, setProfileError] = useState<string | null>(null);
  const [pwError, setPwError] = useState<string | null>(null);
  const pf = useForm<z.infer<typeof profileSchema>>({ resolver: zodResolver(profileSchema) });
  const pw = useForm<z.infer<typeof passwordSchema>>({ resolver: zodResolver(passwordSchema) });
  useEffect(() => {
    if (me.data) pf.reset({ fullName: me.data.fullName, phone: me.data.phone ?? '' });
  }, [me.data, pf]);
  const newPw = pw.watch('newPassword') ?? '';

  const saveProfile = pf.handleSubmit(async (v) => {
    setProfileError(null);
    try {
      const updated = await meApi.updateProfile({ fullName: v.fullName, phone: v.phone || null });
      setUser(updated);
      qc.setQueryData(['me', 'profile'], updated);
      toast.success('Profile updated');
    } catch (e) {
      if (isApiError(e)) {
        e.fieldErrors.forEach((f) => pf.setError(f.field as 'fullName', { message: f.message }));
        if (!e.fieldErrors.length) setProfileError(e.message);
      }
    }
  });
  const changePassword = pw.handleSubmit(async (v) => {
    setPwError(null);
    try {
      const res = await meApi.changePassword(v);
      setSession(res.accessToken, res.user);
      pw.reset({ currentPassword: '', newPassword: '', confirmPassword: '' });
      toast.success('Password changed. Other devices were signed out.');
    } catch (e) {
      if (isApiError(e)) {
        e.fieldErrors.forEach((f) => pw.setError(f.field as 'currentPassword', { message: f.message }));
        if (!e.fieldErrors.length) setPwError(e.message);
      }
    }
  });

  const u = me.data;
  return (
    <>
      <PageHeader title="Profile & security" subtitle="Your account details. Your role and library are managed by administrators." icon={<UserRound className="size-5" />} />
      <div className="grid gap-6 xl:grid-cols-[1fr_1fr]">
        <Panel title="Account">
          {u && (
            <>
              <div className="mb-5 flex items-center gap-4">
                <Avatar name={u.fullName} className="size-14 text-base" />
                <div>
                  <p className="text-lg font-semibold">{u.fullName}</p>
                  <p className="text-sm text-muted">{u.email}</p>
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    <Badge tone="blue">{titleCase(u.role)}</Badge>
                    {u.emailVerified && <Badge tone="green">Email verified</Badge>}
                    {u.library && <Badge tone="cyan">{u.library.name}</Badge>}
                  </div>
                </div>
              </div>
              <KeyValue items={[{ label: 'Member since', value: date(u.createdAt) }, { label: 'Role', value: titleCase(u.role) }]} />
              {memberships.data && memberships.data.length > 0 && (
                <div className="mt-5">
                  <p className="mb-2 text-xs text-faint">Library memberships</p>
                  <div className="flex flex-wrap gap-2">{memberships.data.map((m) => <Badge key={m.libraryId} tone={m.status === 'ACTIVE' ? 'green' : 'red'}>{m.libraryName}{m.status !== 'ACTIVE' ? ' (suspended)' : ''}</Badge>)}</div>
                </div>
              )}
            </>
          )}
          <form onSubmit={saveProfile} noValidate className="mt-6 space-y-4 border-t border-line pt-5">
            {profileError && <Alert tone="error">{profileError}</Alert>}
            <Field label="Full name" htmlFor="p-name" error={pf.formState.errors.fullName?.message} required>
              <Input id="p-name" invalid={!!pf.formState.errors.fullName} {...pf.register('fullName')} />
            </Field>
            <Field label="Phone" htmlFor="p-phone" error={pf.formState.errors.phone?.message}>
              <Input id="p-phone" type="tel" invalid={!!pf.formState.errors.phone} {...pf.register('phone')} />
            </Field>
            <Button type="submit" loading={pf.formState.isSubmitting}>Save changes</Button>
          </form>
        </Panel>
        <div className="space-y-6">
          <Panel title="Change password" icon={<KeyRound className="size-5" />}>
            <form onSubmit={changePassword} noValidate className="space-y-4">
              {pwError && <Alert tone="error">{pwError}</Alert>}
              <Field label="Current password" htmlFor="pw-current" error={pw.formState.errors.currentPassword?.message} required>
                <PasswordInput id="pw-current" autoComplete="current-password" invalid={!!pw.formState.errors.currentPassword} {...pw.register('currentPassword')} />
              </Field>
              <Field label="New password" htmlFor="pw-new" error={pw.formState.errors.newPassword?.message} required>
                <PasswordInput id="pw-new" autoComplete="new-password" invalid={!!pw.formState.errors.newPassword} {...pw.register('newPassword')} />
              </Field>
              {newPw && <PasswordStrength password={newPw} />}
              <Field label="Confirm new password" htmlFor="pw-confirm" error={pw.formState.errors.confirmPassword?.message} required>
                <PasswordInput id="pw-confirm" autoComplete="new-password" invalid={!!pw.formState.errors.confirmPassword} {...pw.register('confirmPassword')} />
              </Field>
              <Button type="submit" loading={pw.formState.isSubmitting}>Update password</Button>
            </form>
          </Panel>
          <Panel title="Sessions">
            <p className="text-sm text-muted">Signed in on a shared or lost device? Sign out everywhere — every session, including this one, ends immediately.</p>
            <div className="mt-4 flex flex-wrap gap-2">
              <Button variant="soft-danger" icon={<LogOut className="size-4" />} onClick={async () => {
                try { await authApi.logoutAll(); } catch { /* ignore */ }
                await logout();
              }}>Sign out of all devices</Button>
            </div>
          </Panel>
        </div>
      </div>
    </>
  );
}
