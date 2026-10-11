import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { ArrowRight, Atom, BookMarked, BookOpen, Briefcase, Cpu, FileText, HeartPulse, Landmark, Library, Megaphone, Palette, Search, Sparkles } from 'lucide-react';
import { publicApi } from '@/api/endpoints';
import { BookCover, LibraryCard } from '@/components/book';
import { Skeleton } from '@/components/ui';
import { num } from '@/lib/format';
import { BookShowcase } from './BookShowcase';
import { AssistantAvatar } from '@/features/assistant/AssistantAvatar';

const categoryStyle: Record<string, { icon: typeof BookOpen; bg: string }> = {
  fiction: { icon: BookOpen, bg: 'from-violet-500 to-fuchsia-600' },
  'non-fiction': { icon: FileText, bg: 'from-emerald-400 to-teal-600' },
  technology: { icon: Cpu, bg: 'from-blue-500 to-indigo-600' },
  science: { icon: Atom, bg: 'from-indigo-400 to-blue-700' },
  business: { icon: Briefcase, bg: 'from-amber-400 to-orange-600' },
  'health-wellness': { icon: HeartPulse, bg: 'from-purple-400 to-violet-700' },
  history: { icon: Landmark, bg: 'from-rose-400 to-red-700' },
  'arts-culture': { icon: Palette, bg: 'from-sky-400 to-cyan-600' },
};

const prompts = ['Recommend me a book for beginners', 'How do I register as a member?', 'Which libraries have Clean Code available?'];

function HeroSearch() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    navigate(q.trim() ? `/catalogue?q=${encodeURIComponent(q.trim())}` : '/catalogue');
  };
  return (
    <form onSubmit={submit} role="search" className="mx-auto mt-8 flex w-full max-w-xl items-center gap-2 rounded-full border border-line-strong bg-ink-raised/70 p-1.5 pl-5 shadow-[0_0_0_1px_rgba(57,197,255,0.06),0_20px_50px_-20px_rgba(22,136,255,0.6)] backdrop-blur-xl">
      <Search className="size-5 shrink-0 text-muted" aria-hidden />
      <label htmlFor="hero-search" className="sr-only">Search books, authors or ISBN</label>
      <input id="hero-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search books, authors or ISBN..."
        className="h-11 min-w-0 flex-1 bg-transparent text-[15px] text-fg placeholder:text-faint focus:outline-none" />
      <button type="submit" aria-label="Search" className="flex size-11 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-accent to-accent-2 text-white shadow-[0_8px_24px_-8px_rgba(22,136,255,0.9)] transition hover:brightness-110">
        <ArrowRight className="size-5" />
      </button>
    </form>
  );
}

