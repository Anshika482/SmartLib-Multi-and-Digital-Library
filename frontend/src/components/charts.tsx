import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { MonthPoint } from '@/api/types';
import { EmptyState } from './states';
import { BarChart3 } from 'lucide-react';

const animate = typeof window === 'undefined' || !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

const axis = { stroke: '#7d93b2', fontSize: 11, tickLine: false, axisLine: false } as const;
const tooltipStyle = {
  contentStyle: { background: '#0d1c31', border: '1px solid rgba(110,170,255,.3)', borderRadius: 12, color: '#f4f8ff', fontSize: 12 },
  labelStyle: { color: '#a6b8d0' },
  cursor: { fill: 'rgba(22,136,255,.08)' },
};

function monthLabel(m: string) {
  const [y, mo] = m.split('-').map(Number);
  return new Date(y, mo - 1, 1).toLocaleString('en-IN', { month: 'short' });
}

export function TrendChart({ data, height = 260 }: { data: MonthPoint[]; height?: number }) {
  if (!data.some((d) => d.issued || d.returned)) {
    return <EmptyState icon={<BarChart3 className="size-6" />} title="No borrowing activity yet" description="Trends appear as books are issued and returned." />;
  }
  const rows = data.map((d) => ({ ...d, label: monthLabel(d.month) }));
  return (
    <div style={{ height }} role="img" aria-label="Monthly borrowing trend: books issued and returned">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={rows} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
          <defs>
            <linearGradient id="gIssued" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#1688ff" stopOpacity={0.55} /><stop offset="100%" stopColor="#1688ff" stopOpacity={0} /></linearGradient>
            <linearGradient id="gReturned" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#39c5ff" stopOpacity={0.35} /><stop offset="100%" stopColor="#39c5ff" stopOpacity={0} /></linearGradient>
          </defs>
          <CartesianGrid stroke="rgba(148,186,255,.08)" vertical={false} />
          <XAxis dataKey="label" {...axis} />
          <YAxis allowDecimals={false} {...axis} />
          <Tooltip {...tooltipStyle} />
          <Legend wrapperStyle={{ fontSize: 12, color: '#a6b8d0' }} />
          <Area type="monotone" dataKey="issued" name="Issued" stroke="#1688ff" strokeWidth={2} fill="url(#gIssued)" isAnimationActive={animate} />
          <Area type="monotone" dataKey="returned" name="Returned" stroke="#39c5ff" strokeWidth={2} fill="url(#gReturned)" isAnimationActive={animate} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

export function BarsChart<T extends object>({ data, xKey, bars, height = 260, label }:
  { data: T[]; xKey: string; bars: { key: string; name: string; color: string }[]; height?: number; label: string }) {
  if (!data.length || !data.some((d) => bars.some((b) => Number((d as Record<string, unknown>)[b.key]) > 0))) {
    return <EmptyState icon={<BarChart3 className="size-6" />} title="No data yet" description="This chart fills in as activity is recorded." />;
  }
  return (
    <div style={{ height }} role="img" aria-label={label}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
          <CartesianGrid stroke="rgba(148,186,255,.08)" vertical={false} />
          <XAxis dataKey={xKey} {...axis} interval={0} tick={{ fontSize: 10 }} />
          <YAxis allowDecimals={false} {...axis} />
          <Tooltip {...tooltipStyle} />
          {bars.length > 1 && <Legend wrapperStyle={{ fontSize: 12, color: '#a6b8d0' }} />}
          {bars.map((b) => <Bar key={b.key} dataKey={b.key} name={b.name} fill={b.color} radius={[6, 6, 0, 0]} maxBarSize={36} isAnimationActive={animate} />)}
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
