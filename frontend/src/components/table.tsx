import { useMemo, useState, type ReactNode } from 'react';
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/cn';
import { num } from '@/lib/format';

export interface Column<T> {
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  sortValue?: (row: T) => string | number;
  className?: string;
  /** Shown as the card heading on small screens. */
  primary?: boolean;
  align?: 'left' | 'right';
}

/** Table on desktop, stacked cards on phones (no horizontal page scroll). Sorting applies to the loaded page. */
export function DataTable<T>({ rows, columns, rowKey, caption, dense }:
  { rows: T[]; columns: Column<T>[]; rowKey: (row: T) => string | number; caption?: string; dense?: boolean }) {
  const [sort, setSort] = useState<{ key: string; dir: 1 | -1 } | null>(null);
  const sorted = useMemo(() => {
    if (!sort) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col?.sortValue) return rows;
    return [...rows].sort((a, b) => {
      const va = col.sortValue!(a);
      const vb = col.sortValue!(b);
      return (va < vb ? -1 : va > vb ? 1 : 0) * sort.dir;
    });
  }, [rows, columns, sort]);
  const primary = columns.find((c) => c.primary) ?? columns[0];
  return (
    <>
      <div className="hidden overflow-x-auto md:block">
        <table className="w-full border-separate border-spacing-0 text-sm">
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead>
            <tr>
              {columns.map((c) => {
                const active = sort?.key === c.key;
                return (
                  <th key={c.key} scope="col" aria-sort={active ? (sort!.dir === 1 ? 'ascending' : 'descending') : undefined}
                    className={cn('border-b border-line px-3 py-2.5 text-left text-xs font-semibold uppercase tracking-wide text-faint', c.align === 'right' && 'text-right', c.className)}>
                    {c.sortValue ? (
                      <button type="button" className="inline-flex items-center gap-1 hover:text-fg"
                        onClick={() => setSort(active ? (sort!.dir === 1 ? { key: c.key, dir: -1 } : null) : { key: c.key, dir: 1 })}>
                        {c.header}
                        {active ? (sort!.dir === 1 ? <ArrowUp className="size-3" /> : <ArrowDown className="size-3" />) : <ArrowUpDown className="size-3 opacity-50" />}
                      </button>
                    ) : c.header}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {sorted.map((row) => (
              <tr key={rowKey(row)} className="transition hover:bg-white/[0.025]">
                {columns.map((c) => (
                  <td key={c.key} className={cn('border-b border-line/70 px-3 align-middle text-muted', dense ? 'py-2' : 'py-3', c.align === 'right' && 'text-right', c.className)}>
                    {c.cell(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="space-y-3 md:hidden" aria-label={caption}>
        {sorted.map((row) => (
          <li key={rowKey(row)} className="glass-soft p-4">
            <div className="mb-3 text-sm font-semibold text-fg">{primary.cell(row)}</div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2.5">
              {columns.filter((c) => c !== primary).map((c) => (
                <div key={c.key} className={cn('min-w-0', c.key === 'actions' && 'col-span-2')}>
                  {c.key !== 'actions' && <dt className="text-[11px] uppercase tracking-wide text-faint">{c.header}</dt>}
                  <dd className="mt-0.5 break-words text-sm text-muted">{c.cell(row)}</dd>
                </div>
              ))}
            </dl>
          </li>
        ))}
      </ul>
    </>
  );
}

export function Pagination({ page, totalPages, size, totalElements, onChange, label = 'results' }:
  { page: number; totalPages: number; size: number; totalElements: number; onChange: (p: number) => void; label?: string }) {
  if (totalElements === 0) return null;
  const from = page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);
  const pages: (number | 'gap')[] = [];
  const window = 1;
  for (let i = 0; i < totalPages; i++) {
    if (i === 0 || i === totalPages - 1 || Math.abs(i - page) <= window) pages.push(i);
    else if (pages[pages.length - 1] !== 'gap') pages.push('gap');
  }
  return (
    <nav className="mt-6 flex flex-wrap items-center justify-between gap-3" aria-label="Pagination">
      <p className="text-sm text-muted">Showing <span className="font-semibold text-fg">{num(from)}–{num(to)}</span> of{' '}
        <span className="font-semibold text-fg">{num(totalElements)}</span> {label}</p>
      {totalPages > 1 && (
        <div className="flex items-center gap-1">
          <button type="button" className="btn btn-ghost btn-sm btn-icon" disabled={page === 0} onClick={() => onChange(page - 1)} aria-label="Previous page">
            <ChevronLeft className="size-4" />
          </button>
          {pages.map((p, i) => p === 'gap' ? <span key={`g${i}`} className="px-1 text-faint">…</span> : (
            <button key={p} type="button" onClick={() => onChange(p)} aria-current={p === page ? 'page' : undefined}
              className={cn('h-8 min-w-8 rounded-lg px-2 text-sm font-semibold transition', p === page ? 'bg-accent text-white shadow-[0_6px_18px_-8px_rgba(22,136,255,0.9)]' : 'text-muted hover:bg-white/5 hover:text-fg')}>
              {p + 1}
            </button>
          ))}
          <button type="button" className="btn btn-ghost btn-sm btn-icon" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)} aria-label="Next page">
            <ChevronRight className="size-4" />
          </button>
        </div>
      )}
    </nav>
  );
}
