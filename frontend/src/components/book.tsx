import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { BookOpen, Building2, Clock, FileText, MapPin, Sparkles } from 'lucide-react';
import { cn } from '@/lib/cn';
import { hashHue, num } from '@/lib/format';
import type { CatalogueItem, HoldingAvailability, LibraryCard as LibraryCardData } from '@/api/types';

interface CoverBook {
  title: string;
  authors?: string[];
  coverUrl?: string | null;
  isbnCoverUrl?: string | null;
}

/**
 * Cover priority: local upload -> ISBN-resolved cover -> direct ISBN lookup -> generated placeholder.
 * Images stay invisible until loaded and fall through on error or 1x1 stub images — never a broken-image icon.
 */
export function BookCover({ book, className, rounded = 'rounded-lg' }: { book: CoverBook; className?: string; rounded?: string }) {
  const candidates = useMemo(() => [book.coverUrl, book.isbnCoverUrl].filter((u): u is string => !!u), [book.coverUrl, book.isbnCoverUrl]);
  const [idx, setIdx] = useState(0);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    setIdx(0);
    setLoaded(false);
  }, [candidates]);
  const src = candidates[idx];
  const next = () => {
    setLoaded(false);
    setIdx((i) => i + 1);
  };
  return (
    <div className={cn('relative aspect-[2/3] overflow-hidden bg-surface shadow-[0_18px_40px_-20px_rgba(0,0,0,0.9)] [container-type:inline-size]', rounded, className)}>
      <CoverPlaceholder title={book.title} author={book.authors?.[0]} hidden={loaded} />
      {src && (
        <img key={src} src={src} alt={`Cover of ${book.title}`} loading="lazy" decoding="async" referrerPolicy="no-referrer"
          onLoad={(e) => (e.currentTarget.naturalWidth < 10 ? next() : setLoaded(true))} onError={next}
          className={cn('absolute inset-0 h-full w-full object-cover transition-opacity duration-500', loaded ? 'opacity-100' : 'opacity-0')} />
      )}
      <span className="pointer-events-none absolute inset-0 bg-gradient-to-r from-black/25 via-transparent to-white/5" aria-hidden />
    </div>
  );
}

function CoverPlaceholder({ title, author, hidden }: { title: string; author?: string; hidden: boolean }) {
  const hue = hashHue(title);
  return (
    <div aria-hidden className={cn('absolute inset-0 flex flex-col justify-between p-[9cqw] transition-opacity duration-500', hidden && 'opacity-0')}
      style={{ background: `linear-gradient(155deg, hsl(${hue} 62% 30%), hsl(${(hue + 40) % 360} 70% 12%))` }}>
      <span className="absolute inset-y-0 left-[5%] w-[3%] bg-white/10" />
      <BookOpen className="size-[16cqw] text-white/50" />
      <div>
        <p className="line-clamp-4 font-bold leading-tight text-white [font-size:11cqw]">{title}</p>
        {author && <p className="mt-[4cqw] line-clamp-2 text-white/70 [font-size:7.5cqw]">{author}</p>}
      </div>
    </div>
  );
}

export function AvailabilityStrip({ holdings, max = 3 }: { holdings: HoldingAvailability[]; max?: number }) {
  if (!holdings.length) return <p className="text-xs text-faint">Not currently held by any library</p>;
  return (
    <ul className="space-y-1" aria-label="Availability by library">
      {holdings.slice(0, max).map((h) => (
        <li key={h.holdingId} className="flex items-center justify-between gap-2 text-xs">
          <span className="flex min-w-0 items-center gap-1.5 text-muted">
            <span className={cn('size-1.5 shrink-0 rounded-full', h.availableCopies > 0 ? 'bg-success' : 'bg-danger/80')} aria-hidden />
            <span className="truncate">{h.libraryName}</span>
          </span>
          <span className={cn('shrink-0 font-semibold', h.availableCopies > 0 ? 'text-success' : 'text-faint')}>
            {h.availableCopies > 0 ? `${h.availableCopies} available` : 'Unavailable'}
          </span>
        </li>
      ))}
      {holdings.length > max && <li className="text-xs text-faint">+ {holdings.length - max} more {holdings.length - max === 1 ? 'library' : 'libraries'}</li>}
    </ul>
  );
}

