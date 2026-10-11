import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { BookPlus, Search } from 'lucide-react';
import { isApiError } from '@/api/http';
import type { IsbnLookup } from '@/api/types';
import { Alert, Button, PageHeader, Panel } from '@/components/ui';
import { Field, Input } from '@/components/form';
import { BookForm, toBookInput, type BookFormValues } from './BookForm';
import { useStaff } from './useStaff';

export default function AddBookPage() {
  const { api, base, libraryId } = useStaff();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [isbn, setIsbn] = useState('');
  const [lookup, setLookup] = useState<IsbnLookup | null>(null);
  const [manual, setManual] = useState(false);
  const [copies, setCopies] = useState('1');
  const [shelf, setShelf] = useState('');
  const [error, setError] = useState<string | null>(null);
  const find = useMutation({ mutationFn: () => api.isbnLookup(isbn), onSuccess: (r) => { setLookup(r); setManual(false); setError(null); }, onError: (e) => setError(isApiError(e) ? e.message : 'Lookup failed') });

  const added = (holdingId: number) => {
    toast.success('Book added to your catalogue');
    qc.invalidateQueries({ queryKey: ['manage', libraryId] });
    navigate(`${base}/books/${holdingId}`);
  };
  const attach = useMutation({
    mutationFn: () => api.addHolding({ bookId: lookup!.existingBookId!, copies: Number(copies) || 0, shelfLocation: shelf || undefined }),
    onSuccess: (h) => added(h.holdingId), onError: (e) => setError(isApiError(e) ? e.message : 'Could not add the book'),
  });
  const create = async (v: BookFormValues, setFieldError: (f: string, m: string) => void) => {
    setError(null);
    try {
      const h = await api.addHolding({ isbn: v.isbn || undefined, book: toBookInput(v), copies: Number(copies) || 0, shelfLocation: shelf || undefined });
      added(h.holdingId);
    } catch (e) {
      if (!isApiError(e)) return setError('Could not add the book');
      e.fieldErrors.forEach((f) => setFieldError(f.field.replace(/^book\./, ''), f.message));
      if (e.code === 'HOLDING_EXISTS') navigate(`${base}/books/${e.details.holdingId}`);
      else if (!e.fieldErrors.length) setError(e.message);
    }
  };
  const meta = lookup?.metadata;
  const defaults: Partial<BookFormValues> = meta ? {
    title: meta.title, subtitle: meta.subtitle ?? '', authors: meta.authors.join(', '), publisher: meta.publisher ?? '', publishedYear: meta.publishedYear ? String(meta.publishedYear) : '',
    pageCount: meta.pageCount ? String(meta.pageCount) : '', description: meta.description ?? '', language: meta.language ?? '', isbn: lookup?.isbn13 ?? '',
  } : { isbn: lookup?.isbn13 ?? '' };

  const copiesFields = (
    <div className="grid gap-4 sm:col-span-2 sm:grid-cols-2">
      <Field label="Number of copies" htmlFor="copies" hint="Physical copies to register now (0–100)"><Input id="copies" type="number" min={0} max={100} value={copies} onChange={(e) => setCopies(e.target.value)} /></Field>
      <Field label="Shelf location" htmlFor="shelf" hint="Optional, e.g. Aisle 4 · J"><Input id="shelf" value={shelf} onChange={(e) => setShelf(e.target.value)} /></Field>
    </div>
  );

  return (
    <>
      <PageHeader title="Add a book" subtitle="Start with the ISBN — existing SmartLib titles are reused so availability stays per library." icon={<BookPlus className="size-5" />}
        crumbs={[{ label: 'Books & copies', to: `${base}/books` }, { label: 'Add book' }]} />
      <div className="grid gap-6 xl:grid-cols-[380px_1fr]">
        <Panel title="1. Look up by ISBN">
          <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); if (isbn.trim()) find.mutate(); }}>
            <Field label="ISBN-10 or ISBN-13" htmlFor="isbn-lookup"><Input id="isbn-lookup" value={isbn} onChange={(e) => setIsbn(e.target.value)} placeholder="978-0-13-235088-4" /></Field>
            <Button type="submit" className="w-full" loading={find.isPending} icon={<Search className="size-4" />}>Look up</Button>
          </form>
          <button type="button" className="mt-4 text-sm text-accent-2 hover:underline" onClick={() => { setManual(true); setLookup(null); }}>No ISBN? Enter details manually</button>
          {error && <Alert tone="error" className="mt-4">{error}</Alert>}
        </Panel>
        <div>
          {lookup?.existingBookId ? (
            <Panel title="2. Add to your library">
              {lookup.alreadyInLibrary ? (
                <Alert tone="info" title="Already in your catalogue">This ISBN is already held by your library.</Alert>
              ) : (
                <div className="space-y-4">
                  <Alert tone="success" title="Found in SmartLib">This title already exists in the shared SmartLib catalogue. Add your copies to it — other libraries' availability is unaffected.</Alert>
                  <Link to={`/catalogue/${lookup.existingBookId}`} target="_blank" className="link text-sm">Preview the public book page ↗</Link>
                  <div className="grid gap-4 sm:grid-cols-2">{copiesFields}</div>
                  <Button loading={attach.isPending} onClick={() => attach.mutate()} icon={<BookPlus className="size-4" />}>Add to my library</Button>
                </div>
              )}
            </Panel>
          ) : (lookup || manual) ? (
            <Panel title="2. Book details">
              {lookup && (meta ? <Alert tone="info" className="mb-4">Details prefilled from {meta.source}. Please check them before saving.</Alert>
                : <Alert tone="warning" className="mb-4">No public metadata found for this ISBN — please enter the details.</Alert>)}
              <BookForm id="new-book" defaults={defaults} onSubmit={create} isbnLocked={!!lookup}>{copiesFields}</BookForm>
              <Button type="submit" form="new-book" className="mt-5" icon={<BookPlus className="size-4" />}>Add book</Button>
            </Panel>
          ) : (
            <Panel><p className="text-sm text-muted">Look up an ISBN to check whether SmartLib already has this title, or enter the details manually.</p></Panel>
          )}
        </div>
      </div>
    </>
  );
}
