import { useEffect, useState } from 'react';
import { BarChart, Bar, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { benchApi, type BenchCost, type BenchDashboard } from '../../api/bench';
import { BarChart3, TrendingUp, Banknote, UserMinus, Users, Percent } from 'lucide-react';
import { card, CardTitle, Empty, ErrorBox, money, StatTile, td, th } from './ui';

const tooltipStyle = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, fontSize: 12, boxShadow: '0 10px 24px rgba(16,24,40,.12)' };
const axisTick = { fontSize: 10.5, fill: 'var(--txt-dim)' };

/** US-A03 dashboard (headcount, bench %, aging buckets, 90-day trend) and US-A13 aggregate cost. */
export default function OverviewSection({ token }: { token: string }) {
  const [dash, setDash] = useState<BenchDashboard | null>(null);
  const [cost, setCost] = useState<BenchCost | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    Promise.all([benchApi.dashboard(token), benchApi.cost(token)])
      .then(([d, c]) => { setDash(d); setCost(c); })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load the bench dashboard'));
  }, [token]);

  if (error) return <ErrorBox message={error} />;
  if (!dash || !cost) return <Empty>Loading…</Empty>;

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <div className="nf-bn-tiles">
        <StatTile tone="var(--warn)" icon={<UserMinus size={20} />} label="On bench" value={String(dash.benchedCount)} hint="Active bench episodes" />
        <StatTile tone="var(--info)" icon={<Users size={20} />} label="Total headcount" value={String(dash.totalHeadcount)} hint="Active employees (Employee Master)" />
        <StatTile tone="var(--brand)" icon={<Percent size={20} />} label="Bench %" value={`${dash.benchPercent.toFixed(1)}%`} hint="Benched ÷ headcount" />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 16 }}>
        <div style={card}>
          <CardTitle icon={<BarChart3 size={16} />}>Bench aging</CardTitle>
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={dash.buckets} margin={{ top: 4, right: 0, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
              <XAxis dataKey="bucket" tick={axisTick} axisLine={{ stroke: 'var(--line2)' }} tickLine={false} />
              <YAxis allowDecimals={false} tick={axisTick} axisLine={false} tickLine={false} width={30} />
              <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'color-mix(in srgb, var(--brand) 8%, transparent)' }} />
              <Bar dataKey="count" name="Employees" fill="var(--brand)" radius={[8, 8, 0, 0]} maxBarSize={64} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div style={card}>
          <CardTitle icon={<TrendingUp size={16} />}>Bench trend: last 90 days</CardTitle>
          <ResponsiveContainer width="100%" height={220}>
            <LineChart data={dash.trend} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
              <XAxis dataKey="date" tick={axisTick} axisLine={{ stroke: 'var(--line2)' }} tickLine={false} />
              <YAxis allowDecimals={false} tick={axisTick} axisLine={false} tickLine={false} width={30} />
              <Tooltip contentStyle={tooltipStyle} />
              <Line type="monotone" dataKey="benchedCount" name="On bench" stroke="var(--brand)" strokeWidth={2.5} dot={dash.trend.length < 20} activeDot={{ r: 5 }} />
            </LineChart>
          </ResponsiveContainer>
          {dash.trend.length < 2 && (
            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
              The trend fills in as the nightly snapshot job runs each day.
            </div>
          )}
        </div>
      </div>

      <div style={card}>
        <CardTitle icon={<Banknote size={16} />}>Aggregate bench cost</CardTitle>
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginBottom: 8 }}>
          Benched headcount per compensation band × the band's average CTC. Band averages only, no individual salary is ever shown.
        </div>
        {cost.rows.length === 0 ? (
          <Empty>{cost.unbandedCount > 0
            ? `${cost.unbandedCount} benched employee(s) have no compensation band yet — assign bands in Settings.`
            : 'No benched employees to cost.'}</Empty>
        ) : (
          <table className="nf-bn-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr><th style={th}>Band</th><th style={th}>Benched</th><th style={th}>Avg CTC</th><th style={th}>Total</th></tr></thead>
            <tbody>
              {cost.rows.map(r => (
                <tr key={r.bandLabel}><td style={td}>{r.bandLabel}</td><td style={td}>{r.benchedCount}</td><td style={td}>{money(r.avgCtc)}</td><td style={td}>{money(r.totalCost)}</td></tr>
              ))}
              <tr><td style={{ ...td, fontWeight: 800, background: 'var(--raised)' }} colSpan={3}>Total</td><td style={{ ...td, fontWeight: 800, background: 'var(--raised)' }}>{money(cost.totalCost)}</td></tr>
            </tbody>
          </table>
        )}
        {cost.rows.length > 0 && cost.unbandedCount > 0 && (
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 8 }}>
            {cost.unbandedCount} benched employee(s) without a band are not included in the total.
          </div>
        )}
      </div>
    </div>
  );
}
