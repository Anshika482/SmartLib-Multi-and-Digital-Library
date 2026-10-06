import type { ApiErrorBody } from '@/types/api';
import { tokenStorage } from './tokenStorage';

/**
 * The one way this application talks to the API.
 *
 * <p>Everything goes through here so that four things are decided once: where
 * the API is, how the token is attached, how an error becomes a typed failure,
 * and what happens when a token has expired.</p>
 *
 * <p><b>An expired access token is refreshed once, transparently.</b> A 401 on
 * a request that carried a token triggers a single refresh; if that works the
 * original request is retried, and if it does not the session is cleared and
 * the caller is told to sign in. Concurrent 401s share one refresh rather than
 * starting a stampede of them.</p>
 */

/** Empty in development, where Vite proxies /api and the browser sees one origin. */
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

/** Requests that must never be retried after a refresh - they are the refresh path itself. */
const AUTH_PATHS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout'];

/** A failed request, with the status and the message the API chose to give. */
export class ApiError extends Error {
  readonly status: number;
  readonly timestamp?: string;

  constructor(status: number, message: string, timestamp?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.timestamp = timestamp;
  }

  /** Whether signing in again is the answer. */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  /** Whether the caller is known but not allowed. */
  get isForbidden(): boolean {
    return this.status === 403;
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Set for the sign-in and refresh calls, which have no token to send. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

/** Called when a session ends for good, so the app can show the sign-in page. */
type SessionEndedListener = () => void;
let onSessionEnded: SessionEndedListener | null = null;

export function setSessionEndedListener(listener: SessionEndedListener | null): void {
  onSessionEnded = listener;
}

/** The refresh in flight, if any, so several 401s wait on one call. */
let refreshInFlight: Promise<string | null> | null = null;

async function parseError(response: Response): Promise<ApiError> {
  // Every handled error has the same JSON shape. Anything else - a proxy error
  // page, an empty 502 - must still become an ApiError rather than a parse
  // crash, so the status is what is trusted and the body is a bonus.
  try {
    const body = (await response.json()) as Partial<ApiErrorBody>;
    if (typeof body?.message === 'string' && body.message.length > 0) {
      return new ApiError(response.status, body.message, body.timestamp);
    }
  } catch {
    // Fall through to the generic message below.
  }

  return new ApiError(response.status, `Request failed (${response.status}).`);
}

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = tokenStorage.refreshToken();
  if (refreshToken === null) {
    return null;
  }

  try {
    const response = await fetch(`${BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });

    if (!response.ok) {
      return null;
    }

    const body = (await response.json()) as { token?: string; refreshToken?: string };
    if (typeof body.token !== 'string') {
      return null;
    }

    // The backend rotates the refresh token on every refresh: the old one is
    // spent, and presenting it again is treated as reuse. Both are stored.
    tokenStorage.save({
      token: body.token,
      refreshToken: typeof body.refreshToken === 'string' ? body.refreshToken : refreshToken,
    });

    return body.token;
  } catch {
    return null;
  }
}

function endSession(): void {
  tokenStorage.clear();
  onSessionEnded?.();
}

async function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {};

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (token !== null) {
    headers.Authorization = `Bearer ${token}`;
  }

  return fetch(`${BASE_URL}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });
}

/**
 * Makes one API call and returns its parsed body.
 *
 * @throws ApiError for any non-2xx answer, and for a network failure - a
 *         caller should not have to tell the two apart to show a message.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const anonymous = options.anonymous === true || AUTH_PATHS.includes(path);
  let token = anonymous ? null : tokenStorage.accessToken();

  let response: Response;
  try {
    response = await send(path, options, token);
  } catch {
    throw new ApiError(0, 'The library service could not be reached. Check your connection and try again.');
  }

  // One refresh, one retry, and only for a request that actually carried a
  // token. A 401 from the sign-in call means the credentials were wrong.
  if (response.status === 401 && !anonymous && token !== null) {
    refreshInFlight ??= refreshAccessToken().finally(() => {
      refreshInFlight = null;
    });

    token = await refreshInFlight;

    if (token === null) {
      endSession();
      throw new ApiError(401, 'Your session has ended. Please sign in again.');
    }

    try {
      response = await send(path, options, token);
    } catch {
      throw new ApiError(0, 'The library service could not be reached. Check your connection and try again.');
    }

    if (response.status === 401) {
      endSession();
      throw new ApiError(401, 'Your session has ended. Please sign in again.');
    }
  }

  if (!response.ok) {
    throw await parseError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

/**
 * Fetches a binary resource and returns it as an object URL.
 *
 * <p>An <img src> is a plain browser request: it carries no Authorization
 * header, so an endpoint behind the token cannot be pointed at directly. This
 * fetches the bytes the way every other call is made - token attached, one
 * refresh on a 401 - and hands back a URL the browser will render.</p>
 *
 * <p><b>The caller must revoke it.</b> An object URL holds the blob in memory
 * until it is released; the component that creates one releases it when it goes
 * away.</p>
 */
export async function apiObjectUrl(path: string, options: RequestOptions = {}): Promise<string> {
  const anonymous = options.anonymous === true || AUTH_PATHS.includes(path);
  let token = anonymous ? null : tokenStorage.accessToken();

  let response: Response;
  try {
    response = await send(path, options, token);
  } catch {
    throw new ApiError(0, 'The library service could not be reached. Check your connection and try again.');
  }

  if (response.status === 401 && !anonymous && token !== null) {
    refreshInFlight ??= refreshAccessToken().finally(() => {
      refreshInFlight = null;
    });

    token = await refreshInFlight;

    if (token === null) {
      endSession();
      throw new ApiError(401, 'Your session has ended. Please sign in again.');
    }

    try {
      response = await send(path, options, token);
    } catch {
      throw new ApiError(0, 'The library service could not be reached. Check your connection and try again.');
    }
  }

  if (!response.ok) {
    throw await parseError(response);
  }

  return URL.createObjectURL(await response.blob());
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => apiRequest<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options?: RequestOptions) => apiRequest<T>(path, { ...options, method: 'DELETE' }),
};
