import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Inbox, LockKeyhole, RefreshCcw, SearchX, ShieldAlert, WifiOff } from 'lucide-react';
import { ApiError } from '@/api/http';
import { Button, LinkButton, Skeleton, Spinner, Logo } from './ui';
import { cn } from '@/lib/cn';

export function EmptyState({ icon, title, description, action, className }:
  { icon?: ReactNode; title: ReactNode; description?: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-col items-center justify-center px-6 py-12 text-center', className)}>
      <span className="mb-4 flex size-14 items-center justify-center rounded-2xl border border-line bg-accent/10 text-accent-2">
        {icon ?? <Inbox className="size-6" aria-hidden />}
      </span>
      <h3 className="text-base font-semibold text-fg">{title}</h3>
      {description && <div className="mt-1.5 max-w-md text-sm text-muted">{description}</div>}
      {action && <div className="mt-5 flex flex-wrap justify-center gap-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, className }: { error: unknown; onRetry?: () => void; className?: string }) {
  const e = error instanceof ApiError ? error : new ApiError(500, 'UNKNOWN', (error as Error)?.message ?? 'Something went wrong.');
  if (e.isNetwork) {
    return (
      <EmptyState className={className} icon={<WifiOff className="size-6" aria-hidden />} title="You're offline or SmartLib is unreachable"
        description="Check your internet connection. We'll keep your place." action={onRetry && <Button variant="outline" icon={<RefreshCcw className="size-4" />} onClick={onRetry}>Try again</Button>} />
    );
  }
  if (e.status === 401) {
    return (
      <EmptyState className={className} icon={<LockKeyhole className="size-6" aria-hidden />} title="Please log in to continue"
        description="Your session has ended or this page needs an account." action={<LinkButton to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}>Log in</LinkButton>} />
    );
  }
  if (e.status === 403) {
    return (
      <EmptyState className={className} icon={<ShieldAlert className="size-6" aria-hidden />} title="You don't have access to this"
        description={e.message} action={<LinkButton to="/" variant="outline">Go home</LinkButton>} />
    );
  }
  if (e.status === 404) {
    return <EmptyState className={className} icon={<SearchX className="size-6" aria-hidden />} title="Not found" description={e.message} />;
  }
  return (
    <EmptyState className={className} icon={<ShieldAlert className="size-6 text-danger" aria-hidden />} title="Something went wrong"
      description={<>{e.message}{e.requestId && <span className="mt-1 block text-xs text-faint">Reference: {e.requestId}</span>}</>}
      action={onRetry && <Button variant="outline" icon={<RefreshCcw className="size-4" />} onClick={onRetry}>Try again</Button>} />
  );
}

export function LoadingBlock({ rows = 4, className }: { rows?: number; className?: string }) {
  return (
    <div className={cn('space-y-3', className)} aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} className="h-14 w-full" />)}
    </div>
  );
}

/** Standard loading / error / empty / success handling for every API-backed view. */
export function QueryState<T>({ query, isEmpty, empty, loading, children }:
  { query: UseQueryResult<T>; isEmpty?: (data: T) => boolean; empty?: ReactNode; loading?: ReactNode; children: (data: T) => ReactNode }) {
  if (query.isPending) return <>{loading ?? <LoadingBlock />}</>;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  if (isEmpty?.(query.data)) return <>{empty ?? <EmptyState title="Nothing here yet" />}</>;
  return <>{children(query.data)}</>;
}

export function FullPageLoader({ label = 'Loading SmartLib…' }: { label?: string }) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-5">
      <Logo size="lg" to={null} className="animate-[float_4s_ease-in-out_infinite]" />
      <div className="flex items-center gap-2 text-sm text-muted"><Spinner className="size-4" />{label}</div>
    </div>
  );
}

export function NotFoundPage() {
  return (
    <div className="mx-auto flex min-h-[70dvh] max-w-lg flex-col items-center justify-center px-6 text-center">
      <p className="text-gradient text-7xl font-extrabold">404</p>
      <h1 className="mt-3 text-2xl font-bold">This page isn't on our shelves</h1>
      <p className="mt-2 text-muted">The link may be broken or the page may have moved.</p>
      <div className="mt-6 flex gap-2">
        <LinkButton to="/">Go home</LinkButton>
        <LinkButton to="/catalogue" variant="outline">Browse catalogue</LinkButton>
      </div>
    </div>
  );
}

export function ForbiddenPage() {
  return (
    <div className="mx-auto flex min-h-[70dvh] max-w-lg flex-col items-center justify-center px-6 text-center">
      <span className="flex size-16 items-center justify-center rounded-2xl border border-line bg-danger/10 text-danger"><ShieldAlert className="size-7" /></span>
      <h1 className="mt-4 text-2xl font-bold">You don't have access to this area</h1>
      <p className="mt-2 text-muted">Your account role doesn't include this workspace. If you think this is wrong, contact your library administrator.</p>
      <div className="mt-6 flex gap-2">
        <LinkButton to="/app">Go to my dashboard</LinkButton>
        <Link to="/" className="btn btn-outline">Home</Link>
      </div>
    </div>
  );
}
