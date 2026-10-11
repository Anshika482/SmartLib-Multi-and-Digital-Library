import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { CatalogueItem } from '@/api/types';
import { BookCover } from '@/components/book';
import { cn } from '@/lib/cn';

const reducedMotion = () => typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

/** 3D book carousel on a glowing platform (homepage reference), driven by real featured books. */
export function BookShowcase({ books, loading, error }: { books?: CatalogueItem[]; loading: boolean; error: boolean }) {
  const items = useMemo(() => (books ?? []).slice(0, 9), [books]);
  const [active, setActive] = useState(0);
  const [paused, setPaused] = useState(false);
  const n = items.length;

  useEffect(() => {
    if (n < 2 || paused || reducedMotion()) return;
    const t = setInterval(() => setActive((a) => (a + 1) % n), 5000);
    return () => clearInterval(t);
  }, [n, paused]);

  useEffect(() => {
    if (active >= n) setActive(0);
  }, [n, active]);

  const go = (d: number) => setActive((a) => (a + d + n) % n);

  return (
    <div className="relative mx-auto mt-6 h-[300px] max-w-5xl select-none sm:h-[360px] lg:h-[410px]"
      role="region" aria-roledescription="carousel" aria-label="Featured books"
      onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)} onFocus={() => setPaused(true)} onBlur={() => setPaused(false)}>
      {/* glowing stage */}
      <div className="absolute inset-x-[4%] bottom-6 h-[70px] rounded-[50%] border-2 border-accent-2/60 bg-[radial-gradient(ellipse_at_center,rgba(22,136,255,0.35),rgba(5,11,22,0.2)_60%,transparent_75%)] shadow-[0_0_40px_6px_rgba(22,136,255,0.45),inset_0_0_30px_rgba(57,197,255,0.35)] sm:h-[90px] lg:inset-x-[2%]" aria-hidden />
      <div className="absolute inset-x-[10%] bottom-2 h-[40px] rounded-[50%] bg-accent/30 blur-2xl animate-pulse-glow" aria-hidden />

      <div className="absolute inset-0 [perspective:1400px]">
        {loading && [-2, -1, 0, 1, 2].map((o) => (
          <div key={o} className="absolute bottom-[58px] left-1/2 w-[120px] sm:bottom-[72px] sm:w-[150px] lg:w-[178px]"
            style={{ transform: `translateX(calc(-50% + ${o * 112}%)) scale(${1 - Math.abs(o) * 0.1})` }} aria-hidden>
            <div className="skeleton aspect-[2/3] w-full" />
          </div>
        ))}
        {!loading && (error || n === 0) && (
          <p className="absolute inset-x-0 top-1/3 text-sm text-muted">{error ? 'Featured books are unavailable right now.' : 'Books will appear here once libraries add them.'}</p>
        )}
        {items.map((book, i) => {
          let offset = i - active;
          if (offset > n / 2) offset -= n;
          if (offset < -n / 2) offset += n;
          const abs = Math.abs(offset);
          const visible = abs <= 3;
          const center = offset === 0;
          return (
            <Link key={book.id} to={`/catalogue/${book.id}`} tabIndex={visible ? 0 : -1} aria-hidden={!visible}
              aria-label={`${book.title} by ${book.authors.join(', ')}${center ? '' : ' — show in centre'}`}
              onClick={(e) => {
                if (!center) {
                  e.preventDefault();
                  setActive(i);
                }
              }}
              className={cn('absolute bottom-[54px] left-1/2 w-[118px] transition-[transform,opacity,filter] duration-700 ease-[cubic-bezier(.2,.7,.2,1)] sm:bottom-[70px] sm:w-[148px] lg:w-[176px]',
                !visible && 'pointer-events-none')}
              style={{
                transform: `translateX(calc(-50% + ${offset * 108}%)) translateZ(${-abs * 70}px) rotateY(${offset * -16}deg) scale(${center ? 1.14 : 1 - abs * 0.07})`,
                zIndex: 20 - abs,
                opacity: visible ? 1 - abs * 0.14 : 0,
                filter: center ? 'none' : `brightness(${1 - abs * 0.16})`,
              }}>
              <BookCover book={book} rounded="rounded-md" className={cn(center && 'shadow-[0_30px_60px_-20px_rgba(0,0,0,0.95),0_0_40px_-10px_rgba(57,197,255,0.55)]')} />
              <span className="pointer-events-none absolute -bottom-5 left-[8%] h-6 w-[84%] rounded-[50%] bg-accent-2/30 blur-md" aria-hidden />
            </Link>
          );
        })}
      </div>

      {n > 1 && (
        <>
          <button type="button" onClick={() => go(-1)} aria-label="Previous book"
            className="absolute left-0 top-[42%] z-30 flex size-11 items-center justify-center rounded-full border border-line-strong bg-ink/70 text-fg backdrop-blur transition hover:border-accent-2 sm:left-2 sm:size-12">
            <ChevronLeft className="size-5" />
          </button>
          <button type="button" onClick={() => go(1)} aria-label="Next book"
            className="absolute right-0 top-[42%] z-30 flex size-11 items-center justify-center rounded-full border border-line-strong bg-ink/70 text-fg backdrop-blur transition hover:border-accent-2 sm:right-2 sm:size-12">
            <ChevronRight className="size-5" />
          </button>
          <div className="absolute inset-x-0 -bottom-1 flex justify-center gap-2" role="tablist" aria-label="Choose featured book">
            {items.map((b, i) => (
              <button key={b.id} type="button" role="tab" aria-selected={i === active} aria-label={`Show ${b.title}`} onClick={() => setActive(i)}
                className={cn('h-2 rounded-full transition-all', i === active ? 'w-6 bg-accent-2' : 'w-2 bg-white/20 hover:bg-white/40')} />
            ))}
          </div>
        </>
      )}
      <p className="sr-only" aria-live="polite">{items[active] ? `Featured: ${items[active].title}` : ''}</p>
    </div>
  );
}
