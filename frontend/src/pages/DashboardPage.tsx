import { useEffect, useMemo, useState } from 'react';
import { Users, Clock, Calendar, TrendingUp, UserCheck } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useAuthStore } from '../store/authStore';
import { dashboardApi, type DirectReport, type ManagerDashboard } from '../api/dashboard';

/* ── Placeholder card for unbuilt modules ────────── */
function PendingCard({ icon: Icon, title, note }: {
  icon: React.FC<{ size: number; style?: React.CSSProperties }>;
  title: string;
  note: string;
}) {
  return (
    <div style={{
      background: 'var(--panel)', border: '1px dashed var(--line2)', borderRadius: 10,
      padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
        <Icon size={14} style={{ color: 'var(--txt-dim)' }} />
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{title}</span>
        <span style={{ marginLeft: 'auto', fontSize: 10, fontWeight: 600, padding: '2px 7px', borderRadius: 20, background: 'rgba(107,114,128,.12)', color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em', whiteSpace: 'nowrap' }}>
          Not yet built
        </span>
      </div>
      <p style={{ margin: 0, fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.55 }}>{note}</p>
    </div>
  );
}

/* ── Joiners-per-month chart ─────────────────────── */
function buildJoinerData(): { month: string; joiners: number }[] {
  const counts: Record<string, number> = {};
  const today = new Date();
  for (let i = 11; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth() - i, 1);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    counts[key] = 0;
  }
  return Object.entries(counts).map(([month, joiners]) => ({
    month: new Date(month + '-01').toLocaleDateString('en-US', { month: 'short', year: '2-digit' }),
    joiners,
  }));
}

function JoinersChart({ reports: _reports }: { reports: DirectReport[] }) {
  const data = useMemo(() => buildJoinerData(), []);
  const hasData = data.some(d => d.joiners > 0);

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          Team Joiners per Month
        </span>
        <span style={{ fontSize: 11, color: 'var(--txt-dim)', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 5, padding: '2px 8px' }}>
          Last 12 months
        </span>
      </div>
      <p style={{ margin: '0 0 16px', fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
        {hasData
          ? 'New team members joining per calendar month.'
          : 'Available once DirectReport records carry joining dates. Joining dates are stored per employee — the API will be enriched in a future slice to include them here.'}
      </p>
      {hasData ? (
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={data} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
            <XAxis dataKey="month" tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} allowDecimals={false} />
            <Tooltip
              contentStyle={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' }}
              cursor={{ fill: 'rgba(177,17,22,.08)' }}
            />
            <Bar dataKey="joiners" name="New joiners" fill="#B11116" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <div style={{ height: 180, display: 'grid', placeItems: 'center', background: 'var(--raised)', borderRadius: 8, border: '1px dashed var(--line2)' }}>
          <div style={{ textAlign: 'center' }}>
            <TrendingUp size={24} style={{ color: 'var(--line2)', marginBottom: 8 }} />
            <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Joiner data available once API is enriched</div>
          </div>
        </div>
      )}
    </div>
  );
}

/* ── Manager dashboard ───────────────────────────── */
function ManagerDashboardView() {
  const token = useAuthStore(s => s.token) ?? '';
  const user  = useAuthStore(s => s.user);
  const [data, setData]   = useState<ManagerDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError]   = useState('');

  useEffect(() => {
    dashboardApi.managerDashboard(token)
      .then(setData)
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load team data'))
      .finally(() => setLoading(false));
  }, [token]);

  const firstName = user?.firstName ?? user?.email?.split('@')[0] ?? 'Manager';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          Welcome back, {firstName}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>Manager Dashboard</p>
      </div>

      {/* KPI row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Users size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Direct Reports</span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>
            {loading ? '—' : error ? '—' : (data?.directReportCount ?? 0)}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>current reports from HR record</div>
        </div>

        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <UserCheck size={14} style={{ color: 'var(--ok)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Active</span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>
            {loading ? '—' : error ? '—' : (data?.directReports.filter(r => r.active).length ?? 0)}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>active in team</div>
        </div>

        <div style={{ background: 'var(--panel)', border: '1px dashed var(--line2)', borderRadius: 10, padding: '18px 20px', opacity: .7 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Clock size={14} style={{ color: 'var(--txt-dim)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Present Today</span>
          </div>
          <div style={{ fontSize: 12, color: 'var(--txt-dim)', lineHeight: 1.5 }}>Available once Attendance module is built</div>
        </div>

        <div style={{ background: 'var(--panel)', border: '1px dashed var(--line2)', borderRadius: 10, padding: '18px 20px', opacity: .7 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Calendar size={14} style={{ color: 'var(--txt-dim)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>On Leave</span>
          </div>
          <div style={{ fontSize: 12, color: 'var(--txt-dim)', lineHeight: 1.5 }}>Available once Leave module is built</div>
        </div>
      </div>

      {/* Team list + chart */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        {/* Direct reports table */}
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
            <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>Your Team</span>
          </div>
          {loading ? (
            <div style={{ padding: '32px 20px', fontSize: 13, color: 'var(--txt-mut)' }}>Loading…</div>
          ) : error ? (
            <div style={{ padding: '20px', fontSize: 13, color: 'var(--risk)' }}>{error}</div>
          ) : !data || data.directReports.length === 0 ? (
            <div style={{ padding: '32px 20px', fontSize: 13, color: 'var(--txt-mut)' }}>
              No direct reports assigned. HR assigns them in Employee Master.
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Name', 'Designation', 'Status'].map(h => (
                      <th key={h} style={{ padding: '8px 14px', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', textAlign: 'left', borderBottom: '1px solid var(--line)', background: 'var(--raised)' }}>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.directReports.map(r => (
                    <tr key={r.userId} style={{ borderBottom: '1px solid var(--line)' }}>
                      <td style={{ padding: '10px 14px', fontSize: 12.5 }}>
                        <div style={{ fontWeight: 600, color: 'var(--txt)', marginBottom: 1 }}>{r.fullName}</div>
                        <div style={{ fontSize: 11, color: 'var(--txt-dim)', fontFamily: '"JetBrains Mono", monospace' }}>{r.employeeCode}</div>
                      </td>
                      <td style={{ padding: '10px 14px', fontSize: 12, color: 'var(--txt-mut)' }}>{r.designationName ?? '—'}</td>
                      <td style={{ padding: '10px 14px' }}>
                        <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: r.active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)', color: r.active ? 'var(--ok)' : 'var(--txt-dim)' }}>
                          {r.active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Joiner chart */}
        <JoinersChart reports={data?.directReports ?? []} />
      </div>

      {/* Honest pending cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 10 }}>
        <PendingCard
          icon={Clock}
          title="Attendance Summary"
          note="Will show daily team check-in/out status. Available once the Attendance module is built."
        />
        <PendingCard
          icon={Calendar}
          title="Pending Leave Requests"
          note="Leave approvals from your team will appear here. Available once the Leave module is built."
        />
        <PendingCard
          icon={TrendingUp}
          title="Performance Overview"
          note="Goals and review cycles. Available once the Performance module is built."
        />
      </div>
    </div>
  );
}

/* ── Generic dashboard (Employee, HR Admin, Super Admin) ── */
function GenericDashboardView({ role }: { role: string }) {
  const user = useAuthStore(s => s.user);
  const firstName = user?.firstName ?? user?.email?.split('@')[0] ?? 'there';

  const label: Record<string, string> = {
    SUPER_ADMIN: 'Super Admin Dashboard',
    HR_ADMIN:    'HR Dashboard',
    EMPLOYEE:    'My Dashboard',
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          Welcome back, {firstName}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>{label[role] ?? 'Dashboard'}</p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12 }}>
        <PendingCard
          icon={Clock}
          title="Attendance"
          note="Your attendance log and check-in status. Available once the Attendance module is built."
        />
        <PendingCard
          icon={Calendar}
          title="Leave & Holidays"
          note="Request leave, view holiday calendar. Available once the Leave module is built."
        />
        <PendingCard
          icon={TrendingUp}
          title="Performance"
          note="Goals, reviews, and growth. Available once the Performance module is built."
        />
      </div>
    </div>
  );
}

/* ── Entry point ─────────────────────────────────── */
export default function DashboardPage() {
  const role = useAuthStore(s => s.user?.role) ?? '';
  if (role === 'MANAGER') return <ManagerDashboardView />;
  return <GenericDashboardView role={role} />;
}
