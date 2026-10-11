import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Archive, ExternalLink, FolderOpen, Plus, RotateCcw } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import type { ResourceType, ResourceVisibility } from '@/api/types';
import { ResourceTypeIcon } from '@/components/book';
import { Modal } from '@/components/modal';
import { EmptyState, QueryState } from '@/components/states';
import { DataTable, Pagination } from '@/components/table';
import { Alert, Badge, Button, PageHeader, Segmented } from '@/components/ui';
import { FileUpload, Field, Input, Select, Textarea } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { bytes, date } from '@/lib/format';
import { useStaff } from './useStaff';
import { useDebounced } from './useDebounced';

const visibilityLabel: Record<ResourceVisibility, string> = { PUBLIC: 'Everyone', REGISTERED: 'Signed-in users', LIBRARY_MEMBERS: 'Library members only' };

export default function StaffResourcesPage() {
  const { api, libraryId } = useStaff();
  const qc = useQueryClient();
  const [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED' | 'ALL'>('ACTIVE');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const list = useQuery({ queryKey: ['manage', libraryId, 'resources', status, page], queryFn: () => api.resources({ status, page }), placeholderData: keepPreviousData });
  const update = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Parameters<typeof api.updateResource>[1] }) => api.updateResource(id, body),
    onSuccess: () => { toast.success('Resource updated'); qc.invalidateQueries({ queryKey: ['manage', libraryId, 'resources'] }); },
    onError: (e) => toast.error(isApiError(e) ? e.message : 'Update failed'),
  });
  const open = useMutation({ mutationFn: (id: number) => meApi.access(id), onSuccess: (g) => window.open(g.url, '_blank', 'noopener,noreferrer') });
  return (
    <>
      <PageHeader title="Digital resources" subtitle="PDFs, EPUBs, videos and links. Access is checked on every open; files are served through short-lived signed links." icon={<FolderOpen className="size-5" />}
        actions={<Button icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>Add resource</Button>} />
      <Segmented label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} className="mb-5" options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'ARCHIVED', label: 'Archived' }, { value: 'ALL', label: 'All' }]} />
      <div className="glass p-4">
        <QueryState query={list} isEmpty={(d) => d.content.length === 0} empty={<EmptyState icon={<FolderOpen className="size-6" />} title="No resources yet" action={<Button onClick={() => setCreating(true)}>Add resource</Button>} />}>
          {(d) => (
            <>
              <DataTable caption="Digital resources" rows={d.content} rowKey={(r) => r.id} columns={[
                { key: 'title', header: 'Resource', primary: true, cell: (r) => <div className="flex items-center gap-3"><ResourceTypeIcon type={r.type} /><div><p className="font-medium text-fg">{r.title}</p><p className="text-xs text-faint">{r.bookTitle ?? 'Not linked to a book'}{r.sizeBytes ? ` · ${bytes(r.sizeBytes)}` : ''}</p></div></div> },
                { key: 'type', header: 'Type', cell: (r) => <Badge tone="blue">{r.type}</Badge> },
                { key: 'vis', header: 'Who can open', cell: (r) => (
                  <Select aria-label={`Visibility of ${r.title}`} value={r.visibility} className="h-8 w-48 py-0 text-xs" onChange={(e) => update.mutate({ id: r.id, body: { visibility: e.target.value as ResourceVisibility } })}>
                    {(Object.keys(visibilityLabel) as ResourceVisibility[]).map((v) => <option key={v} value={v}>{visibilityLabel[v]}</option>)}
                  </Select>) },
                { key: 'added', header: 'Added', cell: (r) => date(r.createdAt) },
                { key: 'status', header: 'Status', cell: (r) => <StatusBadge status={r.status === 'ACTIVE' ? 'AVAILABLE' : 'ARCHIVED'} /> },
                { key: 'actions', header: '', align: 'right', cell: (r) => (
                  <div className="flex justify-end gap-1.5">
                    <Button size="sm" variant="ghost" icon={<ExternalLink className="size-3.5" />} onClick={() => open.mutate(r.id)}>Open</Button>
                    {r.status === 'ACTIVE'
                      ? <Button size="sm" variant="outline" icon={<Archive className="size-3.5" />} onClick={() => update.mutate({ id: r.id, body: { status: 'ARCHIVED' } })}>Archive</Button>
                      : <Button size="sm" variant="outline" icon={<RotateCcw className="size-3.5" />} onClick={() => update.mutate({ id: r.id, body: { status: 'ACTIVE' } })}>Restore</Button>}
                  </div>) },
              ]} />
              <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="resources" />
            </>
          )}
        </QueryState>
      </div>
      <CreateResourceDialog open={creating} onClose={() => setCreating(false)} />
    </>
  );
}

function CreateResourceDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { api, libraryId } = useStaff();
  const qc = useQueryClient();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState<ResourceType>('PDF');
  const [visibility, setVisibility] = useState<ResourceVisibility>('LIBRARY_MEMBERS');
  const [url, setUrl] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [q, setQ, debounced] = useDebounced('');
  const [bookId, setBookId] = useState('');
  const holdings = useQuery({ queryKey: ['manage', libraryId, 'holdings', 'pick', debounced], queryFn: () => api.holdings({ q: debounced || undefined, size: 50 }), enabled: open });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const needsFile = type === 'PDF' || type === 'EPUB';
  const submit = async () => {
    setError(null);
    setBusy(true);
    const form = new FormData();
    form.append('title', title);
    form.append('description', description);
    form.append('type', type);
    form.append('visibility', visibility);
    if (bookId) form.append('bookId', bookId);
    if (url && (type === 'LINK' || (type === 'VIDEO' && !file))) form.append('externalUrl', url);
    if (file && type !== 'LINK') form.append('file', file);
    try {
      await api.createResource(form);
      toast.success('Resource published');
      qc.invalidateQueries({ queryKey: ['manage', libraryId, 'resources'] });
      setTitle(''); setDescription(''); setUrl(''); setFile(null); setBookId('');
      onClose();
    } catch (e) {
      setError(isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : 'Upload failed.');
    } finally {
      setBusy(false);
    }
  };
  const valid = title.trim() && (needsFile ? !!file : type === 'LINK' ? !!url : !!file || !!url);
  return (
    <Modal open={open} onOpenChange={(o) => !o && !busy && onClose()} title="Add a digital resource" size="lg"
      footer={<><Button variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button><Button loading={busy} disabled={!valid} onClick={submit}>Publish</Button></>}>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Title" htmlFor="rs-title" required className="sm:col-span-2"><Input id="rs-title" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} /></Field>
        <Field label="Type" htmlFor="rs-type"><Select id="rs-type" value={type} onChange={(e) => { setType(e.target.value as ResourceType); setFile(null); }}>
          <option value="PDF">PDF document</option><option value="EPUB">EPUB eBook</option><option value="VIDEO">Video</option><option value="LINK">External link</option></Select></Field>
        <Field label="Who can open it" htmlFor="rs-vis"><Select id="rs-vis" value={visibility} onChange={(e) => setVisibility(e.target.value as ResourceVisibility)}>
          {(Object.keys(visibilityLabel) as ResourceVisibility[]).map((v) => <option key={v} value={v}>{visibilityLabel[v]}</option>)}</Select></Field>
        {type !== 'LINK' && (
          <div className="sm:col-span-2">
            <p className="mb-1.5 text-sm font-medium">File{type === 'VIDEO' ? ' (or use a video URL below)' : ''}</p>
            <FileUpload id="rs-file" file={file} onFile={setFile} maxMb={type === 'VIDEO' ? 200 : 50}
              accept={type === 'PDF' ? 'application/pdf' : type === 'EPUB' ? 'application/epub+zip,.epub' : 'video/mp4,video/webm'}
              hint={type === 'VIDEO' ? 'MP4 or WebM, max 200 MB' : `${type}, max 50 MB — the real file type is checked on upload`} />
          </div>
        )}
        {(type === 'LINK' || (type === 'VIDEO' && !file)) && (
          <Field label={type === 'LINK' ? 'URL' : 'Video URL'} htmlFor="rs-url" required={type === 'LINK'} className="sm:col-span-2"><Input id="rs-url" type="url" placeholder="https://…" value={url} onChange={(e) => setUrl(e.target.value)} /></Field>
        )}
        <Field label="Link to a book" htmlFor="rs-book" hint="Optional — shows on the book's page" className="sm:col-span-2">
          <div className="grid gap-2 sm:grid-cols-2">
            <Input aria-label="Search your books" placeholder="Search your books…" value={q} onChange={(e) => setQ(e.target.value)} />
            <Select id="rs-book" value={bookId} onChange={(e) => setBookId(e.target.value)}>
              <option value="">No book</option>
              {holdings.data?.content.map((h) => <option key={h.holdingId} value={h.book.id}>{h.book.title}</option>)}
            </Select>
          </div>
        </Field>
        <Field label="Description" htmlFor="rs-desc" className="sm:col-span-2"><Textarea id="rs-desc" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} /></Field>
        {error && <div className="sm:col-span-2"><Alert tone="error">{error}</Alert></div>}
      </div>
    </Modal>
  );
}
