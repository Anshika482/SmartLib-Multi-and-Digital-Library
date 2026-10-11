import { Link } from 'react-router';
import { Bot, BookOpenText, Library, ShieldCheck } from 'lucide-react';
import { Logo } from '@/components/ui';

const features = [
  { icon: Library, title: 'Multiple Libraries', text: 'One platform' },
  { icon: BookOpenText, title: 'Digital Resources', text: 'Books, PDFs, videos & more' },
  { icon: Bot, title: 'AI Powered', text: 'Smart recommendations' },
  { icon: ShieldCheck, title: 'Secure & Reliable', text: 'Your data, our priority' },
];

export function SiteFooter() {
  return (
    <footer className="mt-12 border-t border-line/70 bg-ink/60">
      <div className="mx-auto grid max-w-7xl gap-8 px-4 py-10 sm:px-6 lg:grid-cols-[1.2fr_2fr] lg:items-center">
        <div>
          <Logo size="lg" />
          <p className="mt-3 max-w-sm text-sm text-muted">Libraries today. A smarter tomorrow. Discover books across every SmartLib library and borrow from the one you choose.</p>
          <nav aria-label="Footer" className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-sm text-muted">
            <Link to="/catalogue" className="hover:text-fg">Catalogue</Link>
            <Link to="/libraries" className="hover:text-fg">Libraries</Link>
            <Link to="/assistant" className="hover:text-fg">AI Assistant</Link>
            <Link to="/register" className="hover:text-fg">Become a member</Link>
            <Link to="/register/librarian" className="hover:text-fg">Apply as librarian</Link>
          </nav>
        </div>
        <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {features.map(({ icon: Icon, title, text }) => (
            <li key={title} className="flex items-center gap-3">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-full border border-line-strong text-accent-2"><Icon className="size-5" aria-hidden /></span>
              <span><span className="block text-sm font-semibold">{title}</span><span className="block text-xs text-faint">{text}</span></span>
            </li>
          ))}
        </ul>
      </div>
      <div className="border-t border-line/60">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-2 px-4 py-4 text-xs text-faint sm:px-6">
          <p>© {new Date().getFullYear()} SmartLib · Intelligence in Every Chapter</p>
          <p className="italic">Knowledge has no limits.</p>
        </div>
      </div>
    </footer>
  );
}
