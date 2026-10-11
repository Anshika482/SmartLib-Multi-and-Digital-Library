import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Megaphone, Send } from 'lucide-react';
import { isApiError } from '@/api/http';
import { Alert, Button, PageHeader, Panel } from '@/components/ui';
import { Checkbox, Field, Input, Textarea } from '@/components/form';
import { useStaff } from './useStaff';

export default function AnnouncementsPage() {
  const { api, libraryName } = useStaff();
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [email, setEmail] = useState(false);
  const [sent, setSent] = useState<number | null>(null);
  const send = useMutation({
    mutationFn: () => api.announce({ title, body, sendEmail: email }),
    onSuccess: (r) => { setSent(r.recipients); setTitle(''); setBody(''); toast.success(`Announcement sent to ${r.recipients} member(s)`); },
  });
  return (
    <>
      <PageHeader title="Announcements" subtitle={`Notify every member of ${libraryName} — e.g. holiday closures, new arrivals or events.`} icon={<Megaphone className="size-5" />} />
      <div className="grid gap-6 xl:grid-cols-[1.4fr_1fr]">
        <Panel title="New announcement">
          <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); setSent(null); send.mutate(); }}>
            {sent != null && <Alert tone="success">Delivered to {sent} member(s) as an in-app notification{email ? ' and email' : ''}.</Alert>}
            {send.error && <Alert tone="error">{isApiError(send.error) ? send.error.message : 'Could not send.'}</Alert>}
            <Field label="Title" htmlFor="an-title" required><Input id="an-title" value={title} maxLength={150} onChange={(e) => setTitle(e.target.value)} /></Field>
            <Field label="Message" htmlFor="an-body" required hint={`${body.length}/1500`}><Textarea id="an-body" rows={6} value={body} maxLength={1500} onChange={(e) => setBody(e.target.value)} /></Field>
            <Checkbox checked={email} onChange={(e) => setEmail(e.target.checked)} label="Also send by email" />
            <Button type="submit" icon={<Send className="size-4" />} loading={send.isPending} disabled={!title.trim() || !body.trim()}>Send announcement</Button>
          </form>
        </Panel>
        <Panel title="Who receives it?">
          <p className="text-sm text-muted">Members with an active membership at {libraryName}. Members join a library explicitly or automatically when they request a book there. Every announcement is recorded in the audit log.</p>
        </Panel>
      </div>
    </>
  );
}
