import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Bot, Building2, CalendarDays, Clock, ExternalLink, Hash, Languages, MapPin, Sparkles, Tag } from 'lucide-react';
import { meApi, publicApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import type { BookDetails, HoldingDetail, ResourceSummary } from '@/api/types';
import { useSession } from '@/stores/session';
import { BookCard, BookCover, ResourceTypeIcon } from '@/components/book';
import { ErrorState } from '@/components/states';
import { Badge, Breadcrumbs, Button, KeyValue, Panel, Skeleton } from '@/components/ui';
import { StatusBadge } from '@/components/status';
import { money } from '@/lib/format';
import { cn } from '@/lib/cn';
import { RequestDialog } from './RequestDialog';

export default function BookDetailsPage() {
  const { bookId } = useParams();
  const id = Number(bookId);
  const [params, setParams] = useSearchParams();
  const highlight = Number(params.get('library')) || null;
  const { user } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const book = useQuery({ queryKey: ['book', id], queryFn: () => publicApi.book(id), enabled: Number.isFinite(id) });
  const isMember = user?.role === 'MEMBER';
  const myRequests = useQuery({
    queryKey: ['me', 'requests', { bookId: id, status: 'ACTIVE' }],
    queryFn: () => meApi.requests({ bookId: id, status: 'ACTIVE', size: 20 }),
    enabled: isMember && Number.isFinite(id),
  });
  const [requestFor, setRequestFor] = useState<HoldingDetail | null>(null);

  // Intent preserved through login: /catalogue/:id?request=<libraryId> opens the request dialog once.
  const opened = useRef(false);
  useEffect(() => {
    const wanted = Number(params.get('request'));
    if (!wanted || opened.current || !book.data || !isMember) return;
    const h = book.data.holdings.find((x) => x.libraryId === wanted);
    opened.current = true;
    if (h) setRequestFor(h);
    const p = new URLSearchParams(params);
    p.delete('request');
    setParams(p, { replace: true });
  }, [book.data, isMember, params, setParams]);

  if (book.isPending) return <DetailsSkeleton />;
  if (book.isError) return <div className="mx-auto max-w-4xl px-4 py-12"><ErrorState error={book.error} onRetry={() => book.refetch()} /></div>;
  const b = book.data;

  const requestClick = (h: HoldingDetail) => {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(`${location.pathname}?request=${h.libraryId}`)}`);
      return;
    }
    setRequestFor(h);
  };
  const pendingAt = new Map((myRequests.data?.content ?? []).map((r) => [r.library.id, r]));

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
      <Breadcrumbs items={[{ label: 'Catalogue', to: '/catalogue' }, ...(b.category ? [{ label: b.category.name, to: `/catalogue?category=${b.category.slug}` }] : []), { label: b.title }]} />
      <div className="mt-4 grid gap-8 lg:grid-cols-[300px_1fr]">
        <div className="mx-auto w-full max-w-[300px]">
          <div className="lg:sticky lg:top-24">
            <BookCover book={b} rounded="rounded-xl" className="shadow-[0_40px_80px_-30px_rgba(0,0,0,0.95),0_0_60px_-20px_rgba(57,197,255,0.5)]" />
            <div className="mt-4 rounded-xl border border-line bg-ink/40 p-3 text-center text-sm">
              {b.totalAvailable > 0
                ? <><b className="text-success">{b.totalAvailable}</b> {b.totalAvailable === 1 ? 'copy' : 'copies'} available across <b>{b.holdings.filter((h) => h.availableCopies > 0).length}</b> {b.holdings.length === 1 ? 'library' : 'libraries'}</>
                : <span className="text-muted">All copies are currently on loan</span>}
            </div>
            <Link to={`/assistant?q=${encodeURIComponent(`Tell me about "${b.title}" and which SmartLib libraries have it available`)}`} className="btn btn-outline mt-3 w-full">
              <Bot className="size-4" />Ask the AI about this book
            </Link>
          </div>
        </div>

        <div className="min-w-0 space-y-6">
          <div>
            {b.category && <Badge tone="cyan" icon={<Tag className="size-3" />}>{b.category.name}</Badge>}
            <h1 className="mt-3 text-3xl font-extrabold tracking-tight sm:text-4xl">{b.title}</h1>
            {b.subtitle && <p className="mt-1 text-lg text-muted">{b.subtitle}</p>}
            <p className="mt-2 text-base">by <span className="font-semibold text-accent-2">{b.authors.join(', ') || 'Unknown author'}</span></p>
          </div>

          <Panel>
            <KeyValue className="lg:grid-cols-3" items={[
              { label: 'ISBN-13', value: b.isbn13 ? <span className="inline-flex items-center gap-1 font-mono"><Hash className="size-3.5 text-faint" />{b.isbn13}</span> : '—' },
              { label: 'ISBN-10', value: b.isbn10 ? <span className="font-mono">{b.isbn10}</span> : '—' },
              { label: 'Publisher', value: b.publisher },
              { label: 'Published', value: b.publishedYear ? <span className="inline-flex items-center gap-1"><CalendarDays className="size-3.5 text-faint" />{b.publishedYear}</span> : '—' },
              { label: 'Language', value: b.language ? <span className="inline-flex items-center gap-1"><Languages className="size-3.5 text-faint" />{b.language}</span> : '—' },
              { label: 'Pages', value: b.pageCount },
            ]} />
            {b.description && <p className="mt-5 border-t border-line pt-4 text-sm leading-relaxed text-muted">{b.description}</p>}
          </Panel>

          <Panel title="Library availability" icon={<Building2 className="size-5" />}
            actions={<span className="text-xs text-faint">Choose the library you want to borrow from</span>}>
            <ul className="space-y-3">
              {b.holdings.map((h) => {
                const pending = pendingAt.get(h.libraryId);
                const available = h.availableCopies > 0;
                return (
                  <li key={h.holdingId} className={cn('rounded-xl border p-4 transition', highlight === h.libraryId ? 'border-accent-2/60 bg-accent/10' : 'border-line bg-ink/40')}>
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="min-w-0">
                        <Link to={`/libraries/${h.librarySlug}`} className="font-semibold hover:text-accent-2">{h.libraryName}</Link>
                        <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
                          {(h.city || h.addressLine) && <span className="inline-flex items-center gap-1"><MapPin className="size-3.5" />{h.city ?? h.addressLine}</span>}
                          {h.openingHours && <span className="inline-flex items-center gap-1"><Clock className="size-3.5" />{h.openingHours}</span>}
                          {h.shelfLocation && <span>Shelf: {h.shelfLocation}</span>}
                        </div>
                        <p className="mt-1.5 text-xs text-faint">Loan {h.loanPeriodDays} days · collect within {h.pickupWindowDays} days of approval · late fee {money(h.finePerDayPaise)}/day</p>
                      </div>
                      <div className="flex flex-col items-end gap-2">
                        {available
                          ? <Badge tone="green">{h.availableCopies} of {h.totalCopies} available</Badge>
                          : <Badge tone="red">Unavailable{h.totalCopies ? ` · ${h.totalCopies} on loan` : ''}</Badge>}
                        {pending ? (
                          <Link to="/app/member/requests" className="text-xs"><StatusBadge status={pending.status} /></Link>
                        ) : user && user.role !== 'MEMBER' ? (
                          <span className="text-xs text-faint">Staff accounts can't borrow</span>
                        ) : (
                          <Button size="sm" disabled={!available} onClick={() => requestClick(h)}>
                            {available ? `Request from ${h.libraryName}` : 'Unavailable'}
                          </Button>
                        )}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          </Panel>

          {b.digitalResources.length > 0 && <DigitalResources resources={b.digitalResources} />}
        </div>
      </div>

      {b.related.length > 0 && (
        <section className="mt-12" aria-labelledby="related">
          <h2 id="related" className="mb-4 flex items-center gap-2 text-xl font-bold"><Sparkles className="size-5 text-accent-2" />More in {b.category?.name}</h2>
          <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">{b.related.map((r) => <BookCard key={r.id} book={r} />)}</div>
        </section>
      )}
      <RequestDialog book={b as BookDetails} holding={requestFor} onClose={() => setRequestFor(null)} />
    </div>
  );
}

function DigitalResources({ resources }: { resources: ResourceSummary[] }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { user } = useSession();
  const [denied, setDenied] = useState<{ id: number; libraryId: number; message: string } | null>(null);
  const open = useMutation({
    mutationFn: (id: number) => meApi.access(id),
    onSuccess: (grant) => window.open(grant.url, '_blank', 'noopener,noreferrer'),
    onError: (e, id) => {
      if (isApiError(e) && e.code === 'LOGIN_REQUIRED') navigate(`/login?next=${encodeURIComponent(location.pathname)}`);
      else if (isApiError(e) && e.code === 'RESOURCE_ACCESS_DENIED') setDenied({ id, libraryId: Number(e.details.libraryId), message: e.message });
      else toast.error(isApiError(e) ? e.message : 'Could not open this resource.');
    },
  });
  const join = useMutation({
    mutationFn: (libraryId: number) => meApi.join(libraryId),
    onSuccess: (m) => {
      toast.success(`You joined ${m.libraryName}.`);
      qc.invalidateQueries({ queryKey: ['me'] });
      if (denied) open.mutate(denied.id);
      setDenied(null);
    },
    onError: (e) => toast.error(isApiError(e) ? e.message : 'Could not join the library.'),
  });
  return (
    <Panel title="Digital resources" icon={<ExternalLink className="size-5" />}>
      <ul className="space-y-2">
        {resources.map((r) => (
          <li key={r.id} className="flex flex-wrap items-center gap-3 rounded-xl border border-line bg-ink/40 p-3">
            <ResourceTypeIcon type={r.type} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold">{r.title}</p>
              <p className="text-xs text-faint">{r.type} · {r.libraryName} · {r.visibility === 'PUBLIC' ? 'Open to everyone' : r.visibility === 'REGISTERED' ? 'Signed-in users' : 'Library members'}</p>
            </div>
            <Button size="sm" variant="outline" loading={open.isPending && open.variables === r.id} onClick={() => open.mutate(r.id)}>Open</Button>
          </li>
        ))}
      </ul>
      {denied && (
        <div className="mt-3 rounded-xl border border-warning/30 bg-warning/10 p-3 text-sm">
          <p>{denied.message}</p>
          {user?.role === 'MEMBER' && <Button size="sm" className="mt-2" loading={join.isPending} onClick={() => join.mutate(denied.libraryId)}>Join library & open</Button>}
        </div>
      )}
    </Panel>
  );
}

function DetailsSkeleton() {
  return (
    <div className="mx-auto grid max-w-7xl gap-8 px-4 py-10 sm:px-6 lg:grid-cols-[300px_1fr]" aria-busy="true">
      <Skeleton className="aspect-[2/3] w-full max-w-[300px]" />
      <div className="space-y-4"><Skeleton className="h-10 w-2/3" /><Skeleton className="h-5 w-1/3" /><Skeleton className="h-40 w-full" /><Skeleton className="h-56 w-full" /></div>
    </div>
  );
}
