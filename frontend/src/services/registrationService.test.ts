import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registrationService } from './registrationService';
import { ApiError } from './apiClient';
import { tokenStorage } from './tokenStorage';
import { registrationTypeLabel, type RegistrationType } from '@/types/api';

/**
 * What the client sends when somebody registers.
 *
 * <p>The property worth holding is negative: the request carries a type, never
 * a role, so a modified client has nothing privileged to ask for. The server
 * decides regardless - this only checks the client is not offering it a way to
 * be talked into something.</p>
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

const fetchMock = vi.fn<typeof fetch>();

function sent(): { url: URL; init: RequestInit | undefined; body: Record<string, unknown> } {
  const [input, init] = fetchMock.mock.calls[0];
  return {
    url: new URL(String(input), 'http://localhost'),
    init,
    body: JSON.parse(String(init?.body)) as Record<string, unknown>,
  };
}

beforeEach(() => {
  vi.stubGlobal('window', { localStorage: memoryStorage() });
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(
    new Response(JSON.stringify({ status: 'APPROVED', message: 'Your account is ready.' }), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
});

describe('registering', () => {
  it('posts to the public endpoint', async () => {
    await registrationService.register({
      type: 'MEMBER',
      username: 'asha',
      email: 'asha@example.invalid',
      fullName: 'Asha Rao',
      password: 'a-long-enough-password',
      libraryId: 7,
    });

    expect(sent().url.pathname).toBe('/api/auth/register');
    expect(sent().init?.method).toBe('POST');
  });

  it('sends no token, even when one is stored', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });

    await registrationService.register({
      type: 'MEMBER',
      username: 'asha',
      email: 'asha@example.invalid',
      fullName: 'Asha Rao',
      password: 'a-long-enough-password',
      libraryId: 7,
    });

    expect(((sent().init?.headers ?? {}) as Record<string, string>).Authorization).toBeUndefined();
  });

  it('never sends a role, only a type', async () => {
    await registrationService.register({
      type: 'ADMIN',
      username: 'asha',
      email: 'asha@example.invalid',
      fullName: 'Asha Rao',
      password: 'a-long-enough-password',
      libraryName: 'New Library',
    });

    const body = sent().body;
    expect(body.type).toBe('ADMIN');
    expect(body.role).toBeUndefined();
    expect(Object.keys(body)).not.toContain('role');
  });

  it('carries the chosen library for a member and the new name for an administrator', async () => {
    await registrationService.register({
      type: 'LIBRARIAN',
      username: 'asha',
      email: 'asha@example.invalid',
      fullName: 'Asha Rao',
      password: 'a-long-enough-password',
      libraryId: 7,
    });

    expect(sent().body.libraryId).toBe(7);
    expect(sent().body.libraryName).toBeUndefined();
  });

  it('surfaces a rate limit as a 429 the form can single out', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 429 }));

    const failure = await registrationService
      .register({
        type: 'MEMBER',
        username: 'asha',
        email: 'asha@example.invalid',
        fullName: 'Asha Rao',
        password: 'a-long-enough-password',
        libraryId: 7,
      })
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(429);
  });
});

describe('the registration vocabulary', () => {
  it('offers exactly three kinds, none of them privileged', () => {
    const types: RegistrationType[] = ['MEMBER', 'LIBRARIAN', 'ADMIN'];

    for (const type of types) {
      expect(registrationTypeLabel(type).length).toBeGreaterThan(0);
    }

    // Not a runtime check so much as a statement: there is no fourth value in
    // the type, so no form control can produce one.
    expect(types).not.toContain('SUPER_ADMIN' as RegistrationType);
  });
});
