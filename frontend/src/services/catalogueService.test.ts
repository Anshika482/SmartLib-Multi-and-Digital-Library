import { beforeEach, describe, expect, it, vi } from 'vitest';
import { bookQueryString, catalogueService } from './catalogueService';
import { resourceQueryString, digitalResourceService } from './digitalResourceService';
import { CATEGORY_PAGE_MAX, categoryService } from './categoryService';
import { tokenStorage } from './tokenStorage';

/**
 * The query these services send.
 *
 * <p>Worth pinning down because the backend validates it: a size above 50 or a
 * sortBy outside its whitelist is a 400, and both whitelists are mirrored in
 * these modules. If one drifts from the other, it should fail here rather than
 * on somebody's screen.</p>
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

function requestedUrl(): URL {
  return new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost');
}

beforeEach(() => {
  vi.stubGlobal('window', { localStorage: memoryStorage() });
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(
    new Response(JSON.stringify({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
  tokenStorage.save({ token: 'access', refreshToken: 'refresh' });
});

describe('the book query', () => {
  it('uses the backend defaults when asked for nothing in particular', () => {
    const params = new URLSearchParams(bookQueryString());

    expect(params.get('page')).toBe('0');
    expect(params.get('size')).toBe('10');
    expect(params.get('sortBy')).toBe('id');
    expect(params.get('direction')).toBe('asc');
    expect(params.has('keyword')).toBe(false);
    expect(params.has('categoryId')).toBe(false);
  });

  it('sends what it was given', () => {
    const params = new URLSearchParams(
      bookQueryString({ page: 2, size: 8, sortBy: 'availableCopies', direction: 'desc', categoryId: 4 }),
    );

    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('8');
    expect(params.get('sortBy')).toBe('availableCopies');
    expect(params.get('direction')).toBe('desc');
    expect(params.get('categoryId')).toBe('4');
  });

  it('trims a keyword and leaves a blank one out entirely', () => {
    expect(new URLSearchParams(bookQueryString({ keyword: '  dune  ' })).get('keyword')).toBe('dune');
    expect(new URLSearchParams(bookQueryString({ keyword: '   ' })).has('keyword')).toBe(false);
  });

  it('escapes a keyword rather than pasting it into the URL', () => {
    const params = new URLSearchParams(bookQueryString({ keyword: 'rock & roll' }));

    expect(bookQueryString({ keyword: 'rock & roll' })).toContain('rock+%26+roll');
    expect(params.get('keyword')).toBe('rock & roll');
  });

  it('asks the books endpoint, with the query attached', async () => {
    await catalogueService.list({ page: 0, size: 8, sortBy: 'availableCopies', direction: 'desc' });

    const url = requestedUrl();
    expect(url.pathname).toBe('/api/books');
    expect(url.searchParams.get('size')).toBe('8');
    expect(url.searchParams.get('sortBy')).toBe('availableCopies');
  });
});

describe('the digital resource query', () => {
  it('defaults to the newest first, which is what a preview wants', () => {
    const params = new URLSearchParams(resourceQueryString());

    expect(params.get('sortBy')).toBe('createdAt');
    expect(params.get('direction')).toBe('desc');
  });

  it('asks the digital resources endpoint, with the query attached', async () => {
    await digitalResourceService.list({ page: 0, size: 4 });

    const url = requestedUrl();
    expect(url.pathname).toBe('/api/digital-resources');
    expect(url.searchParams.get('size')).toBe('4');
  });

  it('never asks for a library: the backend decides that from the token', async () => {
    await digitalResourceService.list({ page: 0, size: 4, bookId: 9 });

    const url = requestedUrl();
    expect(url.searchParams.has('libraryId')).toBe(false);
    expect(url.searchParams.get('bookId')).toBe('9');
  });
});

describe('one book', () => {
  it('asks for it by id, with nothing else attached', async () => {
    await catalogueService.get(7);

    const url = requestedUrl();
    expect(url.pathname).toBe('/api/books/7');
    expect(url.search).toBe('');
  });
});

describe('the category query', () => {
  it('asks for one page of shelves by name, at the size the backend allows', async () => {
    await categoryService.list();

    const url = requestedUrl();
    expect(url.pathname).toBe('/api/categories');
    expect(url.searchParams.get('size')).toBe(String(CATEGORY_PAGE_MAX));
    expect(url.searchParams.get('sortBy')).toBe('name');
    expect(url.searchParams.get('direction')).toBe('asc');
  });

  it('stays inside the size the backend accepts, which a 400 would otherwise be', () => {
    // CategoryService caps a page at fifty. Asking for more is an error, not a
    // larger page, so this constant is the ceiling rather than a preference.
    expect(CATEGORY_PAGE_MAX).toBeLessThanOrEqual(50);
  });

  it('never asks for a library: the backend decides that from the token', async () => {
    await categoryService.list();

    expect(requestedUrl().searchParams.has('libraryId')).toBe(false);
  });
});
