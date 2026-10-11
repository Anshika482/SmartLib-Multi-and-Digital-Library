import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import type { CatalogueItem } from '@/api/types';

const libraries = [
  { id: 1, name: 'Library A', slug: 'library-a', code: 'LIBA' },
  { id: 2, name: 'Library B', slug: 'library-b', code: 'LIBB' },
  { id: 3, name: 'Library C', slug: 'library-c', code: 'LIBC' },
];

/** §79 fixture: A has 10 Java titles, B has 8, C has 5, plus two non-Java titles. */
export const fixtureBooks: CatalogueItem[] = (() => {
  const out: CatalogueItem[] = [];
  let id = 1;
  const add = (title: string, lib: (typeof libraries)[number]) => out.push({
    id: id++, title, subtitle: null, authors: ['Author'], isbn13: null, coverUrl: null, isbnCoverUrl: null, category: null,
    publishedYear: 2020, language: 'English', publisher: null, totalAvailable: 1, libraryCount: 1,
    holdings: [{ holdingId: id, libraryId: lib.id, libraryName: lib.name, librarySlug: lib.slug, city: null, availableCopies: 1, totalCopies: 1 }],
  });
  // interleaved like a real title sort, so the first page already spans all three libraries
  for (let i = 1; i <= 10; i++) {
    ([[0, 10], [1, 8], [2, 5]] as const).forEach(([li, n]) => { if (i <= n) add(`Java Programming ${i} (${libraries[li].code})`, libraries[li]); });
  }
  add('Cooking Basics', libraries[0]);
  add('History of Art', libraries[1]);
  return out;
})();

export const handlers = [
  http.post('/api/auth/refresh', () => new HttpResponse(null, { status: 204 })),
  http.get('/api/public/demo-accounts', () => HttpResponse.json({ enabled: false, password: null, accounts: [] })),
  http.get('/api/public/libraries', () => HttpResponse.json(libraries.map((l) => ({ ...l, tagline: null, description: null, addressLine: null, city: null, state: null,
    postalCode: null, publicEmail: null, publicPhone: null, website: null, openingHours: null, imageUrl: null, titles: 0, copies: 0, availableCopies: 0, digitalResources: 0 })))),
  http.get('/api/public/categories', () => HttpResponse.json([])),
  http.get('/api/public/languages', () => HttpResponse.json(['English'])),
  http.get('/api/public/catalogue', ({ request }) => {
    const url = new URL(request.url);
    const q = (url.searchParams.get('q') ?? '').toLowerCase();
    const lib = url.searchParams.get('libraryId');
    const page = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? 12);
    let rows = fixtureBooks.filter((b) => !q || b.title.toLowerCase().includes(q));
    if (lib) rows = rows.filter((b) => b.holdings.some((h) => h.libraryId === Number(lib)));
    return HttpResponse.json({ content: rows.slice(page * size, page * size + size), page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) });
  }),
];

export const server = setupServer(...handlers);
