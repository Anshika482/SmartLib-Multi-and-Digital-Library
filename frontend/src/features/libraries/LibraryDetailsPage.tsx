import { useState, useEffect } from 'react';
import { Link, useParams } from 'react-router';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { BookOpen, Clock, Globe, Mail, MapPin, Phone, Search, UserPlus } from 'lucide-react';
import { meApi, publicApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { useSession } from '@/stores/session';
import { BookCard, BookCardSkeleton, LibraryArt } from '@/components/book';
import { EmptyState, ErrorState } from '@/components/states';
import { Pagination } from '@/components/table';
import { Badge, Breadcrumbs, Button, KeyValue, Panel, Skeleton } from '@/components/ui';
import { Input } from '@/components/form';
import { money, num } from '@/lib/format';

export default function LibraryDetailsPage() {
  const { slug = '' } = useParams();
  const { user } = useSession();
  const qc = useQueryClient();
  const details = useQuery({ queryKey: ['public', 'library', slug], queryFn: () => publicApi.library(slug) });
  const memberships = useQuery({ queryKey: ['me', 'memberships'], queryFn: meApi.memberships, enabled: user?.role === 'MEMBER' });
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => {
    const t = setTimeout(() => { setQ(text.trim()); setPage(0); }, 300);
    return () => clearTimeout(t);
  }, [text]);
  const libraryId = details.data?.library.id;
  const books = useQuery({
    queryKey: ['catalogue', 'library', libraryId, q, page],
    queryFn: ({ signal }) => publicApi.catalogue({ q: q || undefined, libraryId, page, size: 12 }, signal),
    enabled: !!libraryId, placeholderData: keepPreviousData,
  });
  const join = useMutation({
    mutationFn: () => meApi.join(libraryId!),
    onSuccess: (m) => { toast.success(`Welcome to ${m.libraryName}!`); qc.invalidateQueries({ queryKey: ['me', 'memberships'] }); },
    onError: (e) => toast.error(isApiError(e) ? e.message : 'Could not join this library.'),
  });

  if (details.isPending) return <div className="mx-auto max-w-7xl px-4 py-10 sm:px-6"><Skeleton className="h-64 w-full" /></div>;
  if (details.isError) return <div className="mx-auto max-w-4xl px-4 py-12"><ErrorState error={details.error} onRetry={() => details.refetch()} /></div>;
  const { library: l, policy } = details.data;
  const membership = memberships.data?.find((m) => m.libraryId === l.id);

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
      <Breadcrumbs items={[{ label: 'Libraries', to: '/libraries' }, { label: l.name }]} />
      <div className="glass mt-3 grid overflow-hidden lg:grid-cols-[1.1fr_1fr]">
        <div className="h-56 lg:h-auto"><LibraryArt library={l} /></div>
        <div className="p-6 sm:p-8">
          {l.tagline && <p className="eyebrow">{l.tagline}</p>}
          <h1 className="mt-1 text-3xl font-bold tracking-tight">{l.name}</h1>
          {l.description && <p className="mt-3 text-sm leading-relaxed text-muted">{l.description}</p>}
          <ul className="mt-5 space-y-2 text-sm text-muted">
            {(l.addressLine || l.city) && <li className="flex gap-2"><MapPin className="mt-0.5 size-4 shrink-0 text-accent-2" />{[l.addressLine, l.city, l.state, l.postalCode].filter(Boolean).join(', ')}</li>}
            {l.openingHours && <li className="flex gap-2"><Clock className="mt-0.5 size-4 shrink-0 text-accent-2" />{l.openingHours}</li>}
            {l.publicEmail && <li className="flex gap-2"><Mail className="mt-0.5 size-4 shrink-0 text-accent-2" /><a href={`mailto:${l.publicEmail}`} className="hover:text-fg">{l.publicEmail}</a></li>}
            {l.publicPhone && <li className="flex gap-2"><Phone className="mt-0.5 size-4 shrink-0 text-accent-2" /><a href={`tel:${l.publicPhone.replace(/\s/g, '')}`} className="hover:text-fg">{l.publicPhone}</a></li>}
            {l.website && <li className="flex gap-2"><Globe className="mt-0.5 size-4 shrink-0 text-accent-2" /><a href={l.website} target="_blank" rel="noopener noreferrer" className="hover:text-fg">{l.website}</a></li>}
          </ul>
          <div className="mt-6 flex flex-wrap gap-2">
            <Link to={`/catalogue?library=${l.id}`} className="btn btn-primary"><BookOpen className="size-4" />Explore all books</Link>
            {user?.role === 'MEMBER' && (membership
              ? <Badge tone={membership.status === 'ACTIVE' ? 'green' : 'red'} className="h-10 px-4">{membership.status === 'ACTIVE' ? 'You are a member' : 'Membership suspended'}</Badge>
              : <Button variant="outline" icon={<UserPlus className="size-4" />} loading={join.isPending} onClick={() => join.mutate()}>Join this library</Button>)}
            {!user && <Link to={`/register`} className="btn btn-outline"><UserPlus className="size-4" />Become a member</Link>}
          </div>
        </div>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_320px]">
        <section aria-labelledby="lib-books" className="min-w-0">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <h2 id="lib-books" className="text-xl font-bold">Books at {l.name}</h2>
            <div className="w-full max-w-xs">
              <label htmlFor="lib-book-search" className="sr-only">Search this library</label>
              <Input id="lib-book-search" leading={<Search className="size-4" />} placeholder="Search this library…" value={text} onChange={(e) => setText(e.target.value)} />
            </div>
          </div>
          {books.isError ? <ErrorState error={books.error} onRetry={() => books.refetch()} /> : !books.data ? (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-3">{Array.from({ length: 6 }, (_, i) => <BookCardSkeleton key={i} />)}</div>
          ) : books.data.content.length === 0 ? (
            <div className="glass"><EmptyState title={q ? `No books found for “${q}” here.` : 'No books yet.'} action={q ? <Button variant="outline" onClick={() => setText('')}>Clear search</Button> : undefined} /></div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-4 md:grid-cols-3">{books.data.content.map((b) => <BookCard key={b.id} book={b} libraryId={l.id} />)}</div>
              <Pagination page={books.data.page} totalPages={books.data.totalPages} size={books.data.size} totalElements={books.data.totalElements} onChange={setPage} label="books" />
            </>
          )}
        </section>
        <aside className="space-y-4">
          <Panel title="At a glance">
            <KeyValue className="!grid-cols-2" items={[
              { label: 'Titles', value: num(l.titles) },
              { label: 'Copies', value: num(l.copies) },
              { label: 'Available now', value: <span className="text-success">{num(l.availableCopies)}</span> },
              { label: 'Digital resources', value: num(l.digitalResources) },
            ]} />
          </Panel>
          {policy && (
            <Panel title="Borrowing rules">
              <KeyValue className="!grid-cols-1" items={[
                { label: 'Loan period', value: `${policy.loanPeriodDays} days` },
                { label: 'Books at a time', value: `Up to ${policy.maxActiveLoans}` },
                { label: 'Collect approved requests within', value: `${policy.pickupWindowDays} days` },
                { label: 'Late fee', value: `${money(policy.finePerDayPaise)} per day${policy.graceDays ? ` after ${policy.graceDays} grace day${policy.graceDays > 1 ? 's' : ''}` : ''}` },
              ]} />
            </Panel>
          )}
        </aside>
      </div>
    </div>
  );
}
