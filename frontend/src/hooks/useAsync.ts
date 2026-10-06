import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@/services/apiClient';

/**
 * Runs one API call and reports where it got to.
 *
 * <p>Three states, always exactly one of them: loading, failed, or loaded.
 * A page renders from this rather than from a bare `data` that is null for two
 * different reasons - "still fetching" and "there is nothing" look identical
 * otherwise, and they need different things on screen.</p>
 *
 * <p>The call is given an AbortSignal and the result of an aborted call is
 * dropped, so a page that unmounts mid-flight does not set state afterwards -
 * including under StrictMode, which runs every effect twice in development.</p>
 *
 * <p><b>`enabled` decides whether the call is made at all.</b> A section that
 * needs a signed-in caller passes false while nobody is, so the request is
 * never sent rather than sent and refused - a 401 nobody can act on is noise
 * in the log and an error state on screen that misdescribes the situation.</p>
 */
export interface AsyncState<T> {
  data: T | null;
  loading: boolean;
  /** A message fit to show a person, or null. */
  error: string | null;
  reload: () => void;
}

export function useAsync<T>(
  task: (signal: AbortSignal) => Promise<T>,
  deps: readonly unknown[],
  enabled = true,
): AsyncState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  // The task is rebuilt on every render by its caller, so the effect below
  // depends on the caller's own deps instead - the same contract useCallback
  // has, and the reason this hook takes a dependency list at all.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(task, deps);

  useEffect(() => {
    if (!enabled) {
      // Not loading, not failed, and nothing fetched: the three states a
      // caller renders from stay consistent when the call is skipped.
      setData(null);
      setLoading(false);
      setError(null);
      return;
    }

    const controller = new AbortController();
    let live = true;

    setLoading(true);
    setError(null);

    run(controller.signal)
      .then((result) => {
        if (live) {
          setData(result);
        }
      })
      .catch((failure: unknown) => {
        if (!live || controller.signal.aborted) {
          return;
        }

        setError(
          failure instanceof ApiError
            ? failure.message
            : 'Something went wrong loading this. Please try again.',
        );
      })
      .finally(() => {
        if (live) {
          setLoading(false);
        }
      });

    return () => {
      live = false;
      controller.abort();
    };
  }, [run, attempt, enabled]);

  const reload = useCallback(() => setAttempt((previous) => previous + 1), []);

  return { data, loading, error, reload };
}
