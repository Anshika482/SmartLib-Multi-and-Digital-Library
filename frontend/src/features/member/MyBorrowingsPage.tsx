import { Link, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { BookMarked, CalendarClock } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { BookCover } from '@/components/book';
import { EmptyState, QueryState } from '@/components/states';
import { Pagination } from '@/components/table';
import { LinkButton, PageHeader, Segmented } from '@/components/ui';
import { LoanStatusBadge } from '@/components/status';
import { date, daysUntil, money } from '@/lib/format';

type Filter = 'ACTIVE' | 'OVERDUE' | 'HISTORY' | 'ALL';

export default function MyBorrowingsPage() {
  const [params, setParams] = useSearchParams();
  const filter = (params.get('filter') as Filter) || 'ACTIVE';
  const page = Math.max(0, Number(params.get('page') ?? 1) - 1);
  const query = useQuery({ queryKey: ['me', 'borrowings', filter, page], queryFn: () => meApi.borrowings({ filter, page, size: 10 }), placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Borrowed Books" subtitle="Due dates, returns and history across all your libraries." icon={<BookMarked className="size-5" />} />
      <Segmented label="Filter loans" value={filter} onChange={(v) => setParams({ filter: v })} className="mb-5"
        options={[{ value: 'ACTIVE', label: 'On loan' }, { value: 'OVERDUE', label: 'Overdue' }, { value: 'HISTORY', label: 'Returned' }, { value: 'ALL', label: 'All' }]} />
      <QueryState query={query} isEmpty={(d) => d.content.length === 0}
        empty={<div className="glass"><EmptyState title={filter === 'OVERDUE' ? 'Nothing overdue — great job!' : 'No books here'} action={<LinkButton to="/catalogue">Browse catalogue</LinkButton>} /></div>}>
        {(d) => (
          <>
            <ul className="grid gap-4 md:grid-cols-2">
              {d.content.map((l) => {
                const left = l.status === 'ACTIVE' ? daysUntil(l.dueDate) : null;
                return (
                  <li key={l.id} className="glass flex gap-4 p-4">
                    <BookCover book={l.book} className="w-20 shrink-0" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-start justify-between gap-2">
                        <Link to={`/catalogue/${l.book.id}`} className="line-clamp-2 font-semibold hover:text-accent-2">{l.book.title}</Link>
                        <LoanStatusBadge loan={l} />
                      </div>
                      <p className="text-sm text-muted">{l.library.name}</p>
                      <dl className="mt-3 grid grid-cols-2 gap-2 text-xs">
                        <div><dt className="text-faint">Issued</dt><dd>{date(l.issuedAt)}</dd></div>
                        <div><dt className="text-faint">Due date</dt><dd className={l.overdue ? 'font-semibold text-danger' : ''}>{date(l.dueDate)}</dd></div>
                        {l.returnedAt && <div><dt className="text-faint">Returned</dt><dd>{date(l.returnedAt)}</dd></div>}
                        <div><dt className="text-faint">Copy</dt><dd className="font-mono">{l.accessionNo}</dd></div>
                      </dl>
                      {left != null && (
                        <p className={`mt-3 flex items-center gap-1.5 text-xs ${l.overdue ? 'text-danger' : l.dueSoon ? 'text-warning' : 'text-muted'}`}>
                          <CalendarClock className="size-3.5" />
                          {l.overdue ? `${l.overdueDays} days overdue · estimated fine so far ${money(l.estimatedFinePaise)}` : left === 0 ? 'Due today' : `Due in ${left} day${left === 1 ? '' : 's'}`}
                        </p>
                      )}
                      {l.status === 'ACTIVE' && <p className="mt-1 text-[11px] text-faint">Return it to the {l.library.name} desk — the librarian records the return.</p>}
                    </div>
                  </li>
                );
              })}
            </ul>
            <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} label="loans"
              onChange={(p) => setParams({ filter, page: String(p + 1) })} />
          </>
        )}
      </QueryState>
    </>
  );
}
