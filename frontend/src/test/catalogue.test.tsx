import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CataloguePage from '@/features/catalogue/CataloguePage';
import { renderRoutes } from './utils';

const setup = (path = '/catalogue') => renderRoutes([{ path: '/catalogue', element: <CataloguePage /> }, { path: '/catalogue/:id', element: <p>details</p> }], path);

function librariesShown() {
  const strips = screen.getAllByRole('list', { name: 'Availability by library' });
  return new Set(strips.flatMap((s) => within(s).getAllByRole('listitem').map((li) => li.textContent?.match(/Library [ABC]/)?.[0])));
}

describe('Public catalogue (spec §79)', () => {
  it('search spans all libraries, the library filter restricts, and clearing restores everything', async () => {
    const user = userEvent.setup();
    const { router } = setup();
    expect(await screen.findByText('Showing', { exact: false })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/of/, { selector: 'p' })).toHaveTextContent('25'));

    // 1. Search "java" -> results from A + B + C
    await user.type(screen.getByLabelText('Search by title, author or ISBN'), 'java');
    await waitFor(() => expect(router.state.location.search).toContain('q=java'), { timeout: 2000 });
    await waitFor(() => expect(screen.getByText('23', { selector: 'b' })).toBeInTheDocument());
    expect(router.state.location.search).not.toContain('library');

    // 2. Select Library B -> only Library B results
    await user.selectOptions(screen.getByRole('combobox', { name: 'Library' }), 'Library B');
    await waitFor(() => expect(screen.getByText('8', { selector: 'b' })).toBeInTheDocument());
    expect(librariesShown()).toEqual(new Set(['Library B']));
    expect(router.state.location.search).toContain('q=java');

    // 3. Back to All Libraries -> A + B + C again (search kept)
    await user.selectOptions(screen.getByRole('combobox', { name: 'Library' }), 'All Libraries');
    await waitFor(() => expect(screen.getByText('23', { selector: 'b' })).toBeInTheDocument());
    expect(librariesShown()).toEqual(new Set(['Library A', 'Library B', 'Library C']));

    // 4. Clear search -> all books from all libraries
    await user.click(screen.getByRole('button', { name: 'Clear search' }));
    await waitFor(() => expect(screen.getByText('25', { selector: 'b' })).toBeInTheDocument());
    expect(router.state.location.search).toBe('');
  });

  it('zero results keep the page usable with a way back to all books', async () => {
    const user = userEvent.setup();
    setup('/catalogue?q=xyz');
    expect(await screen.findByText(/No books found for/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show all books' }));
    await waitFor(() => expect(screen.getByText('25', { selector: 'b' })).toBeInTheDocument());
  });

  it('deep links restore search, filter and page from the URL', async () => {
    setup('/catalogue?q=java&library=1&page=1');
    await waitFor(() => expect(screen.getByText('10', { selector: 'b' })).toBeInTheDocument());
    expect(screen.getByLabelText('Search by title, author or ISBN')).toHaveValue('java');
    expect(screen.getByRole('combobox', { name: 'Library' })).toHaveValue('1');
  });

  it('changing a filter resets pagination to the first page', async () => {
    const user = userEvent.setup();
    const { router } = setup('/catalogue?q=java&page=2');
    await screen.findByText('23', { selector: 'b' });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Library' }), 'Library A');
    await waitFor(() => expect(router.state.location.search).not.toContain('page='));
  });
});
