import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, apiRequest, setSessionEndedListener } from './apiClient';
import { tokenStorage } from './tokenStorage';

/**
 * What the client does around a token, which is the part with consequences:
 * an expired token is refreshed once rather than per request, a refresh that
 * fails ends the session instead of looping, and a failure of any kind reaches
 * the caller as an ApiError it can show.
 *
 * No real network and no real storage - fetch and window.localStorage are both
 * stand-ins, so the test asserts on the calls the client made.
 */

function memoryStorage(): Storage {
  const entries = new Map<string, string>();
  return {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => void entries.set(key, value),
    removeItem: (key: string) => void entries.delete(key),
    clear: () => entries.clear(),
    key: (index: number) => [...entries.keys()][index] ?? null,
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

beforeEach(() => {
  vi.stubGlobal('window', { localStorage: memoryStorage() });
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  setSessionEndedListener(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('a plain request', () => {
  it('sends the stored access token and returns the parsed body', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });
    fetchMock.mockResolvedValueOnce(json(200, { username: 'asha' }));

    await expect(api.get('/api/users/me')).resolves.toEqual({ username: 'asha' });

    const [, init] = fetchMock.mock.calls[0];
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer access-1');
  });

  it('sends no token for the sign-in call', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });
    fetchMock.mockResolvedValueOnce(json(200, { token: 't', refreshToken: 'r' }));

    await api.post('/api/auth/login', { username: 'asha', password: 'x' });

    const [, init] = fetchMock.mock.calls[0];
    expect((init?.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('returns nothing for a 204 rather than trying to parse it', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(apiRequest('/api/transactions/1')).resolves.toBeUndefined();
  });
});

describe('an error', () => {
  it("surfaces the API's own message and status", async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });
    fetchMock.mockResolvedValueOnce(json(403, { status: 403, message: 'Access denied', timestamp: 'now' }));

    const failure = await api.get('/api/libraries').catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).message).toBe('Access denied');
    expect((failure as ApiError).isForbidden).toBe(true);
  });

  it('turns a body that is not the usual shape into an ApiError all the same', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });
    fetchMock.mockResolvedValueOnce(new Response('<html>502</html>', { status: 502 }));

    await expect(api.get('/api/books')).rejects.toBeInstanceOf(ApiError);
  });

  it('turns an unreachable server into an ApiError instead of a raw network failure', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    const failure = await api.get('/api/books').catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(0);
  });
});

describe('an expired access token', () => {
  it('is refreshed once and the original request is retried', async () => {
    tokenStorage.save({ token: 'stale', refreshToken: 'refresh-1' });
    fetchMock
      .mockResolvedValueOnce(json(401, { status: 401, message: 'Unauthorized' }))
      .mockResolvedValueOnce(json(200, { token: 'fresh', refreshToken: 'refresh-2' }))
      .mockResolvedValueOnce(json(200, { username: 'asha' }));

    await expect(api.get('/api/users/me')).resolves.toEqual({ username: 'asha' });

    expect(fetchMock.mock.calls[1][0]).toContain('/api/auth/refresh');
    expect(tokenStorage.accessToken()).toBe('fresh');
    expect(tokenStorage.refreshToken()).toBe('refresh-2');

    const [, retry] = fetchMock.mock.calls[2];
    expect((retry?.headers as Record<string, string>).Authorization).toBe('Bearer fresh');
  });

  it('is refreshed once for several requests that fail together, not once each', async () => {
    tokenStorage.save({ token: 'stale', refreshToken: 'refresh-1' });
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/refresh')) {
        return Promise.resolve(json(200, { token: 'fresh', refreshToken: 'refresh-2' }));
      }
      const [, init] = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
      const authorization = (init?.headers as Record<string, string>).Authorization;
      return Promise.resolve(
        authorization === 'Bearer fresh' ? json(200, { ok: true }) : json(401, { status: 401, message: 'Unauthorized' }),
      );
    });

    await Promise.all([api.get('/api/books'), api.get('/api/categories'), api.get('/api/libraries')]);

    const refreshes = fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/auth/refresh'));
    expect(refreshes).toHaveLength(1);
  });

  it('ends the session when the refresh is refused, and does not keep retrying', async () => {
    tokenStorage.save({ token: 'stale', refreshToken: 'spent' });
    const sessionEnded = vi.fn();
    setSessionEndedListener(sessionEnded);

    fetchMock
      .mockResolvedValueOnce(json(401, { status: 401, message: 'Unauthorized' }))
      .mockResolvedValueOnce(json(401, { status: 401, message: 'Unauthorized' }));

    await expect(api.get('/api/users/me')).rejects.toBeInstanceOf(ApiError);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(sessionEnded).toHaveBeenCalledOnce();
    expect(tokenStorage.has()).toBe(false);
    expect(tokenStorage.refreshToken()).toBeNull();
  });

  it('is not refreshed when the 401 came from signing in, so a wrong password is just a wrong password', async () => {
    fetchMock.mockResolvedValueOnce(json(401, { status: 401, message: 'Invalid username or password' }));

    const failure = await api
      .post('/api/auth/login', { username: 'asha', password: 'wrong' })
      .catch((error: unknown) => error);

    expect(fetchMock).toHaveBeenCalledOnce();
    expect((failure as ApiError).message).toBe('Invalid username or password');
  });
});
