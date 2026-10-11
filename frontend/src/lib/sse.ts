import { refreshSession, ApiError } from '@/api/http';
import { useSession } from '@/stores/session';

export interface ChatEvent {
  event: 'meta' | 'status' | 'delta' | 'books' | 'done' | 'error' | string;
  data: Record<string, unknown>;
}

async function open(body: unknown, signal: AbortSignal) {
  const token = useSession.getState().accessToken;
  return fetch('/api/ai/chat', {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/problem+json, application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
}

/** POSTs a chat message and streams Server-Sent Events back (EventSource cannot POST or send auth headers). */
export async function streamChat(body: { message: string; conversationId?: string | null }, signal: AbortSignal,
                                 onEvent: (e: ChatEvent) => void): Promise<void> {
  let res: Response;
  try {
    res = await open(body, signal);
    if (res.status === 401 && useSession.getState().accessToken && (await refreshSession())) {
      res = await open(body, signal);
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK_ERROR', 'Unable to reach SmartLib. Check your connection and try again.');
  }
  if (!res.ok || !res.body) {
    let payload: Record<string, unknown> = {};
    try {
      payload = await res.json();
    } catch {
      // ignore
    }
    throw new ApiError(res.status, (payload.code as string) ?? `HTTP_${res.status}`,
      (payload.detail as string) ?? "I'm having trouble connecting to the AI assistant right now. Please try again.", payload);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const block = buffer.slice(0, idx);
      buffer = buffer.slice(idx).replace(/^\r?\n\r?\n/, '');
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length) {
        try {
          onEvent({ event, data: JSON.parse(data.join('\n')) });
        } catch {
          // ignore malformed chunk
        }
      }
    }
  }
}