export function BookCard({ book, libraryId }: { book: CatalogueItem; libraryId?: number | null }) {
  const to = `/catalogue/${book.id}${libraryId ? `?library=${libraryId}` : ''}`;
  return (
    <article className="glass glass-hover group flex h-full flex-col p-3">
      <Link to={to} className="block rounded-lg" aria-label={`${book.title} — view details`}>
        <BookCover book={book} className="transition duration-300 group-hover:-translate-y-0.5" />
      </Link>
      <div className="mt-3 flex flex-1 flex-col">
        <h3 className="line-clamp-2 text-sm font-semibold leading-snug text-fg">
          <Link to={to} className="hover:text-accent-2">{book.title}</Link>
        </h3>
        <p className="mt-0.5 line-clamp-1 text-xs text-muted">{book.authors.join(', ') || 'Unknown author'}</p>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {book.category && <span className="chip !py-0.5 !text-[11px]">{book.category.name}</span>}
          {book.publishedYear && <span className="chip !py-0.5 !text-[11px]">{book.publishedYear}</span>}
        </div>
        <div className="mt-3 border-t border-line pt-2.5"><AvailabilityStrip holdings={book.holdings} /></div>
        <Link to={to} className="btn btn-outline btn-sm mt-3 w-full">View details</Link>
      </div>
    </article>
  );
}

export function BookCardSkeleton() {
  return (
    <div className="glass p-3" aria-hidden>
      <div className="skeleton aspect-[2/3] w-full" />
      <div className="skeleton mt-3 h-4 w-4/5" />
      <div className="skeleton mt-2 h-3 w-1/2" />
      <div className="skeleton mt-4 h-10 w-full" />
    </div>
  );
}

export function LibraryArt({ library, className }: { library: { name: string; code: string; imageUrl?: string | null }; className?: string }) {
  const hue = (hashHue(library.code) % 60) + 195;
  if (library.imageUrl) {
    return <img src={library.imageUrl} alt={`${library.name}`} className={cn('h-full w-full object-cover', className)} loading="lazy" />;
  }
  return (
    <div className={cn('relative h-full w-full overflow-hidden', className)} aria-hidden
      style={{ background: `radial-gradient(120% 90% at 20% 0%, hsl(${hue} 85% 45% / .55), transparent 60%), linear-gradient(160deg, hsl(${hue} 60% 18%), #071224)` }}>
      <div className="absolute inset-x-0 bottom-0 h-1/2 opacity-60"
        style={{ background: 'repeating-linear-gradient(90deg, rgb(10 25 50) 0 10px, rgb(40 70 120 / .8) 10px 12px, rgb(20 40 80) 12px 22px, rgb(70 100 150 / .5) 22px 24px)' }} />
      <Building2 className="absolute right-4 top-4 size-10 text-white/25" />
      <span className="absolute bottom-3 left-4 rounded-md bg-black/40 px-2 py-0.5 font-mono text-xs font-bold tracking-widest text-white/80">{library.code}</span>
    </div>
  );
}

export function LibraryCard({ library }: { library: LibraryCardData }) {
  return (
    <article className="glass glass-hover flex h-full flex-col overflow-hidden">
      <div className="h-32"><LibraryArt library={library} /></div>
      <div className="flex flex-1 flex-col p-4">
        <h3 className="text-base font-semibold text-fg">
          <Link to={`/libraries/${library.slug}`} className="hover:text-accent-2">{library.name}</Link>
        </h3>
        {library.tagline && <p className="text-xs text-accent-2">{library.tagline}</p>}
        <ul className="mt-3 space-y-1.5 text-xs text-muted">
          {(library.city || library.addressLine) && <li className="flex gap-1.5"><MapPin className="mt-px size-3.5 shrink-0 text-faint" aria-hidden />{[library.city, library.state].filter(Boolean).join(', ')}</li>}
          {library.openingHours && <li className="flex gap-1.5"><Clock className="mt-px size-3.5 shrink-0 text-faint" aria-hidden />{library.openingHours}</li>}
        </ul>
        <dl className="mt-4 grid grid-cols-3 gap-2 rounded-xl border border-line bg-ink/40 p-2.5 text-center">
          <div><dt className="text-[10px] uppercase tracking-wide text-faint">Titles</dt><dd className="text-sm font-bold">{num(library.titles)}</dd></div>
          <div><dt className="text-[10px] uppercase tracking-wide text-faint">Available</dt><dd className="text-sm font-bold text-success">{num(library.availableCopies)}</dd></div>
          <div><dt className="text-[10px] uppercase tracking-wide text-faint">Digital</dt><dd className="text-sm font-bold">{num(library.digitalResources)}</dd></div>
        </dl>
        <div className="mt-4 grid grid-cols-2 gap-2">
          <Link to={`/libraries/${library.slug}`} className="btn btn-outline btn-sm">View details</Link>
          <Link to={`/catalogue?library=${library.id}`} className="btn btn-primary btn-sm">Explore books</Link>
        </div>
      </div>
    </article>
  );
}

export function ResourceTypeIcon({ type, className }: { type: string; className?: string }) {
  const color = { PDF: 'text-rose-300', EPUB: 'text-sky-300', VIDEO: 'text-violet-300', LINK: 'text-emerald-300' }[type] ?? 'text-muted';
  return type === 'LINK' ? <Sparkles className={cn('size-5', color, className)} aria-hidden /> : <FileText className={cn('size-5', color, className)} aria-hidden />;
}
