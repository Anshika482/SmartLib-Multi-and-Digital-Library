/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type AuthState } from '@/auth/AuthContext';
import { ReadingPage } from './ReadingPage';
import { ResourceReaderPage } from './ResourceReaderPage';
import { digitalResourceService } from '@/services/digitalResourceService';
import { ApiError } from '@/services/apiClient';
import type { DigitalResource, Page, ResourceType, Role, UserProfile } from '@/types/api';

/**
 * Digital reading: the list, and the reader for each type.
 *
 * <p>The service is stubbed, so these are about what each screen asks for and
 * what it renders. Who may open what is the server's decision and is proven
 * against a real database by {@code DigitalResourceServiceTest} and
 * {@code DigitalResourceApiIntegrationTest}.</p>
 *
 * <p>The property pinned hardest: <b>no resource is ever read into this
 * application</b>. A PDF is handed to the browser by URL and a video to the
 * player by URL; several tests below check that the element carries the address
 * and that nothing fetched the bytes.</p>
 */

const list = vi.spyOn(digitalResourceService, 'list');
const get = vi.spyOn(digitalResourceService, 'get');

function resource(overrides: Partial<DigitalResource> = {}): DigitalResource {
  return {
    id: 12,
    bookId: 101,
    bookTitle: 'The Left Hand of Darkness',
    title: 'Chapter one',
    description: 'The opening chapter.',
    resourceType: 'PDF',
    resourceUrl: 'https://library.example.invalid/chapter-one.pdf',
    enabled: true,
    createdAt: '2026-09-01T10:00:00',
    updatedAt: '2026-09-01T10:00:00',
    ...overrides,
  };
}

function page(content: DigitalResource[]): Page<DigitalResource> {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 };
}

function renderWith(node: React.ReactElement, role: Role, path: string, routePath?: string) {
  const value: AuthState = {
    user: { id: 1, username: 'asha', email: 'a@b.invalid', role, enabled: true } as UserProfile,
    loading: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[path]}>
        {routePath === undefined ? (
          node
        ) : (
          <Routes>
            <Route path={routePath} element={node} />
          </Routes>
        )}
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const renderList = (role: Role = 'ROLE_MEMBER') =>
  renderWith(<ReadingPage />, role, '/app/reading');

const renderReader = (role: Role = 'ROLE_MEMBER', id = '12') =>
  renderWith(<ResourceReaderPage />, role, `/app/reading/${id}`, '/app/reading/:resourceId');

beforeEach(() => {
  list.mockReset();
  get.mockReset();
  list.mockResolvedValue(page([resource()]));
  get.mockResolvedValue(resource());
});

// ========== the list ==========

describe('the reading list', () => {
  it('asks for the caller’s own library, with no library to send', async () => {
    renderList();

    await screen.findByText('Chapter one');

    expect(JSON.stringify(list.mock.calls[0][0] ?? {})).not.toContain('library');
  });

  it('opens each item in the reader rather than at the file', async () => {
    renderList();

    await screen.findByText('Chapter one');

    // Into the reader: that is where the type is handled and the link made safe.
    expect(screen.getByRole('link', { name: 'Chapter one' })).toHaveAttribute(
      'href',
      '/app/reading/12',
    );
  });

  it('names the book each item belongs to, and prints no ids', async () => {
    const { container } = renderList();

    await screen.findByText('Chapter one');

    expect(screen.getByText('The Left Hand of Darkness')).toBeInTheDocument();
    // The ids belong in the href, not on screen.
    expect(container.textContent).not.toContain('101');
    expect(container.textContent).not.toContain('#12');
  });

  it('says a library with nothing online has nothing, not that something broke', async () => {
    list.mockResolvedValue(page([]));
    renderList();

    expect(await screen.findByText('Nothing online yet')).toBeInTheDocument();
  });

  it('shows a placeholder while it loads', () => {
    list.mockReturnValue(new Promise(() => {}));
    const { container } = renderList();

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
  });

  it("shows the API's own message and a retry when the list fails", async () => {
    const user = userEvent.setup();
    list.mockRejectedValueOnce(new ApiError(500, 'Reading is unavailable.'));
    renderList();

    expect(await screen.findByText('Reading is unavailable.')).toBeInTheDocument();

    list.mockResolvedValue(page([resource()]));
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.getByText('Chapter one')).toBeInTheDocument());
  });
});

