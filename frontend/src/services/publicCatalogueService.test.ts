import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_PUBLIC_PAGE_SIZE, publicCatalogueQuery, publicCatalogueService } from './publicCatalogueService';
import { tokenStorage } from './tokenStorage';

/**
 * The public catalogue call.
 *
 * <p>Two properties worth holding: it never asks for more than the backend
 * allows, and it never sends a token. The second matters because a visitor has
 * none and a signed-in caller's token would make a public request look
 * authenticated - and a stale one would drag a refresh into a call that never
 * needed it.</p>
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

function requested(): { url: URL; init: RequestInit | undefined } {
  const [input, init] = fetchMock.mock.calls[0];
  return { url: new URL(String(input), 'http://localhost'), init };
}

beforeEach(() => {
  vi.stubGlobal('window', { localStorage: memoryStorage() });
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(
    new Response(JSON.stringify({ content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
});

describe('the public query', () => {
  it('defaults to the first page at a size the backend accepts', () => {
    const params = new URLSearchParams(publicCatalogueQuery());

    expect(params.get('page')).toBe('0');
    expect(Number(params.get('size'))).toBeLessThanOrEqual(MAX_PUBLIC_PAGE_SIZE);
    expect(params.has('keyword')).toBe(false);
  });

  it('never asks for more than the public ceiling, however much is requested', () => {
    const params = new URLSearchParams(publicCatalogueQuery({ size: 500 }));

    expect(params.get('size')).toBe(String(MAX_PUBLIC_PAGE_SIZE));
  });

  it('trims a keyword and omits a blank one', () => {
    expect(new URLSearchParams(publicCatalogueQuery({ keyword: '  dune ' })).get('keyword')).toBe('dune');
    expect(new URLSearchParams(publicCatalogueQuery({ keyword: '   ' })).has('keyword')).toBe(false);
  });
});

describe('the public request', () => {
  it('goes to the public endpoint', async () => {
    await publicCatalogueService.search({ keyword: 'dune' });

    expect(requested().url.pathname).toBe('/api/public/catalogue');
    expect(requested().url.searchParams.get('keyword')).toBe('dune');
  });

  it('sends no token even when one is stored', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });

    await publicCatalogueService.search();

    const headers = (requested().init?.headers ?? {}) as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
  });

  it('asks the library list without a token too', async () => {
    tokenStorage.save({ token: 'access-1', refreshToken: 'refresh-1' });
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );

    await publicCatalogueService.libraries();

    expect(requested().url.pathname).toBe('/api/public/libraries');
    expect(((requested().init?.headers ?? {}) as Record<string, string>).Authorization).toBeUndefined();
  });
});
