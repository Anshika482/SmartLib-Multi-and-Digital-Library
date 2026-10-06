import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { chatService, MAX_CHAT_MESSAGE_LENGTH } from '@/services/chatService';
import { ApiError } from '@/services/apiClient';
import { SparkIcon, ArrowRightIcon } from '@/components/ui/icons';
import type { ChatTurn } from '@/types/api';
import './ChatWidget.css';

/** Openers a visitor can send with one tap, phrased as things the assistant can take. */
const SUGGESTIONS = [
  'What should I read next?',
  'Recommend a book on algorithms',
  'Explain recursion simply',
];

/**
 * The SmartLib assistant, as a floating panel.
 *
 * <p>Works signed in and signed out. The client sends only the question; the
 * token is attached by the API client when there is one, and the backend
 * decides from that whether the answer may draw on the caller's own library or
 * only on the public catalogue. Nothing here asks for or carries a library, a
 * role or an account.</p>
 *
 * <p>The conversation lives in this component. The API is stateless, so what a
 * person sees is a local transcript - which also means it is gone on reload
 * rather than persisted somewhere it would have to be protected.</p>
 */
export function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; waiting: boolean } | null>(null);

  const logRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const nextId = useRef(0);
  const panelRef = useRef<HTMLDivElement>(null);

  // The newest turn, whenever one arrives.
  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: 'smooth' });
  }, [turns, busy]);

  useEffect(() => {
    if (open) {
      inputRef.current?.focus();
    }
  }, [open]);

  // Escape closes, which is what a dialog is expected to do.
  useEffect(() => {
    if (!open) {
      return;
    }

    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };

    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const send = useCallback(async (question: string) => {
    const trimmed = question.trim();
    if (trimmed.length === 0 || busy) {
      return;
    }

    setError(null);
    setDraft('');

    // Captured before the new question is added, so what is sent is the
    // conversation up to this point and not a list containing the question
    // twice.
    const conversation = turns;

    setTurns((current) => [...current, { id: (nextId.current += 1), role: 'you', text: trimmed }]);
    setBusy(true);

    try {
      const answer = await chatService.ask(trimmed, conversation);
      setTurns((current) => [
        ...current,
        { id: (nextId.current += 1), role: 'assistant', text: answer.reply },
      ]);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 429) {
        setError({
          message: 'You have asked a lot in a short while. Give it a minute and try again.',
          waiting: true,
        });
      } else if (failure instanceof ApiError) {
        setError({ message: failure.message, waiting: false });
      } else {
        setError({ message: 'The assistant could not be reached. Please try again.', waiting: false });
      }
    } finally {
      setBusy(false);
    }
  }, [busy, turns]);

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void send(draft);
  }

  // Enter sends, Shift+Enter makes a new line - what a chat box is expected to do.
  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void send(draft);
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        className="sl-chat__launcher"
        onClick={() => setOpen(true)}
        aria-expanded={false}
      >
        <SparkIcon width="20" height="20" aria-hidden="true" />
        <span className="sl-chat__launcher-label">Ask SmartLib</span>
        <span className="sl-visually-hidden">Open the SmartLib assistant</span>
      </button>
    );
  }

  return (
    <div
      className="sl-chat__panel"
      ref={panelRef}
      role="dialog"
      aria-modal="false"
      aria-label="SmartLib assistant"
    >
      <header className="sl-chat__head">
        <span className="sl-chat__mark" aria-hidden="true">
          <SparkIcon width="18" height="18" />
        </span>
        <div className="sl-chat__titles">
          <p className="sl-chat__title">SmartLib Assistant</p>
          <p className="sl-chat__subtitle">Books, authors and how things work</p>
        </div>
        <button type="button" className="sl-chat__close" onClick={() => setOpen(false)}>
          <span aria-hidden="true">&times;</span>
          <span className="sl-visually-hidden">Close the assistant</span>
        </button>
      </header>

      <div className="sl-chat__log" ref={logRef} aria-live="polite" aria-busy={busy}>
        {turns.length === 0 && (
          <div>
            <p className="sl-chat__opening">
              Ask about a book, an author or an idea. Sign in and it can answer from your own
              library&rsquo;s shelves too.
            </p>
            <div className="sl-chat__suggestions">
              {SUGGESTIONS.map((suggestion) => (
                <button
                  type="button"
                  className="sl-chat__suggestion"
                  key={suggestion}
                  onClick={() => void send(suggestion)}
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        )}

        {turns.map((turn) => (
          <p className={`sl-chat__turn sl-chat__turn--${turn.role}`} key={turn.id}>
            {turn.text}
          </p>
        ))}

        {busy && (
          <p className="sl-chat__thinking">
            <span className="sl-visually-hidden">The assistant is thinking</span>
            <span aria-hidden="true" />
            <span aria-hidden="true" />
            <span aria-hidden="true" />
          </p>
        )}
      </div>

      {error !== null && (
        <p className={`sl-chat__error${error.waiting ? ' sl-chat__error--wait' : ''}`} role="alert">
          {error.message}
        </p>
      )}

      <form className="sl-chat__form" onSubmit={onSubmit}>
        <label className="sl-visually-hidden" htmlFor="sl-chat-input">
          Your question
        </label>
        <textarea
          id="sl-chat-input"
          className="sl-chat__input"
          ref={inputRef}
          rows={1}
          value={draft}
          maxLength={MAX_CHAT_MESSAGE_LENGTH}
          placeholder="Ask anything about books..."
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
        />
        <button type="submit" className="sl-chat__send" disabled={busy || draft.trim().length === 0}>
          <ArrowRightIcon aria-hidden="true" />
          <span className="sl-visually-hidden">Send</span>
        </button>
      </form>
    </div>
  );
}
