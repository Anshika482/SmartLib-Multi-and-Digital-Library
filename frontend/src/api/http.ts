
import { useSession } from '@/stores/session';
import type { LoginResponse } from './types';

const API_BASE_URL = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '');

export const apiUrl = (path: string): string => {
  if (!path.startsWith('/api/')) return path;
  return `${API_BASE_URL}${path}`;
};

export interface FieldError {
  field: string;
  message: string;
}

/** Normalised API error (RFC 9457 problem details + network failures). */
export class ApiError extends Error {
  status: number;
  code: string;
  fieldErrors: FieldError[];
  details: Record<string, unknown>;
  requestId?: string;

  constructor(
    status: number,
    code: string,
    message: string,
    details: Record<string, unknown> = {},
  ) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
    this.fieldErrors = (details.fieldErrors as FieldError[]) ?? [];
    this.requestId = details.requestId as string | undefined;
  }

  get isNetwork() {
    return this.status === 0;
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

let refreshing: Promise<boolean> | null = null;

/** Single-flight refresh using the httpOnly refresh cookie. */
export function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch(apiUrl('/api/auth/refresh'), {
          method: 'POST',
          credentials: 'include',
          headers: {
            'X-Requested-With': 'SmartLib',
            Accept: 'application/json',
          },
        });

        if (res.status === 204 || !res.ok) {
          useSession.getState().clear();
          return false;
        }

        const data = (await res.json()) as LoginResponse;
        useSession.getState().setSession(data.accessToken, data.user);
        return true;
      } catch {
        // Network failure: preserve existing state unless it is unknown.
        if (useSession.getState().status === 'unknown') {
          useSession.getState().clear();
        }
        return false;
      } finally {
        setTimeout(() => {
          refreshing = null;
        }, 0);
      }
    })();
  }

  return refreshing;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  /** Skip the automatic refresh-and-retry on 401 (used by auth endpoints). */
  noRetry?: boolean;
}

async function toError(res: Response): Promise<ApiError> {
  let payload: Record<string, unknown> = {};

  try {
    payload = await res.json();
  } catch {
    // Non-JSON error body.
  }

  const message =
    (payload.detail as string) ||
    (payload.title as string) ||
    res.statusText ||
    'Request failed';

  return new ApiError(
    res.status,
    (payload.code as string) || `HTTP_${res.status}`,
    message,
    payload,
  );
}

export async function http<T>(
  path: string,
  opts: RequestOptions = {},
): Promise<T> {
  const doFetch = () => {
    const token = useSession.getState().accessToken;
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...opts.headers,
    };

    let body: BodyInit | undefined;

    if (opts.body instanceof FormData) {
      body = opts.body;
    } else if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }

    if (
      token &&
      !path.startsWith('/api/auth/login') &&
      !path.startsWith('/api/auth/refresh')
    ) {
      headers.Authorization = `Bearer ${token}`;
    }

    return fetch(apiUrl(path), {
      method: opts.method ?? 'GET',
      headers,
      body,
      credentials: 'include',
      signal: opts.signal,
    });
  };

  let res: Response;

  try {
    res = await doFetch();

    if (
      res.status === 401 &&
      !opts.noRetry &&
      useSession.getState().accessToken
    ) {
      const ok = await refreshSession();
      if (ok) res = await doFetch();
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;

    throw new ApiError(
      0,
      'NETWORK_ERROR',
      'Unable to reach SmartLib. Check your connection and try again.',
    );
  }

  if (!res.ok) throw await toError(res);
  if (res.status === 204) return undefined as T;

  const type = res.headers.get('Content-Type') ?? '';

  if (!type.includes('json')) {
    return (await res.text()) as unknown as T;
  }

  return (await res.json()) as T;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) =>
    http<T>(path, { signal }),

  post: <T>(path: string, body?: unknown) =>
    http<T>(path, { method: 'POST', body: body ?? {} }),

  put: <T>(path: string, body?: unknown) =>
    http<T>(path, { method: 'PUT', body: body ?? {} }),

  patch: <T>(path: string, body?: unknown) =>
    http<T>(path, { method: 'PATCH', body: body ?? {} }),

  del: <T>(path: string) =>
    http<T>(path, { method: 'DELETE' }),

  upload: <T>(path: string, form: FormData) =>
    http<T>(path, { method: 'POST', body: form }),
};

export function qs(
  params: Record<string, string | number | boolean | null | undefined>,
) {
  const sp = new URLSearchParams();

  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '' && v !== false) {
      sp.set(k, String(v));
    }
  });

  const s = sp.toString();
  return s ? `?${s}` : '';
}

/** Authenticated file download (CSV exports) that respects the in-memory token. */
export async function download(path: string, filename: string) {
  const token = useSession.getState().accessToken;

  const res = await fetch(apiUrl(path), {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    credentials: 'include',
  });

  if (!res.ok) throw await toError(res);

  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');

  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

