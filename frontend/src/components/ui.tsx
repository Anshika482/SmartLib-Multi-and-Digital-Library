import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router';
import { AlertTriangle, ArrowLeft, CheckCircle2, ChevronRight, Info, Loader2, XCircle } from 'lucide-react';
import { cn } from '@/lib/cn';
import { initials } from '@/lib/format';

type Variant = 'primary' | 'outline' | 'ghost' | 'success' | 'danger' | 'soft-danger';
type Size = 'sm' | 'md' | 'lg';

function btnClass(variant: Variant, size: Size, iconOnly?: boolean, className?: string) {
  return cn('btn', `btn-${variant}`, size === 'sm' && 'btn-sm', size === 'lg' && 'btn-lg', iconOnly && 'btn-icon', className);
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
  iconOnly?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', loading, icon, iconOnly, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button ref={ref} type={type} className={btnClass(variant, size, iconOnly, className)} disabled={disabled || loading}
      aria-busy={loading || undefined} {...rest}>
      {loading ? <Loader2 className="size-4 animate-spin" aria-hidden /> : icon}
      {children}
    </button>
  );
});

export function LinkButton({ variant = 'primary', size = 'md', icon, className, children, ...rest }:
  LinkProps & { variant?: Variant; size?: Size; icon?: ReactNode }) {
  return (
    <Link className={btnClass(variant, size, false, className)} {...rest}>
      {icon}
      {children}
    </Link>
  );
}

export function Spinner({ className, label = 'Loading' }: { className?: string; label?: string }) {
  return <Loader2 className={cn('size-5 animate-spin text-accent-2', className)} aria-label={label} role="status" />;
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('skeleton', className)} aria-hidden />;
}

/** The official SmartLib logo, shown unaltered on a white plate so its artwork stays readable on dark surfaces. */
export function Logo({ size = 'md', to = '/', className }: { size?: 'sm' | 'md' | 'lg' | 'xl'; to?: string | null; className?: string }) {
  const h = { sm: 42, md: 58, lg: 70, xl: 92 }[size];
  // Dark-surface version of the official logo: transparent background, wings/feather/tagline in their original
  // colours, the near-black wordmark in the site's text colour. Emails keep the original white-background file.
  const img = (
    <span className={cn('inline-flex shrink-0 items-center', className)}>
      <img src="/brand/smartlib-logo-dark.png" alt="SmartLib — Intelligence in Every Chapter" style={{ height: h }} className="w-auto max-w-none select-none" draggable={false} />
    </span>
  );
  return to ? <Link to={to} aria-label="SmartLib home" className="inline-flex rounded-xl">{img}</Link> : img;
}

export type Tone = 'blue' | 'cyan' | 'green' | 'amber' | 'red' | 'gray' | 'violet';
const toneClass: Record<Tone, string> = {
  blue: 'bg-accent/12 text-[#8cc4ff] border-accent/30',
  cyan: 'bg-accent-2/12 text-accent-2 border-accent-2/30',
  green: 'bg-success/12 text-success border-success/30',
  amber: 'bg-warning/12 text-warning border-warning/30',
  red: 'bg-danger/12 text-danger border-danger/30',
  gray: 'bg-white/5 text-muted border-line',
  violet: 'bg-violet-400/12 text-violet-300 border-violet-400/30',
};

export function Badge({ tone = 'gray', icon, children, className }: { tone?: Tone; icon?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-semibold', toneClass[tone], className)}>
      {icon}
      {children}
    </span>
  );
}

export function Alert({ tone = 'info', title, children, action, className }:
  { tone?: 'info' | 'success' | 'warning' | 'error'; title?: ReactNode; children?: ReactNode; action?: ReactNode; className?: string }) {
  const styles = {
    info: ['border-accent/30 bg-accent/10', <Info key="i" className="size-5 shrink-0 text-accent-2" aria-hidden />],
    success: ['border-success/30 bg-success/10', <CheckCircle2 key="s" className="size-5 shrink-0 text-success" aria-hidden />],
    warning: ['border-warning/30 bg-warning/10', <AlertTriangle key="w" className="size-5 shrink-0 text-warning" aria-hidden />],
    error: ['border-danger/35 bg-danger/10', <XCircle key="e" className="size-5 shrink-0 text-danger" aria-hidden />],
  }[tone];
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={cn('flex gap-3 rounded-xl border p-3.5 text-sm', styles[0] as string, className)}>
      {styles[1]}
      <div className="min-w-0 flex-1">
        {title && <p className="font-semibold text-fg">{title}</p>}
        {children && <div className={cn('text-muted', title && 'mt-0.5')}>{children}</div>}
        {action && <div className="mt-2.5">{action}</div>}
      </div>
    </div>
  );
}

export function Avatar({ name, className }: { name: string | null | undefined; className?: string }) {
  return (
    <span aria-hidden className={cn('inline-flex size-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-accent to-accent-2 text-xs font-bold text-white ring-2 ring-white/10', className)}>
      {initials(name)}
    </span>
  );
}

