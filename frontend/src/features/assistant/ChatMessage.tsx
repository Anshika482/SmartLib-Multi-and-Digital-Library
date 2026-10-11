import { Link } from 'react-router';
import ReactMarkdown from 'react-markdown';
import { AlertTriangle, RefreshCcw } from 'lucide-react';
import type { CatalogueItem } from '@/api/types';
import { BookCover } from '@/components/book';
import { Avatar } from '@/components/ui';
import { cn } from '@/lib/cn';
import { useSession } from '@/stores/session';
import { AssistantAvatar } from './AssistantAvatar';

export interface Msg {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  pending?: boolean;
  status?: string;
  books?: CatalogueItem[];
  error?: { code: string; message: string };
  latencyMs?: number;
  prompt?: string;
  stopped?: boolean;
}

function TypingDots() {
  return (
    <span className="inline-flex items-center gap-1" aria-label="Assistant is typing">
      {[0, 1, 2].map((i) => <span key={i} className="size-2 animate-bounce rounded-full bg-accent-2" style={{ animationDelay: `${i * 0.15}s` }} />)}
    </span>
  );
}

export function ChatBookCards({ books }: { books: CatalogueItem[] }) {
  return (
    <div className="mt-3 grid gap-2 sm:grid-cols-2" aria-label="Books from the SmartLib catalogue">
      {books.map((b) => (
        <Link key={b.id} to={`/catalogue/${b.id}`} className="flex gap-3 rounded-xl border border-line bg-ink/60 p-2.5 transition hover:border-line-strong">
          <BookCover book={b} className="w-12 shrink-0" rounded="rounded" />
          <div className="min-w-0 flex-1">
            <p className="line-clamp-1 text-sm font-semibold text-fg">{b.title}</p>
            <p className="line-clamp-1 text-xs text-muted">{b.authors.join(', ')}</p>
            <div className="mt-1.5 flex flex-wrap gap-1">
              {b.holdings.slice(0, 3).map((h) => (
                <span key={h.holdingId} className={cn('rounded-full border px-1.5 py-px text-[10px] font-semibold',
                  h.availableCopies > 0 ? 'border-success/30 bg-success/10 text-success' : 'border-line text-faint')}>
                  {h.libraryName}: {h.availableCopies > 0 ? `${h.availableCopies} available` : 'unavailable'}
                </span>
              ))}
            </div>
          </div>
        </Link>
      ))}
    </div>
  );
}

const markdown = 'text-sm leading-relaxed text-fg/90 [&_a]:text-accent-2 [&_a]:underline [&_code]:rounded [&_code]:bg-white/10 [&_code]:px-1 [&_code]:py-px [&_code]:text-[13px] [&_h1]:text-base [&_h1]:font-bold [&_h2]:font-bold [&_h3]:font-semibold [&_li]:my-0.5 [&_ol]:my-2 [&_ol]:list-decimal [&_ol]:pl-5 [&_p]:my-2 [&_p:first-child]:mt-0 [&_p:last-child]:mb-0 [&_pre]:my-2 [&_pre]:overflow-x-auto [&_pre]:rounded-lg [&_pre]:bg-ink [&_pre]:p-3 [&_strong]:text-fg [&_ul]:my-2 [&_ul]:list-disc [&_ul]:pl-5';

export function ChatMessage({ message, onRetry, talking }: { message: Msg; onRetry: () => void; talking?: boolean }) {
  const user = useSession((s) => s.user);
  if (message.role === 'user') {
    return (
      <div className="flex justify-end gap-2.5">
        <div className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-gradient-to-br from-accent to-[#2b9bff] px-4 py-2.5 text-sm text-white shadow-[0_10px_30px_-14px_rgba(22,136,255,0.9)]">{message.content}</div>
        <Avatar name={user?.fullName ?? 'You'} className="mt-0.5 size-8" />
      </div>
    );
  }
  return (
    <div className="flex gap-2.5">
      <AssistantAvatar className="mt-0.5 size-8" decorative motion={talking ? 'talk' : 'none'} />
      <div className="min-w-0 max-w-[92%] flex-1">
        <div className="rounded-2xl rounded-tl-sm border border-line bg-surface/70 px-4 py-3">
          {message.pending && !message.content && (
            <div className="flex items-center gap-3 text-sm text-muted"><TypingDots />{message.status && <span>{message.status}</span>}</div>
          )}
          {message.content && (
            <div className={markdown}>
              <ReactMarkdown components={{
                a: ({ href, children }) => href?.startsWith('/') ? <Link to={href}>{children}</Link> : <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>,
              }}>{message.content}</ReactMarkdown>
            </div>
          )}
          {message.status && message.content && <p className="mt-2 text-xs text-faint">{message.status}</p>}
          {message.books && message.books.length > 0 && <ChatBookCards books={message.books} />}
          {message.error && message.error.code !== 'STOPPED' && (
            <div role="alert" className="mt-2 flex flex-wrap items-center gap-3 rounded-xl border border-danger/30 bg-danger/10 px-3 py-2 text-sm">
              <AlertTriangle className="size-4 shrink-0 text-danger" />
              <span className="flex-1">{message.error.message}</span>
              {message.prompt && message.error.code !== 'AI_DISABLED' && (
                <button type="button" onClick={onRetry} className="btn btn-outline btn-sm"><RefreshCcw className="size-3.5" />Retry</button>
              )}
            </div>
          )}
          {message.stopped && <p className="mt-2 text-xs text-faint">Response stopped.</p>}
        </div>
        {message.latencyMs != null && <p className="mt-1 pl-1 text-[11px] text-faint">Answered in {(message.latencyMs / 1000).toFixed(1)}s</p>}
      </div>
    </div>
  );
}