export default function HomePage() {
  const site = useQuery({ queryKey: ['public', 'site'], queryFn: publicApi.site });
  const featured = useQuery({ queryKey: ['public', 'featured', 12], queryFn: () => publicApi.featured(12) });
  const categories = useQuery({ queryKey: ['public', 'categories'], queryFn: publicApi.categories });
  const libraries = useQuery({ queryKey: ['public', 'libraries', ''], queryFn: () => publicApi.libraries() });
  const navigate = useNavigate();

  return (
    <>
      <section className="relative overflow-hidden pb-6" aria-labelledby="hero-title">
        <div className="shelves" aria-hidden />
        <div className="window-glow hidden lg:block" aria-hidden />
        <div className="pointer-events-none absolute left-1/2 top-[38%] size-[620px] -translate-x-1/2 rounded-full bg-accent/10 blur-3xl" aria-hidden />
        <p aria-hidden className="pointer-events-none absolute right-[7%] top-16 hidden rotate-[-10deg] font-hand text-[1.75rem] leading-[1.05] text-muted/85 xl:block">
          Good books<br />&nbsp;build better<br />&nbsp;&nbsp;futures
          <svg className="mt-1 h-3 w-28" viewBox="0 0 120 12" fill="none"><path d="M2 9C30 3 70 2 118 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
        </p>
        <div className="relative mx-auto max-w-7xl px-4 pt-10 text-center sm:px-6 sm:pt-14">
          <h1 id="hero-title" className="text-[2.35rem] font-extrabold leading-[1.08] tracking-tight sm:text-5xl lg:text-[3.6rem]">
            More Books. More Knowledge.<br /><span className="text-gradient">A Smarter You.</span>
          </h1>
          <p className="mt-4 text-base text-muted sm:text-lg">Discover. Learn. Grow. – All in One Place.</p>
          <HeroSearch />
          <BookShowcase books={featured.data} loading={featured.isPending} error={featured.isError} />
          {site.data && (
            <ul className="mt-2 flex flex-wrap justify-center gap-x-8 gap-y-2 text-sm text-muted" aria-label="SmartLib at a glance">
              <li><b className="text-fg">{num(site.data.stats.libraries)}</b> libraries</li>
              <li><b className="text-fg">{num(site.data.stats.titles)}</b> titles</li>
              <li><b className="text-fg">{num(site.data.stats.availableCopies)}</b> copies available now</li>
              <li><b className="text-fg">{num(site.data.stats.digitalResources)}</b> digital resources</li>
            </ul>
          )}
        </div>
      </section>

      {site.data?.announcement && (
        <div className="mx-auto mt-4 max-w-7xl px-4 sm:px-6">
          <p className="glass-soft flex items-center gap-3 px-4 py-3 text-sm"><Megaphone className="size-4 shrink-0 text-accent-2" aria-hidden />{site.data.announcement}</p>
        </div>
      )}

      <section className="mx-auto mt-8 grid max-w-7xl gap-5 px-4 sm:px-6 lg:grid-cols-[1.3fr_1.15fr_1fr]" aria-label="Discover">
        <div className="glass p-5">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="flex items-center gap-2.5 text-lg font-semibold"><BookMarked className="size-5 text-accent-2" aria-hidden />Featured Books</h2>
            <Link to="/catalogue?sort=popular" className="flex items-center gap-1 text-sm font-semibold text-accent-2 hover:underline">View All <ArrowRight className="size-4" /></Link>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {featured.isPending && Array.from({ length: 4 }, (_, i) => <div key={i}><Skeleton className="aspect-[2/3] w-full" /><Skeleton className="mt-2 h-3 w-4/5" /></div>)}
            {featured.data?.slice(0, 4).map((b) => (
              <Link key={b.id} to={`/catalogue/${b.id}`} className="group rounded-xl border border-line bg-ink/40 p-2 transition hover:border-line-strong">
                <BookCover book={b} className="transition group-hover:-translate-y-0.5" />
                <p className="mt-2 line-clamp-1 text-[13px] font-semibold">{b.title}</p>
                <p className="line-clamp-1 text-xs text-muted">{b.authors[0] ?? 'Unknown author'}</p>
                <p className="mt-1 text-[11px] text-faint">
                  {b.totalAvailable > 0 ? <span className="text-success">{b.totalAvailable} available</span> : <span>All copies on loan</span>}
                  {' · '}{b.libraryCount} {b.libraryCount === 1 ? 'library' : 'libraries'}
                </p>
              </Link>
            ))}
          </div>
          {featured.isError && <p className="text-sm text-muted">Featured books couldn't be loaded. <button className="link" onClick={() => featured.refetch()}>Retry</button></p>}
        </div>

        <div className="glass p-5">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="flex items-center gap-2.5 text-lg font-semibold"><Library className="size-5 text-accent-2" aria-hidden />Browse by Category</h2>
            <Link to="/catalogue" className="flex items-center gap-1 text-sm font-semibold text-accent-2 hover:underline">View All <ArrowRight className="size-4" /></Link>
          </div>
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
            {categories.isPending && Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-[58px]" />)}
            {categories.data?.map((c) => {
              const style = categoryStyle[c.slug] ?? { icon: BookOpen, bg: 'from-accent to-accent-2' };
              const Icon = style.icon;
              return (
                <Link key={c.id} to={`/catalogue?category=${c.slug}`} className="group flex items-center gap-3 rounded-xl border border-line bg-ink/40 p-2.5 transition hover:border-line-strong hover:bg-accent/5">
                  <span className={`flex size-10 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br ${style.bg} text-white shadow-lg`}><Icon className="size-5" aria-hidden /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-semibold leading-tight">{c.name}</span>
                    <span className="block text-xs text-faint">{num(c.bookCount)} {c.bookCount === 1 ? 'book' : 'books'}</span>
                  </span>
                  <ArrowRight className="size-4 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent-2" aria-hidden />
                </Link>
              );
            })}
          </div>
        </div>

        <div className="glass flex flex-col p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="flex items-center gap-2.5 text-lg font-semibold"><Sparkles className="size-5 text-accent-2" aria-hidden />AI Assistant</h2>
            <Link to="/assistant" className="flex items-center gap-1 text-sm font-semibold text-accent-2 hover:underline">Try Now <ArrowRight className="size-4" /></Link>
          </div>
          <div className="flex items-center gap-3">
            <AssistantAvatar className="size-28 sm:size-32" motion="idle" />
            <p className="rounded-2xl rounded-bl-sm border border-line-strong bg-accent/10 p-3.5 text-sm text-fg/90">
              Ask me anything about books, libraries, or get personalized recommendations!
            </p>
          </div>
          <div className="mt-4 flex flex-col items-start gap-2">
            {prompts.map((p) => (
              <button key={p} type="button" onClick={() => navigate(`/assistant?q=${encodeURIComponent(p)}`)}
                className="rounded-full border border-accent/40 bg-accent/12 px-3.5 py-1.5 text-left text-[13px] text-fg/90 transition hover:border-accent-2 hover:bg-accent/20">{p}</button>
            ))}
          </div>
          {site.data && !site.data.aiEnabled && <p className="mt-3 text-xs text-warning">The assistant is temporarily turned off.</p>}
        </div>
      </section>

      <section className="mx-auto mt-14 max-w-7xl px-4 sm:px-6" aria-labelledby="libraries-title">
        <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="eyebrow">Multi-library discovery</p>
            <h2 id="libraries-title" className="mt-1 text-2xl font-bold">One search. Every library.</h2>
            <p className="mt-1 max-w-2xl text-sm text-muted">Compare availability across libraries and request from the one that suits you best.</p>
          </div>
          <Link to="/libraries" className="btn btn-outline">All libraries <ArrowRight className="size-4" /></Link>
        </div>
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {libraries.isPending && Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-80" />)}
          {libraries.data?.slice(0, 4).map((l) => <LibraryCard key={l.id} library={l} />)}
        </div>
      </section>
    </>
  );
}
