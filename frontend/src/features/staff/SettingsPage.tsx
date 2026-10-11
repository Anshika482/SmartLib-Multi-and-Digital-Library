import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Building2, Cog, ImageUp, Scale } from 'lucide-react';
import { isApiError } from '@/api/http';
import type { LibraryProfileInput, PolicyInput } from '@/api/types';
import { LibraryArt } from '@/components/book';
import { QueryState } from '@/components/states';
import { Alert, Button, PageHeader, Panel } from '@/components/ui';
import { Field, FileUpload, Input, Textarea } from '@/components/form';
import { useStaff } from './useStaff';

export default function SettingsPage() {
  const { api, libraryId } = useStaff();
  const lib = useQuery({ queryKey: ['manage', libraryId, 'library'], queryFn: api.library });
  return (
    <>
      <PageHeader title="Library settings" subtitle="Public profile and borrowing policy. Changes apply immediately and are audited." icon={<Cog className="size-5" />} />
      <QueryState query={lib}>{(l) => (
        <div className="grid gap-6 xl:grid-cols-2">
          <ProfileForm initial={l} />
          <div className="space-y-6">
            <PolicyForm initial={l.policy} />
            <ImageForm library={l} />
          </div>
        </div>
      )}</QueryState>
    </>
  );
}

function ProfileForm({ initial }: { initial: LibraryProfileInput }) {
  const { api, libraryId } = useStaff();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const { register, handleSubmit, reset, formState: { isSubmitting } } = useForm<LibraryProfileInput>({ defaultValues: initial });
  useEffect(() => reset(initial), [initial, reset]);
  const submit = handleSubmit(async (v) => {
    setError(null);
    try {
      await api.updateProfile(v);
      toast.success('Library profile saved');
      qc.invalidateQueries({ queryKey: ['manage', libraryId] });
      qc.invalidateQueries({ queryKey: ['public'] });
    } catch (e) {
      setError(isApiError(e) ? (e.fieldErrors[0] ? `${e.fieldErrors[0].field}: ${e.fieldErrors[0].message}` : e.message) : 'Could not save');
    }
  });
  const f = (name: keyof LibraryProfileInput, label: string, extra?: object) => (
    <Field label={label} htmlFor={`lp-${name}`}><Input id={`lp-${name}`} {...register(name)} {...extra} /></Field>
  );
  return (
    <Panel title="Public profile" icon={<Building2 className="size-5" />}>
      <form className="grid gap-4 sm:grid-cols-2" onSubmit={submit} noValidate>
        <div className="sm:col-span-2">{f('name', 'Library name')}</div>
        <div className="sm:col-span-2">{f('tagline', 'Tagline')}</div>
        <Field label="Description" htmlFor="lp-description" className="sm:col-span-2"><Textarea id="lp-description" rows={4} {...register('description')} /></Field>
        <div className="sm:col-span-2">{f('addressLine', 'Address')}</div>
        {f('city', 'City')}{f('state', 'State')}{f('postalCode', 'Postal code')}{f('openingHours', 'Opening hours')}
        {f('publicEmail', 'Public email', { type: 'email' })}{f('publicPhone', 'Public phone')}
        {f('website', 'Website')}{f('timezone', 'Time zone')}
        {error && <div className="sm:col-span-2"><Alert tone="error">{error}</Alert></div>}
        <div className="sm:col-span-2"><Button type="submit" loading={isSubmitting}>Save profile</Button></div>
      </form>
    </Panel>
  );
}

function PolicyForm({ initial }: { initial: PolicyInput }) {
  const { api, libraryId } = useStaff();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const toForm = (p: PolicyInput) => ({ ...p, finePerDay: p.finePerDayPaise / 100, maxFine: p.maxFinePerLoanPaise / 100, block: p.blockThresholdPaise / 100 });
  type F = ReturnType<typeof toForm>;
  const { register, handleSubmit, reset, formState: { isSubmitting } } = useForm<F>({ defaultValues: toForm(initial) });
  useEffect(() => reset(toForm(initial)), [initial, reset]);
  const submit = handleSubmit(async (v) => {
    setError(null);
    try {
      await api.updatePolicy({
        loanPeriodDays: Number(v.loanPeriodDays), maxActiveLoans: Number(v.maxActiveLoans), maxPendingRequests: Number(v.maxPendingRequests),
        pickupWindowDays: Number(v.pickupWindowDays), graceDays: Number(v.graceDays), finePerDayPaise: Math.round(Number(v.finePerDay) * 100),
        maxFinePerLoanPaise: Math.round(Number(v.maxFine) * 100), blockThresholdPaise: Math.round(Number(v.block) * 100),
      });
      toast.success('Borrowing policy saved');
      qc.invalidateQueries({ queryKey: ['manage', libraryId] });
    } catch (e) {
      setError(isApiError(e) ? (e.fieldErrors[0] ? `${e.fieldErrors[0].field}: ${e.fieldErrors[0].message}` : e.message) : 'Could not save');
    }
  });
  const n = (name: keyof F, label: string, hint?: string) => (
    <Field label={label} htmlFor={`pol-${name}`} hint={hint}><Input id={`pol-${name}`} type="number" min={0} step="any" {...register(name)} /></Field>
  );
  return (
    <Panel title="Borrowing policy" icon={<Scale className="size-5" />}>
      <form className="grid gap-4 sm:grid-cols-2" onSubmit={submit} noValidate>
        {n('loanPeriodDays', 'Loan period (days)')}{n('pickupWindowDays', 'Pickup window (days)')}
        {n('maxActiveLoans', 'Max books per member')}{n('maxPendingRequests', 'Max pending requests')}
        {n('finePerDay', 'Fine per day (₹)')}{n('graceDays', 'Grace days')}
        {n('maxFine', 'Max fine per loan (₹)', '0 = no cap')}{n('block', 'Block requests above unpaid (₹)')}
        {error && <div className="sm:col-span-2"><Alert tone="error">{error}</Alert></div>}
        <div className="sm:col-span-2"><Button type="submit" loading={isSubmitting}>Save policy</Button></div>
      </form>
    </Panel>
  );
}

function ImageForm({ library }: { library: { name: string; code: string; imageUrl: string | null } }) {
  const { api, libraryId } = useStaff();
  const qc = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const upload = useMutation({ mutationFn: (f: File) => api.uploadImage(f), onSuccess: () => { toast.success('Image updated'); setFile(null); qc.invalidateQueries({ queryKey: ['manage', libraryId] }); qc.invalidateQueries({ queryKey: ['public'] }); } });
  return (
    <Panel title="Library image" icon={<ImageUp className="size-5" />}>
      <div className="mb-4 h-36 overflow-hidden rounded-xl"><LibraryArt library={library} /></div>
      <FileUpload id="lib-image" accept="image/jpeg,image/png,image/webp" maxMb={3} file={file} onFile={setFile} hint="JPEG, PNG or WebP · max 3 MB" />
      {upload.error && <Alert tone="error" className="mt-3">{isApiError(upload.error) ? upload.error.message : 'Upload failed'}</Alert>}
      <Button className="mt-4" disabled={!file} loading={upload.isPending} onClick={() => file && upload.mutate(file)}>Upload image</Button>
    </Panel>
  );
}
