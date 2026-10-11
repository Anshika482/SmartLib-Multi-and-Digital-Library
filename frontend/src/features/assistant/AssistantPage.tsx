import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { BookOpen, History, MessageSquarePlus, Send, Sparkles, Square, Trash2 } from 'lucide-react';
import { aiApi, publicApi } from '@/api/endpoints';
import { ApiError, isApiError } from '@/api/http';
import type { CatalogueItem } from '@/api/types';
import { streamChat } from '@/lib/sse';
import { useSession } from '@/stores/session';
import { cn } from '@/lib/cn';
import { relative } from '@/lib/format';
import { AssistantAvatar } from './AssistantAvatar';
import { Button } from '@/components/ui';
import { ChatMessage, type Msg } from './ChatMessage';

const SUGGESTIONS = [
  'Recommend a beginner-friendly Java book',
  'Which libraries have Clean Code available?',
  'How do I verify my email?',
  'Do I have any fines?',
  'Explain polymorphism simply',
  'How do I borrow a book from a specific library?',
];
const WATCHDOG_MS = 45_000;

let seq = 0;
const nid = () => `m${Date.now()}-${seq++}`;

export default function AssistantPage() {
  const { user } = useSession();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const status = useQuery({ queryKey: ['ai-status'], queryFn: publicApi.aiStatus, staleTime: 60_000 });
  const conversations = useQuery({ queryKey: ['ai', 'conversations'], queryFn: aiApi.conversations, enabled: !!user });
  const [messages, setMessages] = useState<Msg[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const autoSent = useRef(false);

  const patchLast = (fn: (m: Msg) => Msg) => setMessages((ms) => (ms.length ? [...ms.slice(0, -1), fn(ms[ms.length - 1])] : ms));

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  const send = useCallback(async (raw: string) => {
    const text = raw.trim();
    if (!text || streaming) return;
    setInput('');
    setMessages((ms) => [...ms, { id: nid(), role: 'user', content: text }, { id: nid(), role: 'assistant', content: '', pending: true, prompt: text }]);
    setStreaming(true);
    const controller = new AbortController();
    abortRef.current = controller;
    let timedOut = false;
    let watchdog = setTimeout(() => { timedOut = true; controller.abort(); }, WATCHDOG_MS);
    const bump = () => {
      clearTimeout(watchdog);
      watchdog = setTimeout(() => { timedOut = true; controller.abort(); }, WATCHDOG_MS);
    };
    try {
      await streamChat({ message: text, conversationId }, controller.signal, (e) => {
        bump();
        switch (e.event) {
          case 'meta':
            setConversationId(String(e.data.conversationId));
            break;
          case 'status':
            patchLast((m) => ({ ...m, status: String(e.data.message) }));
            break;
          case 'delta':
            patchLast((m) => ({ ...m, pending: false, status: undefined, content: m.content + String(e.data.text ?? '') }));
            break;
          case 'books':
            patchLast((m) => ({ ...m, books: e.data.books as CatalogueItem[] }));
            break;
          case 'done':
            patchLast((m) => ({ ...m, pending: false, status: undefined, latencyMs: Number(e.data.latencyMs) || undefined }));
            break;
          case 'error':
            patchLast((m) => ({ ...m, pending: false, status: undefined, error: { code: String(e.data.code), message: String(e.data.message) } }));
            break;
        }
      });
      if (user) qc.invalidateQueries({ queryKey: ['ai', 'conversations'] });
    } catch (err) {
      if ((err as Error).name === 'AbortError') {
        patchLast((m) => ({
          ...m, pending: false, status: undefined,
          error: timedOut ? { code: 'AI_TIMEOUT', message: 'The assistant took too long to respond. Please try again.' } : m.content ? undefined : { code: 'STOPPED', message: 'Stopped.' },
          stopped: !timedOut,
        }));
      } else {
        const e = isApiError(err) ? err : new ApiError(0, 'NETWORK_ERROR', 'Unable to reach SmartLib.');
        patchLast((m) => ({ ...m, pending: false, status: undefined, error: { code: e.code, message: e.code === 'RATE_LIMITED' ? `${e.message}` : e.message } }));
      }
    } finally {
      clearTimeout(watchdog);
      setStreaming(false);
      abortRef.current = null;
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [streaming, conversationId, user, qc]);

  // Quick prompts from the homepage arrive as ?q=
  useEffect(() => {
    const q = params.get('q');
    if (q && !autoSent.current && status.data) {
      autoSent.current = true;
      setParams({}, { replace: true });
      void send(q);
    }
  }, [params, setParams, send, status.data]);

  const retry = (index: number) => {
    const failed = messages[index];
    if (!failed?.prompt) return;
    setMessages((ms) => ms.slice(0, index - 1));
    void send(failed.prompt);
  };

  const newChat = () => {
    abortRef.current?.abort();
    setMessages([]);
    setConversationId(null);
    setInput('');
    inputRef.current?.focus();
  };

  const openConversation = async (id: string) => {
    if (streaming) return;
    try {
      const transcript = await aiApi.messages(id);
      setConversationId(id);
      setMessages(transcript.map((t) => ({ id: `t${t.id}`, role: t.role === 'USER' ? 'user' : 'assistant', content: t.content, books: t.books ?? undefined, latencyMs: t.latencyMs ?? undefined })));
    } catch (e) {
      toast.error(isApiError(e) ? e.message : 'Could not open that conversation.');
    }
  };

  const removeConversation = async (id: string) => {
    try {
      await aiApi.remove(id);
      if (id === conversationId) newChat();
      qc.invalidateQueries({ queryKey: ['ai', 'conversations'] });
    } catch (e) {
      toast.error(isApiError(e) ? e.message : 'Could not delete the conversation.');
    }
  };

  const unavailable = status.data && (!status.data.enabled || !status.data.configured);
  const embedded = location.pathname.startsWith('/app/');
  // Fill the viewport: below the dashboard top bar, or below the public navbar plus its back-to-home row.
  const panelHeight = embedded ? 'h-[calc(100dvh-8.5rem)]' : 'h-[calc(100dvh-12rem)]';

  return (
    <div className={cn('mx-auto grid w-full max-w-7xl gap-5', embedded ? '' : 'px-4 py-6 sm:px-6', user ? 'lg:grid-cols-[280px_1fr]' : 'lg:grid-cols-[1fr]')}>
      {user && (
        <aside className={cn('glass hidden flex-col p-4 lg:flex', panelHeight)} aria-label="Conversations">
          <Button onClick={newChat} icon={<MessageSquarePlus className="size-4" />} className="w-full">New chat</Button>
          <p className="mb-2 mt-5 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-faint"><History className="size-3.5" />Recent</p>
          <ul className="flex-1 space-y-1 overflow-y-auto pr-1">
            {conversations.data?.length === 0 && <li className="px-2 text-xs text-faint">Your conversations will appear here.</li>}
            {conversations.data?.map((c) => (
              <li key={c.id} className={cn('group flex items-center rounded-lg', c.id === conversationId ? 'bg-accent/15' : 'hover:bg-white/5')}>
                <button type="button" onClick={() => openConversation(c.id)} className="min-w-0 flex-1 px-2.5 py-2 text-left">
                  <span className="block truncate text-sm">{c.title ?? 'Conversation'}</span>
                  <span className="block text-[11px] text-faint">{relative(c.lastMessageAt)}</span>
                </button>
                <button type="button" onClick={() => removeConversation(c.id)} aria-label={`Delete conversation ${c.title ?? ''}`}
                  className="mr-1 rounded-md p-1.5 text-faint opacity-0 transition hover:text-danger focus:opacity-100 group-hover:opacity-100"><Trash2 className="size-3.5" /></button>
              </li>
            ))}
          </ul>
        </aside>
      )}

      <section className={cn('glass flex flex-col overflow-hidden', panelHeight)} aria-label="SmartLib AI Assistant">
        <header className="flex items-center gap-3 border-b border-line px-4 py-3 sm:px-5">
          <AssistantAvatar className="size-10" motion={streaming ? 'talk' : 'idle'} />
          <div className="min-w-0 flex-1">
            <h1 className="truncate font-semibold">SmartLib AI Assistant</h1>
            <p className="flex items-center gap-1.5 text-xs text-faint">
              <span className={cn('size-2 rounded-full', unavailable ? 'bg-warning' : 'bg-success')} aria-hidden />
              {status.isPending ? 'Checking…' : unavailable ? (status.data?.enabled === false ? 'Turned off by the administrator' : 'Not configured yet') : 'Gemini-powered · answers SmartLib questions from live data'}
            </p>
          </div>
          {messages.length > 0 && <Button size="sm" variant="ghost" icon={<MessageSquarePlus className="size-4" />} onClick={newChat}>{user ? 'New chat' : 'Clear'}</Button>}
        </header>

        <div ref={listRef} className="flex-1 space-y-5 overflow-y-auto px-4 py-5 sm:px-6" aria-live="polite" aria-busy={streaming}>
          {messages.length === 0 ? (
            <div className="mx-auto flex max-w-2xl flex-col items-center pt-4 text-center">
              <AssistantAvatar className="size-28" motion="idle" />
              <h2 className="mt-2 text-xl font-bold">Ask me anything</h2>
              <p className="mt-1 text-sm text-muted">General questions, study help, or SmartLib: books, availability across libraries, borrowing, fines and your account.</p>
              {!user && <p className="mt-2 text-xs text-faint"><Link to="/login?next=/assistant" className="link">Log in</Link> to ask about your loans, requests and fines.</p>}
              <div className="mt-6 grid w-full gap-2 sm:grid-cols-2">
                {SUGGESTIONS.map((s) => (
                  <button key={s} type="button" onClick={() => send(s)} disabled={!!unavailable}
                    className="glass-soft flex items-center gap-2 px-3.5 py-3 text-left text-sm text-fg/90 transition hover:border-line-strong disabled:opacity-50">
                    <Sparkles className="size-4 shrink-0 text-accent-2" />{s}
                  </button>
                ))}
              </div>
            </div>
          ) : messages.map((m, i) => <ChatMessage key={m.id} message={m} onRetry={() => retry(i)} talking={streaming && i === messages.length - 1} />)}
        </div>

        <form className="border-t border-line p-3 sm:p-4" onSubmit={(e) => { e.preventDefault(); void send(input); }}>
          <div className="flex items-end gap-2 rounded-2xl border border-line-strong bg-ink/70 p-2 focus-within:border-accent">
            <label htmlFor="chat-input" className="sr-only">Message the SmartLib assistant</label>
            <textarea id="chat-input" ref={inputRef} rows={1} value={input} maxLength={2000} disabled={!!unavailable}
              placeholder={unavailable ? 'The assistant is unavailable right now' : 'Type your message… (Enter to send, Shift+Enter for a new line)'}
              onChange={(e) => { setInput(e.target.value); e.target.style.height = 'auto'; e.target.style.height = `${Math.min(e.target.scrollHeight, 160)}px`; }}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send(input); } }}
              className="max-h-40 min-h-10 flex-1 resize-none bg-transparent px-2 py-2 text-[15px] text-fg placeholder:text-faint focus:outline-none" />
            {streaming ? (
              <Button type="button" variant="outline" iconOnly aria-label="Stop generating" onClick={() => abortRef.current?.abort()}><Square className="size-4" /></Button>
            ) : (
              <Button type="submit" iconOnly aria-label="Send message" disabled={!input.trim() || !!unavailable}><Send className="size-4" /></Button>
            )}
          </div>
          <div className="mt-1.5 flex justify-between px-1 text-[11px] text-faint">
            <span className="flex items-center gap-1"><BookOpen className="size-3" />Book availability comes from the live SmartLib catalogue.</span>
            {input.length > 1800 && <span>{input.length}/2000</span>}
          </div>
        </form>
      </section>
    </div>
  );
}
