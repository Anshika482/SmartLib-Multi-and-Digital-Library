import { beforeEach, describe, expect, it, vi } from 'vitest';
import { chatService, MAX_CHAT_HISTORY_TURNS, MAX_CHAT_MESSAGE_LENGTH } from './chatService';
import { ApiError } from './apiClient';
import { tokenStorage } from './tokenStorage';

/**
 * Asking the assistant, from both sides of the gate.
 *
 * <p>The client must send the same request either way and let the backend
 * decide what may answer it - and it must surface a rate-limit refusal as
 * something the interface can tell apart from a failure.</p>
 */

function memoryStorage(): Storage {
  const entries = new Map<string, string>();
  return {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => void entries.set(key, value),
    removeItem: (key: string) => void entries.delete(key),
    clear: () => entries.clear(),
    key: () => null,
    get length() {
      return entries.size;
    },
  } as Storage;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const fetchMock = vi.fn<typeof fetch>();

function sent(): { url: URL; init: RequestInit | undefined } {
  const [input, init] = fetchMock.mock.calls[0];
  return { url: new URL(String(input), 'http://localhost'), init };
}

beforeEach(() => {
  vi.stubGlobal('window', { localStorage: memoryStorage() });
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(json(200, { reply: 'An answer.', assistant: 'scripted', answeredAt: 'now' }));
});

describe('a visitor asking', () => {
  it('posts the question to the chat endpoint with no token', async () => {
    const answer = await chatService.ask('what should I read next?');

    expect(sent().url.pathname).toBe('/api/chat');
    expect(sent().init?.method).toBe('POST');
    expect(JSON.parse(String(sent().init?.body))).toEqual({ message: 'what should I read next?' });
    expect(((sent().init?.headers ?? {}) as Record<string, string>).Authorization).toBeUndefined();
    expect(answer.reply).toBe('An answer.');
  });

  it('sends nothing but the message - no library, role or account', async () => {
    await chatService.ask('hello');

    const body = JSON.parse(String(sent().init?.body)) as Record<string, unknown>;
    expect(Object.keys(body)).toEqual(['message']);
  });
});

describe('a signed-in caller asking', () => {
  it('sends the same request, with the token attached by the client', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });

    await chatService.ask('do you have Dune');

    expect(sent().url.pathname).toBe('/api/chat');
    expect(((sent().init?.headers ?? {}) as Record<string, string>).Authorization).toBe('Bearer access-1');
    expect(JSON.parse(String(sent().init?.body))).toEqual({ message: 'do you have Dune' });
  });
});

describe('when it will not answer', () => {
  it('surfaces a rate limit as a 429 the interface can single out', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 429 }));

    const failure = await chatService.ask('hello').catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(429);
  });

  it('surfaces an unavailable provider with its own message', async () => {
    fetchMock.mockResolvedValueOnce(json(503, { status: 503, message: 'The assistant is unavailable.' }));

    const failure = await chatService.ask('hello').catch((error: unknown) => error);

    expect((failure as ApiError).status).toBe(503);
    expect((failure as ApiError).message).toBe('The assistant is unavailable.');
  });

  it('mirrors the backend length limit so a doomed request can be stopped early', () => {
    expect(MAX_CHAT_MESSAGE_LENGTH).toBe(1000);
  });
});

describe('the conversation sent with a question', () => {
  /** The parsed request body of the one call made. */
  function body(): Record<string, unknown> {
    return JSON.parse(String(sent().init?.body)) as Record<string, unknown>;
  }

  function turn(id: number, role: 'you' | 'assistant', text: string) {
    return { id, role, text };
  }

  it('sends no history field at all on a first question', async () => {
    await chatService.ask('hello');

    // Absent rather than an empty array: a first question has no conversation,
    // and saying so with a field would be saying it twice.
    expect(body()).not.toHaveProperty('history');
    expect(body().message).toBe('hello');
  });

  it('sends the conversation when there is one', async () => {
    await chatService.ask('do you have it?', [
      turn(1, 'you', 'Who wrote Clean Code?'),
      turn(2, 'assistant', 'Robert C. Martin.'),
    ]);

    expect(body().history).toEqual([
      { role: 'USER', message: 'Who wrote Clean Code?' },
      { role: 'ASSISTANT', message: 'Robert C. Martin.' },
    ]);
  });

  it("translates the screen's own role names into the ones the API uses", async () => {
    await chatService.ask('again?', [turn(1, 'you', 'first')]);

    // "you" reads better on screen; "USER" names the speaker for a model. The
    // widget keeps its vocabulary and the wire keeps its.
    expect(JSON.stringify(body().history)).not.toContain('"you"');
    expect(JSON.stringify(body().history)).toContain('USER');
  });

  it('sends only the most recent turns', async () => {
    const long = Array.from({ length: 40 }, (_, index) =>
      turn(index, index % 2 === 0 ? 'you' : 'assistant', `line ${index}`),
    );

    await chatService.ask('and now?', long);

    const history = body().history as { message: string }[];
    expect(history).toHaveLength(MAX_CHAT_HISTORY_TURNS);
    // The end of the conversation, which is what a follow-up refers to.
    expect(history[history.length - 1].message).toBe('line 39');
  });

  it('leaves blank turns out rather than sending empty messages', async () => {
    await chatService.ask('go on', [turn(1, 'you', 'a question'), turn(2, 'assistant', '   ')]);

    expect(body().history).toEqual([{ role: 'USER', message: 'a question' }]);
  });

  it('still posts to the one chat endpoint', async () => {
    await chatService.ask('hello', [turn(1, 'you', 'earlier')]);

    expect(sent().url.pathname).toBe('/api/chat');
  });
});
