import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ImageUp, Pencil, Plus, Trash2 } from 'lucide-react';
import { isApiError } from '@/api/http';
import type { CopyRow, CopyStatus } from '@/api/types';
import { BookCover } from '@/components/book';
import { ConfirmDialog, Modal } from '@/components/modal';
import { QueryState } from '@/components/states';
import { DataTable } from '@/components/table';
import { Alert, Badge, Button, KeyValue, PageHeader, Panel } from '@/components/ui';
import { FileUpload, Field, Input, Select } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { date } from '@/lib/format';
import { BookForm, toBookInput, type BookFormValues } from './BookForm';
import { useStaff } from './useStaff';

export default function HoldingDetailPage() {
  const { holdingId } = useParams();
  const id = Number(holdingId);
  const { api, base, libraryId } = useStaff();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const detail = useQuery({ queryKey: ['manage', libraryId, 'holding', id], queryFn: () => api.holding(id) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['manage', libraryId] });
  const [editing, setEditing] = useState(false);
  const [coverOpen, setCoverOpen] = useState(false);
  const [cover, setCover] = useState<File | null>(null);
  const [withdraw, setWithdraw] = useState(false);
  const [copyCount, setCopyCount] = useState('1');
  const [shelf, setShelf] = useState<string | null>(null);
  const [copyEdit, setCopyEdit] = useState<{ copy: CopyRow; status: CopyStatus } | null>(null);

  const addCopies = useMutation({ mutationFn: () => api.addCopies(id, Number(copyCount)), onSuccess: () => { toast.success('Copies added'); refresh(); }, onError: (e) => toast.error(isApiError(e) ? e.message : 'Failed') });
  const saveShelf = useMutation({ mutationFn: (s: string) => api.updateHolding(id, s), onSuccess: () => { toast.success('Shelf location saved'); setShelf(null); refresh(); } });
  const uploadCover = useMutation({ mutationFn: (f: File) => api.uploadCover(detail.data!.holding.book.id, f),
    onSuccess: () => { toast.success('Cover uploaded'); setCoverOpen(false); setCover(null); refresh(); qc.invalidateQueries({ queryKey: ['book'] }); } });

  return (
    <QueryState query={detail}>
      {(d) => {
        const b = d.holding.book;
        const defaults: Partial<BookFormValues> = {
          title: b.title, subtitle: b.subtitle ?? '', authors: b.authors.join(', '), isbn: b.isbn13 ?? '', categoryId: d.categoryId ? String(d.categoryId) : '',
          publisher: d.book?.publisher ?? '', publishedYear: d.book?.publishedYear ? String(d.book.publishedYear) : '', language: d.book?.language ?? '',
          pageCount: d.book?.pageCount ? String(d.book.pageCount) : '', description: d.description ?? '',
        };
        return (
          <>
            <PageHeader title={b.title} subtitle={b.authors.join(', ')} crumbs={[{ label: 'Books & copies', to: `${base}/books` }, { label: b.title }]}
              actions={<>
                {d.canEditBook && <Button variant="outline" icon={<Pencil className="size-4" />} onClick={() => setEditing(true)}>Edit details</Button>}
                {d.canEditBook && <Button variant="outline" icon={<ImageUp className="size-4" />} onClick={() => setCoverOpen(true)}>Upload cover</Button>}
                {d.holding.status === 'ACTIVE' && <Button variant="soft-danger" icon={<Trash2 className="size-4" />} onClick={() => setWithdraw(true)}>Withdraw title</Button>}
              </>} />
            {!d.canEditBook && <Alert tone="info" className="mb-5">This title is shared with {d.librariesHoldingBook - 1} other {d.librariesHoldingBook - 1 === 1 ? 'library' : 'libraries'}, so its bibliographic details are managed by the platform administrator. You can still manage your copies and shelf location.</Alert>}
            <div className="grid gap-6 xl:grid-cols-[260px_1fr]">
              <div className="space-y-4">
                <BookCover book={b} className="mx-auto w-full max-w-[220px]" rounded="rounded-xl" />
                <Panel>
                  <KeyValue className="!grid-cols-2" items={[
                    { label: 'Total', value: d.holding.total }, { label: 'Available', value: <span className="text-success">{d.holding.available}</span> },
                    { label: 'On loan', value: d.holding.borrowed }, { label: 'Reserved', value: d.holding.reserved },
                    { label: 'Lost / damaged', value: d.holding.unavailable }, { label: 'Pending requests', value: d.holding.pendingRequests },
                  ]} />
                </Panel>
              </div>
              <div className="space-y-6">
                <Panel title="Details">
                  <KeyValue className="lg:grid-cols-3" items={[
                    { label: 'ISBN-13', value: b.isbn13 ? <span className="font-mono">{b.isbn13}</span> : '—' },
                    { label: 'Category', value: b.category }, { label: 'Status', value: <StatusBadge status={d.holding.status === 'ACTIVE' ? 'AVAILABLE' : 'WITHDRAWN'} /> },
                    { label: 'Publisher', value: d.book?.publisher }, { label: 'Year', value: d.book?.publishedYear }, { label: 'Added', value: date(d.holding.addedAt) },
                  ]} />
                  <form className="mt-5 flex flex-wrap items-end gap-2 border-t border-line pt-4" onSubmit={(e) => { e.preventDefault(); saveShelf.mutate(shelf ?? ''); }}>
                    <Field label="Shelf location" htmlFor="shelf" className="min-w-52 flex-1"><Input id="shelf" value={shelf ?? d.holding.shelfLocation ?? ''} onChange={(e) => setShelf(e.target.value)} /></Field>
                    <Button type="submit" variant="outline" loading={saveShelf.isPending} disabled={shelf === null}>Save</Button>
                  </form>
                </Panel>
                <Panel title="Physical copies" actions={d.holding.status === 'ACTIVE' && (
                  <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); addCopies.mutate(); }}>
                    <label htmlFor="copy-count" className="sr-only">Number of copies to add</label>
                    <Input id="copy-count" type="number" min={1} max={100} value={copyCount} onChange={(e) => setCopyCount(e.target.value)} className="h-9 w-20" />
                    <Button type="submit" size="sm" icon={<Plus className="size-4" />} loading={addCopies.isPending}>Add copies</Button>
                  </form>)}>
                  <DataTable caption="Copies" dense rows={d.copies} rowKey={(c) => c.id} columns={[
                    { key: 'acc', header: 'Accession no.', primary: true, cell: (c) => <span className="font-mono text-fg">{c.accessionNo}</span> },
                    { key: 'status', header: 'Status', cell: (c) => <StatusBadge status={c.status} /> },
                    { key: 'note', header: 'Condition note', cell: (c) => c.conditionNote ?? '—' },
                    { key: 'acquired', header: 'Acquired', cell: (c) => date(c.acquiredAt) },
                    { key: 'actions', header: 'Change status', align: 'right', cell: (c) => ['AVAILABLE', 'DAMAGED', 'LOST'].includes(c.status) ? (
                      <Select aria-label={`Change status of ${c.accessionNo}`} value="" className="h-8 w-40 py-0 text-xs"
                        onChange={(e) => e.target.value && setCopyEdit({ copy: c, status: e.target.value as CopyStatus })}>
                        <option value="">Change…</option>
                        {(['AVAILABLE', 'DAMAGED', 'LOST', 'WITHDRAWN'] as CopyStatus[]).filter((s) => s !== c.status).map((s) => <option key={s} value={s}>Mark {s.toLowerCase()}</option>)}
                      </Select>) : <Badge tone="gray">Managed by loans</Badge> },
                  ]} />
                </Panel>
              </div>
            </div>

            <Modal open={editing} onOpenChange={setEditing} title="Edit book details" size="lg"
              footer={<><Button variant="ghost" onClick={() => setEditing(false)}>Cancel</Button><Button type="submit" form="edit-book">Save changes</Button></>}>
              <BookForm id="edit-book" defaults={defaults} onSubmit={async (v, setFieldError) => {
                try {
                  await api.updateBook(b.id, toBookInput(v));
                  toast.success('Book details updated');
                  setEditing(false);
                  refresh();
                } catch (e) {
                  if (isApiError(e)) {
                    e.fieldErrors.forEach((f) => setFieldError(f.field, f.message));
                    if (!e.fieldErrors.length) toast.error(e.message);
                  }
                }
              }} />
            </Modal>
            <Modal open={coverOpen} onOpenChange={setCoverOpen} title="Upload a cover" description="JPEG, PNG or WebP up to 2 MB. Local covers take priority over ISBN covers."
              footer={<><Button variant="ghost" onClick={() => setCoverOpen(false)}>Cancel</Button><Button disabled={!cover} loading={uploadCover.isPending} onClick={() => cover && uploadCover.mutate(cover)}>Upload</Button></>}>
              <FileUpload id="cover-file" accept="image/jpeg,image/png,image/webp" maxMb={2} file={cover} onFile={setCover} />
              {uploadCover.error && <Alert tone="error" className="mt-3">{isApiError(uploadCover.error) ? uploadCover.error.message : 'Upload failed'}</Alert>}
            </Modal>
            <ConfirmDialog open={withdraw} onOpenChange={setWithdraw} tone="danger" confirmLabel="Withdraw title" title="Withdraw this title?"
              description="It disappears from your library's public catalogue and its copies are marked withdrawn. Titles with loans, reservations or open requests can't be withdrawn."
              onConfirm={async () => { await api.withdrawHolding(id); toast.success('Title withdrawn'); refresh(); navigate(`${base}/books`); }} />
            <ConfirmDialog open={!!copyEdit} onOpenChange={(o) => !o && setCopyEdit(null)} title={`Mark copy ${copyEdit?.copy.accessionNo} as ${copyEdit?.status.toLowerCase()}?`}
              tone={copyEdit?.status === 'AVAILABLE' ? 'success' : 'danger'} reason={copyEdit?.status !== 'AVAILABLE' ? { label: 'Condition note', minLength: 0 } : undefined}
              onConfirm={async (note) => { await api.updateCopy(copyEdit!.copy.id, copyEdit!.status, note || undefined); toast.success('Copy updated'); refresh(); }} />
          </>
        );
      }}
    </QueryState>
  );
}
