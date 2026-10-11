import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Cog, PlugZap } from 'lucide-react';
import { platformApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { QueryState } from '@/components/states';
import { Alert, Badge, Button, PageHeader, Panel } from '@/components/ui';
import { Field, Textarea } from '@/components/form';

export default function PlatformConfigPage() {
  const qc = useQueryClient();
  const config = useQuery({ queryKey: ['platform', 'config'], queryFn: platformApi.config });
  const [announcement, setAnnouncement] = useState('');
  useEffect(() => { if (config.data) setAnnouncement(config.data.settings['platform.announcement'] ?? ''); }, [config.data]);
  const save = useMutation({
    mutationFn: (changes: Record<string, string>) => platformApi.updateConfig(changes),
    onSuccess: (d) => { qc.setQueryData(['platform', 'config'], d); qc.invalidateQueries({ queryKey: ['public'] }); qc.invalidateQueries({ queryKey: ['ai-status'] }); toast.success('Configuration saved'); },
    onError: (e) => toast.error(isApiError(e) ? e.message : 'Could not save'),
  });
  return (
    <>
      <PageHeader title="Platform configuration" subtitle="Platform-wide switches. Secrets (API keys, Razorpay secret, SMTP password) live only in server environment variables." icon={<Cog className="size-5" />} />
      <QueryState query={config}>{(c) => {
        const toggle = (key: string, label: string, description: string) => {
          const on = c.settings[key] === 'true';
          return (
            <div className="flex items-start justify-between gap-4 rounded-xl border border-line bg-ink/40 p-4">
              <div><p className="font-medium">{label}</p><p className="text-sm text-muted">{description}</p></div>
              <button type="button" role="switch" aria-checked={on} aria-label={label} disabled={save.isPending} onClick={() => save.mutate({ [key]: on ? 'false' : 'true' })}
                className={`relative h-7 w-12 shrink-0 rounded-full transition ${on ? 'bg-accent' : 'bg-white/15'}`}>
                <span className={`absolute top-1 size-5 rounded-full bg-white transition ${on ? 'left-6' : 'left-1'}`} />
              </button>
            </div>
          );
        };
        return (
          <div className="grid gap-6 xl:grid-cols-2">
            <Panel title="Features">
              <div className="space-y-3">
                {toggle('ai.enabled', 'AI assistant', 'Allow members and visitors to use the Gemini-powered assistant.')}
                {toggle('registration.librarian.open', 'Librarian applications', 'Allow new librarian applications from the public registration page.')}
                {toggle('registration.admin.open', 'Admin applications', 'Allow people to apply to administer a library. Every application still needs your approval.')}
              </div>
              <form className="mt-5 space-y-3" onSubmit={(e) => { e.preventDefault(); save.mutate({ 'platform.announcement': announcement }); }}>
                <Field label="Site-wide announcement" htmlFor="announcement" hint="Shown on the homepage. Leave empty to hide."><Textarea id="announcement" rows={3} maxLength={500} value={announcement} onChange={(e) => setAnnouncement(e.target.value)} /></Field>
                <Button type="submit" loading={save.isPending}>Save announcement</Button>
              </form>
            </Panel>
            <Panel title="Integrations" icon={<PlugZap className="size-5" />}>
              <ul className="space-y-3 text-sm">
                <li className="flex items-center justify-between gap-3 rounded-xl border border-line bg-ink/40 p-3"><span>Google Gemini<span className="block text-xs text-faint">Model: {String(c.integrations.geminiModel ?? '—')}</span></span>
                  {c.integrations.geminiConfigured ? <Badge tone="green">Configured</Badge> : <Badge tone="amber">GEMINI_API_KEY missing</Badge>}</li>
                <li className="flex items-center justify-between gap-3 rounded-xl border border-line bg-ink/40 p-3"><span>Razorpay<span className="block text-xs text-faint">Mode: {String(c.integrations.razorpayMode)}</span></span>
                  {c.integrations.razorpayConfigured ? <Badge tone="green">Configured</Badge> : c.integrations.paymentsDemo ? <Badge tone="amber">Demo gateway</Badge> : <Badge tone="amber">Keys missing</Badge>}</li>
                <li className="flex items-center justify-between gap-3 rounded-xl border border-line bg-ink/40 p-3"><span>ISBN cover & metadata lookup<span className="block text-xs text-faint">Open Library, Google Books</span></span>
                  {c.integrations.coverLookupEnabled ? <Badge tone="green">Enabled</Badge> : <Badge tone="gray">Disabled</Badge>}</li>
              </ul>
              <Alert tone="info" className="mt-4">Integration keys are read from environment variables at startup and are never displayed or editable here.</Alert>
            </Panel>
          </div>
        );
      }}</QueryState>
    </>
  );
}
