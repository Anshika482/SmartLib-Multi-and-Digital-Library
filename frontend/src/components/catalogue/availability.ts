import type { BookSummary } from '@/types/api';

/**
 * What a book's copy counts mean, said in words.
 *
 * <p>Pure, and separate from the card that shows it, because this is the one
 * piece of the catalogue with a judgement in it: at what point "available"
 * becomes "last copy". Both numbers come from the backend and neither is
 * rounded, invented or padded here.</p>
 */
export type AvailabilityTone = 'available' | 'low' | 'out';

export interface Availability {
  tone: AvailabilityTone;
  /** Short, for a badge. */
  label: string;
  /** Full, for a screen reader and a tooltip. */
  detail: string;
}

export function availabilityOf(book: Pick<BookSummary, 'availableCopies' | 'totalCopies'>): Availability {
  const available = Math.max(0, book.availableCopies);
  const total = Math.max(available, book.totalCopies);
  const copies = (count: number) => `${count} ${count === 1 ? 'copy' : 'copies'}`;

  if (available === 0) {
    return {
      tone: 'out',
      label: 'All on loan',
      detail: total === 1 ? 'The only copy is on loan' : `All ${copies(total)} are on loan`,
    };
  }

  if (available === 1) {
    return {
      tone: 'low',
      label: 'Last copy',
      detail: total === 1 ? 'The only copy is available' : `1 of ${copies(total)} available`,
    };
  }

  return {
    tone: 'available',
    label: `${available} available`,
    detail: `${available} of ${copies(total)} available`,
  };
}