// ========== each type ==========

describe('a PDF', () => {
  it('is handed to the browser viewer by address, not read into the page', async () => {
    const { container } = renderReader();

    await screen.findByTitle('Chapter one, as a PDF');

    const frame = container.querySelector('iframe');
    expect(frame).toHaveAttribute('src', 'https://library.example.invalid/chapter-one.pdf');

    // Nothing fetched the document itself.
    expect(container.textContent).not.toContain('%PDF');
  });

  it('is sandboxed, so the document cannot reach this page', async () => {
    const { container } = renderReader();

    await screen.findByTitle('Chapter one, as a PDF');

    expect(container.querySelector('iframe')).toHaveAttribute('sandbox');
  });

  it('still offers a way to open it directly', async () => {
    renderReader();

    const open = await screen.findByRole('link', { name: 'Open the PDF in a new tab' });
    expect(open).toHaveAttribute('rel', 'noopener noreferrer');
    expect(open).toHaveAttribute('target', '_blank');
  });
});

describe('a video', () => {
  it('uses the browser player with controls, by address', async () => {
    get.mockResolvedValue(
      resource({ resourceType: 'VIDEO', resourceUrl: 'https://library.example.invalid/talk.mp4' }),
    );
    const { container } = renderReader();

    await screen.findByRole('link', { name: 'Open the recording in a new tab' });

    const video = container.querySelector('video');
    expect(video).toHaveAttribute('src', 'https://library.example.invalid/talk.mp4');
    expect(video).toHaveAttribute('controls');
    // Metadata only until somebody presses play.
    expect(video).toHaveAttribute('preload', 'metadata');
  });

  it('shows nothing about any other resource', async () => {
    get.mockResolvedValue(resource({ resourceType: 'VIDEO', title: 'A talk' }));
    const { container } = renderReader();

    await screen.findByRole('heading', { level: 1, name: 'A talk' });

    // One resource on the page, and no trace of the rest of the catalogue.
    expect(container.querySelectorAll('video')).toHaveLength(1);
    expect(list).not.toHaveBeenCalled();
  });
});

describe('an e-book', () => {
  it('is handed to the reading app, and says why', async () => {
    get.mockResolvedValue(
      resource({ resourceType: 'EPUB', resourceUrl: 'https://library.example.invalid/book.epub' }),
    );
    const { container } = renderReader();

    expect(await screen.findByText('This is an e-book')).toBeInTheDocument();

    const open = screen.getByRole('link', { name: 'Open the e-book' });
    expect(open).toHaveAttribute('href', 'https://library.example.invalid/book.epub');
    expect(open).toHaveAttribute('rel', 'noopener noreferrer');

    // No frame pretending to render it.
    expect(container.querySelector('iframe')).toBeNull();
  });
});

describe('a link', () => {
  it('opens in a new tab, safely, and says it leaves the library', async () => {
    get.mockResolvedValue(
      resource({ resourceType: 'LINK', resourceUrl: 'https://elsewhere.example.invalid/article' }),
    );
    const { container } = renderReader();

    expect(await screen.findByText('This is a link to another site')).toBeInTheDocument();

    const open = screen.getByRole('link', { name: 'Open the link' });
    expect(open).toHaveAttribute('href', 'https://elsewhere.example.invalid/article');
    expect(open).toHaveAttribute('target', '_blank');
    // noopener so the opened page cannot navigate this one through window.opener.
    expect(open).toHaveAttribute('rel', 'noopener noreferrer');

    expect(container.querySelector('iframe')).toBeNull();
  });
});

