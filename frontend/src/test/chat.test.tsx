import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { streamChat, type ChatEvent } from '@/lib/sse';
import { ChatMessage } from '@/features/assistant/ChatMessage';
import { fixtureBooks } from './server';
import { renderWithRouter } from './utils';

function sseResponse(chunks: string[]) {
  const enc = new TextEncoder();
  const body = new ReadableStream({
    start(c) {
      chunks.forEach((s) => c.enqueue(enc.encode(s)));
      c.close();
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('streamChat', () => {
  it('parses events split across network chunks', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(sseResponse([
      'event:meta\ndata:{"conversationId":"c1"}\n\nevent:del', 'ta\ndata:{"text":"Hel"}\n\n', 'event:delta\ndata:{"text":"lo"}\n\nevent:done\ndata:{"latencyMs":5}\n\n',
    ]));
    const events: ChatEvent[] = [];
    await streamChat({ message: 'hi' }, new AbortController().signal, (e) => events.push(e));
    expect(events.map((e) => e.event)).toEqual(['meta', 'delta', 'delta', 'done']);
    expect(events.filter((e) => e.event === 'delta').map((e) => e.data.text).join('')).toBe('Hello');
    fetchSpy.mockRestore();
  });

  it('turns an HTTP error into a friendly ApiError', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({ code: 'AI_UNAVAILABLE', detail: "I'm having trouble connecting" }), { status: 503 }));
    await expect(streamChat({ message: 'hi' }, new AbortController().signal, () => {})).rejects.toMatchObject({ code: 'AI_UNAVAILABLE', status: 503 });
    fetchSpy.mockRestore();
  });
});

describe('ChatMessage', () => {
  it('renders markdown answers with real catalogue cards linking to book pages', () => {
    renderWithRouter(<ChatMessage message={{ id: '1', role: 'assistant', content: 'Try **Java Programming 1 (LIBA)**.', books: [fixtureBooks[0]] }} onRetry={() => {}} />);
    expect(screen.getByText('Java Programming 1 (LIBA)', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Java Programming 1 \(LIBA\)/ })).toHaveAttribute('href', '/catalogue/1');
    expect(screen.getByText('Library A: 1 available')).toBeInTheDocument();
  });

  it('shows an error with retry instead of an endless loader', async () => {
    const retry = vi.fn();
    renderWithRouter(<ChatMessage message={{ id: '2', role: 'assistant', content: '', prompt: 'hi', error: { code: 'AI_TIMEOUT', message: 'The assistant took too long to respond.' } }} onRetry={retry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('took too long');
    expect(screen.queryByLabelText('Assistant is typing')).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalled();
  });

  it('never renders raw HTML from the model', () => {
    renderWithRouter(<ChatMessage message={{ id: '3', role: 'assistant', content: '<img src=x onerror="alert(1)">hello' }} onRetry={() => {}} />);
    expect(document.querySelector('img[src="x"]')).toBeNull();
  });
});
