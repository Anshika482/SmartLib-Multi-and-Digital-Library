import { useMemo } from 'react';
import { manageApi } from '@/api/endpoints';
import { useSession } from '@/stores/session';

/** Staff pages always operate on the caller's assigned library (the backend enforces the same scope). */
export function useStaff() {
  const user = useSession((s) => s.user)!;
  const libraryId = user.library?.id ?? 0;
  const api = useMemo(() => manageApi(libraryId), [libraryId]);
  const isAdmin = user.role === 'ADMIN';
  return { user, libraryId, libraryName: user.library?.name ?? '', api, isAdmin, base: isAdmin ? '/app/admin' : '/app/librarian' };
}