describe('every outward link', () => {
  it.each<ResourceType>(['PDF', 'EPUB', 'VIDEO', 'LINK'])(
    'carries noopener and noreferrer for %s',
    async (resourceType) => {
      get.mockResolvedValue(resource({ resourceType }));
      const { container } = renderReader();

      await waitFor(() => expect(container.querySelector('a[target="_blank"]')).not.toBeNull());

      container.querySelectorAll('a[target="_blank"]').forEach((anchor) => {
        expect(anchor.getAttribute('rel')).toContain('noopener');
        expect(anchor.getAttribute('rel')).toContain('noreferrer');
      });
    },
  );
});

// ========== access ==========

describe('what a member may open', () => {
  it('treats a disabled resource as simply not there', async () => {
    // The server answers 404 for a disabled resource, for another library's,
    // and for one that never existed. The reader must not distinguish them.
    get.mockRejectedValue(new ApiError(404, 'Digital resource not found with id: 12'));
    renderReader('ROLE_MEMBER');

    expect(await screen.findByText('Digital resource not found with id: 12')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Open/ })).not.toBeInTheDocument();
  });

  it('gives the same answer for another library’s resource', async () => {
    get.mockRejectedValue(new ApiError(404, 'Digital resource not found with id: 99'));
    renderReader('ROLE_MEMBER', '99');

    expect(await screen.findByText('Digital resource not found with id: 99')).toBeInTheDocument();
  });

  it('refuses a reference that is not a resource id without asking the server', async () => {
    renderReader('ROLE_MEMBER', 'not-a-number');

    expect(await screen.findByText('That is not something this library publishes.')).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });

  it('shows a member no hidden badge, because a member is sent no hidden resource', async () => {
    renderReader('ROLE_MEMBER');

    await screen.findByRole('heading', { level: 1, name: 'Chapter one' });

    expect(screen.queryByText('Hidden from members')).not.toBeInTheDocument();
  });

  it('marks a disabled resource for the staff who can still open it', async () => {
    get.mockResolvedValue(resource({ enabled: false }));
    renderReader('ROLE_LIBRARIAN');

    expect(await screen.findByText('Hidden from members')).toBeInTheDocument();
  });

  it('links back to the book it belongs to', async () => {
    renderReader();

    await screen.findByRole('heading', { level: 1, name: 'Chapter one' });

    expect(screen.getByRole('link', { name: 'Go to the book' })).toHaveAttribute(
      'href',
      '/app/catalogue/101',
    );
  });
});

// ========== reader states ==========

describe('reader states', () => {
  it('shows a placeholder before the resource arrives', () => {
    get.mockReturnValue(new Promise(() => {}));
    const { container } = renderReader();

    expect(container.querySelectorAll('.sl-skeleton').length).toBeGreaterThan(0);
  });

  it('retries when the resource will not load', async () => {
    const user = userEvent.setup();
    get.mockRejectedValueOnce(new ApiError(500, 'Not this time.'));
    renderReader();
    await screen.findByText('Not this time.');

    get.mockResolvedValue(resource());
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'Chapter one' })).toBeInTheDocument(),
    );
  });

  it('offers a way out when a recording will not play', async () => {
    get.mockResolvedValue(resource({ resourceType: 'VIDEO', title: 'A talk' }));
    const { container } = renderReader();

    await screen.findByRole('heading', { level: 1, name: 'A talk' });
    const video = container.querySelector('video') as HTMLVideoElement;

    // A media element does report a broken source, so this fallback is real.
    fireEvent.error(video);

    expect(await screen.findByText('This would not open here')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open it in a new tab' })).toBeInTheDocument();
    expect(container.querySelector('video')).toBeNull();
  });

  it('always offers a way out of a framed document, since a frame cannot report failure', async () => {
    // A cross-origin frame fires load even when the host refuses to be framed,
    // so there is no failure to detect. The link is therefore always there
    // rather than appearing after one.
    renderReader();

    await screen.findByTitle('Chapter one, as a PDF');

    expect(screen.getByRole('link', { name: 'Open the PDF in a new tab' })).toBeInTheDocument();
  });
});
