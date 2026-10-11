import { useState } from 'react';
import { Link } from 'react-router';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Download, ExternalLink, FolderOpen, Search } from 'lucide-react';
import { meApi } from '@/api/endpoints';
import { isApiError } from '@/api/http';
import { ResourceTypeIcon } from '@/components/book';
import { EmptyState, QueryState } from '@/components/states';
import { Pagination } from '@/components/table';
import { Badge, Button, PageHeader, Segmented } from '@/components/ui';
import { Input } from '@/components/form';
import { bytes, date } from '@/lib/format';

export default function MyResourcesPage() {
  const [type, setType] = useState<'ALL' | 'PDF' | 'EPUB' | 'VIDEO' | 'LINK'>('ALL');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const resources = useQuery({ queryKey: ['me', 'resources', type, q, page], queryFn: () => meApi.resources({ type: type === 'ALL' ? undefined : type, q: q || undefined, page }), placeholderData: keepPreviousData });
  const open = useMutation({
    mutationFn: (id: number) => meApi.access(id),
    onSuccess: (g) => window.open(g.url, '_blank', 'noopener,noreferrer'),
    onError: (e) => toast.error(isApiError(e) ? e.message : 'Could not open this resource.'),
  });
  return (
    <>
      <PageHeader title="Digital Resources" subtitle="PDFs, eBooks, videos and links you can access — public ones and those from libraries you belong to." icon={<FolderOpen className="size-5" />} />
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Segmented label="Resource type" value={type} onChange={(v) => { setType(v); setPage(0); }}
          options={[{ value: 'ALL', label: 'All' }, { value: 'PDF', label: 'PDF' }, { value: 'EPUB', label: 'EPUB' }, { value: 'VIDEO', label: 'Video' }, { value: 'LINK', label: 'Links' }]} />
        <div className="w-full max-w-xs">
          <label htmlFor="res-search" className="sr-only">Search resources</label>
          <Input id="res-search" leading={<Search className="size-4" />} placeholder="Search resources…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
        </div>
      </div>
      <QueryState query={resources} isEmpty={(d) => d.content.length === 0}
        empty={<div className="glass"><EmptyState icon={<FolderOpen className="size-6" />} title="No resources found" description={<>Join a library from its <Link to="/libraries" className="link">page</Link> to unlock members-only resources.</>} /></div>}>
        {(d) => (
          <>
            <ul className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
              {d.content.map((r) => (
                <li key={r.id} className="glass glass-hover flex flex-col p-4">
                  <div className="flex items-start gap-3">
                    <span className="flex size-11 shrink-0 items-center justify-center rounded-xl border border-line bg-ink/60"><ResourceTypeIcon type={r.type} /></span>
                    <div className="min-w-0">
                      <p className="font-semibold">{r.title}</p>
                      <p className="text-xs text-faint">{r.libraryName}{r.bookTitle ? ` · ${r.bookTitle}` : ''}</p>
                    </div>
                  </div>
                  {r.description && <p className="mt-3 line-clamp-3 text-sm text-muted">{r.description}</p>}
                  <div className="mt-auto flex flex-wrap items-center gap-2 pt-4">
                    <Badge tone="blue">{r.type}</Badge>
                    <Badge tone={r.visibility === 'PUBLIC' ? 'green' : r.visibility === 'REGISTERED' ? 'cyan' : 'violet'}>
                      {r.visibility === 'PUBLIC' ? 'Public' : r.visibility === 'REGISTERED' ? 'Members of SmartLib' : 'Library members'}
                    </Badge>
                    {r.sizeBytes && <span className="text-xs text-faint">{bytes(r.sizeBytes)}</span>}
                    <span className="ml-auto text-xs text-faint">{date(r.createdAt)}</span>
                  </div>
                  <Button size="sm" variant="outline" className="mt-3" loading={open.isPending && open.variables === r.id} onClick={() => open.mutate(r.id)}
                    icon={r.type === 'EPUB' ? <Download className="size-4" /> : <ExternalLink className="size-4" />}>
                    {r.type === 'EPUB' ? 'Download' : r.type === 'LINK' ? 'Open link' : 'Open'}
                  </Button>
                </li>
              ))}
            </ul>
            <Pagination page={d.page} totalPages={d.totalPages} size={d.size} totalElements={d.totalElements} onChange={setPage} label="resources" />
          </>
        )}
      </QueryState>
    </>
  );
}
