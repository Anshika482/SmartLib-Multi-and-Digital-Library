import { describe, expect, it } from 'vitest';
import { availabilityOf } from './availability';

/**
 * The only judgement call in the catalogue: when a count becomes a warning.
 * Every number it reports has to be one the backend sent.
 */
describe('availability', () => {
  it('reports the real count when there are copies on the shelf', () => {
    const availability = availabilityOf({ availableCopies: 3, totalCopies: 5 });

    expect(availability.tone).toBe('available');
    expect(availability.label).toBe('3 available');
    expect(availability.detail).toBe('3 of 5 copies available');
  });

  it('calls a single remaining copy the last one', () => {
    const availability = availabilityOf({ availableCopies: 1, totalCopies: 4 });

    expect(availability.tone).toBe('low');
    expect(availability.label).toBe('Last copy');
    expect(availability.detail).toBe('1 of 4 copies available');
  });

  it('says so when everything is out', () => {
    const availability = availabilityOf({ availableCopies: 0, totalCopies: 6 });

    expect(availability.tone).toBe('out');
    expect(availability.detail).toBe('All 6 copies are on loan');
  });

  it('stays grammatical when the library holds one copy', () => {
    expect(availabilityOf({ availableCopies: 0, totalCopies: 1 }).detail).toBe('The only copy is on loan');
    expect(availabilityOf({ availableCopies: 1, totalCopies: 1 }).detail).toBe('The only copy is available');
  });

  it('never reports more available than the library holds, whatever it is sent', () => {
    // Not expected from the backend, but a count that reads "7 of 3" on screen
    // would be worse than one that quietly clamps.
    const availability = availabilityOf({ availableCopies: 7, totalCopies: 3 });

    expect(availability.detail).toBe('7 of 7 copies available');
  });

  it('treats a negative count as none rather than showing a minus sign', () => {
    expect(availabilityOf({ availableCopies: -2, totalCopies: 4 }).tone).toBe('out');
  });
});