export interface Crumb { label: string; to?: string }

export function Breadcrumbs({ items }: { items: Crumb[] }) {
  return (
    <nav aria-label="Breadcrumb" className="mb-2">
      <ol className="flex flex-wrap items-center gap-1 text-xs text-faint">
        {items.map((c, i) => (
          <li key={i} className="flex items-center gap-1">
            {i > 0 && <ChevronRight className="size-3.5" aria-hidden />}
            {c.to ? <Link to={c.to} className="hover:text-fg">{c.label}</Link> : <span aria-current="page" className="text-muted">{c.label}</span>}
          </li>
        ))}
      </ol>
    </nav>
  );
}

/** "← Back to home" link to the landing page. `compact` shows only the arrow below the sm breakpoint. */
export function BackToHome({ compact, className }: { compact?: boolean; className?: string }) {
  return (
    <Link to="/" className={cn('group inline-flex shrink-0 items-center gap-1.5 rounded-lg py-1 text-sm font-medium text-muted transition hover:text-fg', className)}>
      <ArrowLeft className="size-4 transition-transform group-hover:-translate-x-0.5" aria-hidden />
      <span className={compact ? 'sr-only sm:not-sr-only' : undefined}>Back to home</span>
    </Link>
  );
}

export function PageHeader({ title, subtitle, actions, crumbs, icon }:
  { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; crumbs?: Crumb[]; icon?: ReactNode }) {
  return (
    <header className="mb-6">
      {crumbs && <Breadcrumbs items={crumbs} />}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex min-w-0 items-center gap-3">
          {icon && <span className="flex size-11 shrink-0 items-center justify-center rounded-xl border border-line bg-accent/10 text-accent-2">{icon}</span>}
          <div className="min-w-0">
            <h1 className="text-2xl font-bold tracking-tight text-fg sm:text-[1.7rem]">{title}</h1>
            {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
          </div>
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </header>
  );
}

export function Panel({ title, icon, actions, children, className, bodyClassName }:
  { title?: ReactNode; icon?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string }) {
  return (
    <section className={cn('glass p-5', className)}>
      {(title || actions) && (
        <div className="mb-4 flex items-center justify-between gap-3">
          {title && (
            <h2 className="flex items-center gap-2.5 text-base font-semibold text-fg">
              {icon && <span className="text-accent-2">{icon}</span>}
              {title}
            </h2>
          )}
          {actions}
        </div>
      )}
      <div className={bodyClassName}>{children}</div>
    </section>
  );
}

export interface SegmentOption<V extends string> { value: V; label: ReactNode; count?: number }

export function Segmented<V extends string>({ value, onChange, options, label, className }:
  { value: V; onChange: (v: V) => void; options: SegmentOption<V>[]; label: string; className?: string }) {
  return (
    <div role="tablist" aria-label={label} className={cn('inline-flex max-w-full flex-wrap gap-1 rounded-xl border border-line bg-ink/60 p-1', className)}>
      {options.map((o) => (
        <button key={o.value} role="tab" type="button" aria-selected={o.value === value} onClick={() => onChange(o.value)}
          className={cn('rounded-lg px-3 py-1.5 text-xs font-semibold transition sm:text-sm',
            o.value === value ? 'bg-accent/20 text-fg shadow-[inset_0_0_0_1px_rgba(57,197,255,0.35)]' : 'text-muted hover:text-fg')}>
          {o.label}
          {o.count != null && <span className="ml-1.5 rounded-full bg-white/8 px-1.5 text-[11px] text-muted">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function StatCard({ label, value, hint, icon, tone = 'blue', to }:
  { label: string; value: ReactNode; hint?: ReactNode; icon: ReactNode; tone?: Tone; to?: string }) {
  const body = (
    <div className={cn('glass glass-hover flex h-full items-center gap-4 p-4')}>
      <span className={cn('flex size-11 shrink-0 items-center justify-center rounded-xl border', toneClass[tone])}>{icon}</span>
      <div className="min-w-0">
        <p className="truncate text-xs font-medium text-muted">{label}</p>
        <p className="mt-0.5 text-2xl font-bold tracking-tight text-fg">{value}</p>
        {hint && <p className="mt-0.5 truncate text-xs text-faint">{hint}</p>}
      </div>
    </div>
  );
  return to ? <Link to={to} className="block rounded-[1.25rem]">{body}</Link> : body;
}

export function KeyValue({ items, className }: { items: { label: string; value: ReactNode }[]; className?: string }) {
  return (
    <dl className={cn('grid gap-x-6 gap-y-3 sm:grid-cols-2', className)}>
      {items.map((i) => (
        <div key={i.label} className="min-w-0">
          <dt className="text-xs text-faint">{i.label}</dt>
          <dd className="mt-0.5 break-words text-sm text-fg">{i.value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}
