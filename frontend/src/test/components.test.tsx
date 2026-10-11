import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { BookCover } from '@/components/book';
import { OtpInput } from '@/components/form';
import { StatusBadge } from '@/components/status';
import { ErrorState } from '@/components/states';
import { ApiError } from '@/api/http';
import { renderWithRouter } from './utils';

describe('BookCover', () => {
  it('shows a generated placeholder when no cover exists (never a broken image)', () => {
    render(<BookCover book={{ title: 'Clean Code', authors: ['Robert C. Martin'] }} />);
    expect(screen.getByText('Clean Code')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('falls through local -> ISBN cover -> placeholder on errors', () => {
    render(<BookCover book={{ title: 'Dune', coverUrl: '/api/public/covers/1', isbnCoverUrl: 'https://covers.openlibrary.org/b/isbn/1-M.jpg' }} />);
    const first = screen.getByAltText('Cover of Dune');
    expect(first).toHaveAttribute('src', '/api/public/covers/1');
    fireEvent.error(first);
    const second = screen.getByAltText('Cover of Dune');
    expect(second).toHaveAttribute('src', expect.stringContaining('openlibrary'));
    fireEvent.error(second);
    expect(screen.queryByAltText('Cover of Dune')).not.toBeInTheDocument();
    expect(screen.getByText('Dune')).toBeInTheDocument();
  });
});

describe('OtpInput', () => {
  function Harness({ onDone }: { onDone: (v: string) => void }) {
    const [v, setV] = useState('');
    return <OtpInput value={v} onChange={(x) => { setV(x); onDone(x); }} />;
  }

  it('accepts a pasted 6-digit code and ignores non-digits', async () => {
    const spy = vi.fn();
    render(<Harness onDone={spy} />);
    const first = screen.getByLabelText('Digit 1');
    fireEvent.paste(first, { clipboardData: { getData: () => '12-34 56' } });
    expect(spy).toHaveBeenLastCalledWith('123456');
    expect(screen.getByLabelText('Digit 6')).toHaveValue('6');
  });

  it('auto-advances while typing', async () => {
    const user = userEvent.setup();
    const spy = vi.fn();
    render(<Harness onDone={spy} />);
    await user.click(screen.getByLabelText('Digit 1'));
    await user.keyboard('4a2');
    expect(spy).toHaveBeenLastCalledWith('42');
    expect(screen.getByLabelText('Digit 3')).toHaveFocus();
  });
});

describe('Status and error states', () => {
  it('status badges carry text, not just colour', () => {
    render(<><StatusBadge status="PENDING" /><StatusBadge status="OVERDUE" /><StatusBadge status="PAID" /></>);
    expect(screen.getByText('Awaiting approval')).toBeInTheDocument();
    expect(screen.getByText('Overdue')).toBeInTheDocument();
    expect(screen.getByText('Paid')).toBeInTheDocument();
  });

  it('distinguishes offline, unauthorized and forbidden errors', () => {
    const { unmount } = renderWithRouter(<ErrorState error={new ApiError(0, 'NETWORK_ERROR', 'x')} />);
    expect(screen.getByText(/offline/)).toBeInTheDocument();
    unmount();
    const r2 = renderWithRouter(<ErrorState error={new ApiError(401, 'UNAUTHORIZED', 'x')} />);
    expect(screen.getByText('Please log in to continue')).toBeInTheDocument();
    r2.unmount();
    renderWithRouter(<ErrorState error={new ApiError(403, 'FORBIDDEN', 'Nope')} />);
    expect(screen.getByText("You don't have access to this")).toBeInTheDocument();
  });
});
