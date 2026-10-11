const dateFmt = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
const dateTimeFmt = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
const rupee = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 0 });
const number = new Intl.NumberFormat('en-IN');

export function money(paise: number | null | undefined) {
  return rupee.format((paise ?? 0) / 100);
}

export function num(n: number | null | undefined) {
  return number.format(n ?? 0);
}

/** Accepts ISO instants or yyyy-mm-dd dates (treated as local calendar dates). */
export function date(value: string | null | undefined) {
  if (!value) return '—';
  const d = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(value + 'T00:00:00') : new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : dateFmt.format(d);
}

export function dateTime(value: string | null | undefined) {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : dateTimeFmt.format(d);
}

export function relative(value: string | null | undefined) {
  if (!value) return '';
  const diff = Date.now() - new Date(value).getTime();
  const abs = Math.abs(diff);
  const m = Math.round(abs / 60000);
  const future = diff < 0;
  const fmt = (n: number, unit: string) => (future ? `in ${n} ${unit}${n === 1 ? '' : 's'}` : `${n} ${unit}${n === 1 ? '' : 's'} ago`);
  if (m < 1) return 'just now';
  if (m < 60) return fmt(m, 'min');
  const h = Math.round(m / 60);
  if (h < 24) return fmt(h, 'hour');
  const d = Math.round(h / 24);
  if (d < 30) return fmt(d, 'day');
  return date(value);
}

export function daysUntil(isoDate: string) {
  const target = new Date(isoDate + 'T00:00:00');
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((target.getTime() - today.getTime()) / 86400000);
}

export function initials(name: string | null | undefined) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export function bytes(n: number | null | undefined) {
  if (n == null) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

export function titleCase(s: string | null | undefined) {
  if (!s) return '';
  return s.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

export function hashHue(text: string) {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0;
  return Math.abs(h) % 360;
}
