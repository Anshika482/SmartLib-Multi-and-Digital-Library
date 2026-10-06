import { afterEach } from 'vitest';

/**
 * What every test file gets before it runs.
 *
 * <p>Guarded on there being a document: most of the suite is pure logic in a
 * Node environment, where these imports have nothing to attach to.</p>
 *
 * <p>The cleanup matters. Testing Library renders into {@code document.body}
 * and only registers its own {@code afterEach} when Vitest is running with
 * globals, which this project does not - so without this, one test's markup is
 * still on the page during the next and queries match the wrong copy.</p>
 */
if (typeof document !== 'undefined') {
  await import('@testing-library/jest-dom/vitest');
  const { cleanup } = await import('@testing-library/react');

  afterEach(() => {
    cleanup();
  });
}
