import { api } from './apiClient';
import type { ChatReply, ChatTurn } from '@/types/api';

/**
 * Asking the assistant.
 *
 * <p>One endpoint, and it answers whether or not anybody is signed in. The
 * token is attached when there is one - that is what makes the difference
 * between an answer drawn from the caller's own library and one drawn from the
 * public catalogue - and the backend decides which, never this client.</p>
 *
 * <p>The API is stateless: each call carries one question. The conversation a
 * person sees is held in the component, which is why there is no session id
 * here to leak or to get out of step.</p>
 */
/**
 * The most recent turns sent back with a question.
 *
 * <p>The server bounds this again and more tightly - it is the one that pays for
 * a long prompt - so this is a courtesy, not the limit. Kept small because the
 * last few exchanges are what a follow-up refers to.</p>
 */
export const MAX_CHAT_HISTORY_TURNS = 10;

/**
 * The conversation, in the shape the API expects.
 *
 * <p>The widget's own {@code role} values are for the screen ("you" reads better
 * than "user"); the API's name the speaker for a model. Translated here so the
 * widget keeps its own vocabulary and the wire keeps its.</p>
 */
function toWireHistory(turns: ChatTurn[]): { role: 'USER' | 'ASSISTANT'; message: string }[] {
  return turns
    .slice(-MAX_CHAT_HISTORY_TURNS)
    .filter((turn) => turn.text.trim().length > 0)
    .map((turn) => ({
      role: turn.role === 'you' ? ('USER' as const) : ('ASSISTANT' as const),
      message: turn.text,
    }));
}

export const chatService = {
  /**
   * Asks a question, with the conversation so far.
   *
   * <p>The history is sent rather than stored on the server, so a follow-up like
   * "do you have it?" can be understood without the library keeping a transcript
   * of anybody's conversation. It grants nothing: the server resolves who is
   * asking from the token on every request.</p>
   */
  ask(message: string, history: ChatTurn[] = [], signal?: AbortSignal): Promise<ChatReply> {
    const body =
      history.length === 0
        ? { message }
        : { message, history: toWireHistory(history) };

    return api.post<ChatReply>('/api/chat', body, { signal });
  },
};

/** The backend's own limit, mirrored so the box can stop a doomed request. */
export const MAX_CHAT_MESSAGE_LENGTH = 1000;
