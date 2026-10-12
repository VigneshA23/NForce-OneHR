import { useEffect, useState } from 'react';
import { Users, Sparkles, Award, BookOpen, AlertTriangle } from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Legend,
} from 'recharts';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { talentDashboardApi, talentDirectoryApi, type TalentDashboardSummary, type TalentEmployeeSummary } from '../../api/talentOrg';
import { DetailModalShell } from '../../components/TeamGrowthModal';
import { LEVEL_TONE, Pill, EmployeeDetailView } from './EmployeeDetailView';
import { useEmployeeDetail } from './useEmployeeDetail';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const PIE_PALETTE = ['#B11116', '#4C8DD6', '#2FB67C', '#E0A93B', '#8B5FBF', 'var(--raised2)'];

function KpiCard({ icon, label, value, note, danger, onClick }: {
  icon: React.ReactNode; label: string; value: string | number; note?: string; danger?: boolean; onClick?: () => void;
}) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag onClick={onClick} title={onClick ? 'Click to view employees' : undefined} style={{
      background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px',
      textAlign: 'left', font: 'inherit', width: '100%', cursor: onClick ? 'pointer' : 'default',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
      </div>
      <div style={{ fontSize: 28, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: danger ? '#E4373D' : 'var(--txt)', lineHeight: 1 }}>{value}</div>
      {note && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>{note}</div>}
    </Tag>
  );
}

function ChartCard({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, padding: '16px 18px' }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{title}</div>
      {hint && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2, marginBottom: 10 }}>{hint}</div>}
      {!hint && <div style={{ marginBottom: 12 }} />}
      {children}
    </div>
  );
}

const tooltipStyle = { contentStyle: { background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' } };

/** Mirrors the backend's countExpiringWithin: today <= expiryDate <= today+days (cumulative, not an exclusive bucket). */
function expiresWithin(expiryDate: string | null, days: number): boolean {
  if (!expiryDate) return false;
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const expiry = new Date(expiryDate); expiry.setHours(0, 0, 0, 0);
  const diffDays = Math.round((expiry.getTime() - today.getTime()) / 86400000);
  return diffDays >= 0 && diffDays <= days;
}

/** Mirrors the backend's learningStatusCounts grouping: "Planned" is displayed as "Not Started". */
function learningStatusLabel(status: string): string {
  return status === 'Planned' ? 'Not Started' : status;
}

export function DashboardTab() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [data, setData] = useState<TalentDashboardSummary | null>(null);
  const [rows, setRows] = useState<TalentEmployeeSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [department, setDepartment] = useState<string>('');

  const [drillTitle, setDrillTitle] = useState<string | null>(null);
  const [drillEmployees, setDrillEmployees] = useState<TalentEmployeeSummary[]>([]);
  const [selectedEmployee, setSelectedEmployee] = useState<string | null>(null);
  const { detail, detailLoading, openDetail, closeDetail } = useEmployeeDetail(token);

  useEffect(() => {
    Promise.all([talentDashboardApi.get(token), talentDirectoryApi.listAll(token)])
      .then(([summary, directory]) => { setData(summary); setRows(directory); })
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load the dashboard'))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  function openDrill(title: string, employees: TalentEmployeeSummary[]) {
    setDrillTitle(title);
    setDrillEmployees(employees);
    setSelectedEmployee(null);
  }

  function closeDrill() {
    setDrillTitle(null);
    setDrillEmployees([]);
    setSelectedEmployee(null);
    closeDetail();
  }

  function selectEmployee(id: string) {
    setSelectedEmployee(id);
    openDetail(id);
  }

  function backToList() {
    setSelectedEmployee(null);
    closeDetail();
  }

  if (loading) return <div style={{ color: 'var(--txt-mut)', fontSize: 13, padding: '20px 0' }}>Loading…</div>;
  if (!data) return null;

  const departments = Object.keys(data.skillsByDepartment).sort();
  const skillsByDeptChart = department ? data.skillsByDepartment[department] ?? [] : data.topSkills;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
        <KpiCard icon={<Users size={14} />} label="Total Employees" value={data.totalEmployees}
          onClick={() => openDrill('All Employees', rows)} />
        <KpiCard icon={<Sparkles size={14} />} label="With Skills Logged" value={data.withSkills} note={`of ${data.totalEmployees}`}
          onClick={() => openDrill('Employees With Skills Logged', rows.filter(r => r.skills.length > 0))} />
        <KpiCard icon={<Award size={14} />} label="With Certificates" value={data.withCertificates} note={`of ${data.totalEmployees}`}
          onClick={() => openDrill('Employees With Certificates', rows.filter(r => r.certificates.length > 0))} />
        <KpiCard icon={<AlertTriangle size={14} />} label="Certs Expiring (30d)" value={data.certificatesExpiringIn30Days} danger={data.certificatesExpiringIn30Days > 0}
          onClick={() => openDrill('Certificates Expiring in 30 Days', rows.filter(r => r.certificates.some(c => expiresWithin(c.expiryDate, 30))))} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: 12 }} className="nf-grid-side-collapse">
        <ChartCard title="Top skills org-wide" hint={data.topSkills.length > 0 ? 'Click a bar to view employees' : undefined}>
          {data.topSkills.length === 0 ? (
            <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No skills logged yet.</div>
          ) : (
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={data.topSkills.slice(0, 8)} layout="vertical" margin={{ top: 0, right: 12, left: 10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" horizontal={false} />
                <XAxis type="number" tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} allowDecimals={false} />
                <YAxis type="category" dataKey="name" width={90} tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} />
                <Tooltip {...tooltipStyle} cursor={{ fill: 'rgba(177,17,22,.08)' }} />
                <Bar dataKey="count" name="Employees" fill="#B11116" radius={[0, 4, 4, 0]} cursor="pointer"
                  onClick={(entry: { name?: string }) => entry.name && openDrill(
                    `Employees with "${entry.name}"`, rows.filter(r => r.skills.some(s => s.skillName === entry.name)))} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <ChartCard title="Proficiency distribution" hint={data.proficiencyDistribution.length > 0 ? 'Click a slice to view employees' : undefined}>
          {data.proficiencyDistribution.length === 0 ? (
            <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No skills logged yet.</div>
          ) : (
            <ResponsiveContainer width="100%" height={240}>
              <PieChart>
                <Pie data={data.proficiencyDistribution} cx="50%" cy="50%" innerRadius="50%" outerRadius="85%" dataKey="count" nameKey="name" strokeWidth={0}
                  cursor="pointer"
                  onClick={(entry: { name?: string }) => entry.name && openDrill(
                    `Employees — ${entry.name} proficiency`, rows.filter(r => r.skills.some(s => s.proficiencyLevel === entry.name)))}>
                  {data.proficiencyDistribution.map((_, i) => <Cell key={i} fill={PIE_PALETTE[i % PIE_PALETTE.length]} />)}
                </Pie>
                <Legend verticalAlign="bottom" height={28} formatter={(value) => <span style={{ color: 'var(--txt-mut)', fontSize: 12 }}>{value}</span>} />
                <Tooltip {...tooltipStyle} />
              </PieChart>
            </ResponsiveContainer>
          )}
        </ChartCard>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: 12 }} className="nf-grid-side-collapse">
        <ChartCard title="Skills by department" hint={skillsByDeptChart.length > 0 ? 'Click a bar to view employees' : undefined}>
          <div style={{ marginBottom: 10 }}>
            <select value={department} onChange={e => setDepartment(e.target.value)}
              style={{ background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 7, padding: '6px 9px', fontSize: 12, color: 'var(--txt)' }}>
              <option value="">All departments (top skills)</option>
              {departments.map(d => <option key={d} value={d}>{d}</option>)}
            </select>
          </div>
          {skillsByDeptChart.length === 0 ? (
            <div style={{ padding: '20px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No data for this department.</div>
          ) : (
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={skillsByDeptChart.slice(0, 8)} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                <XAxis dataKey="name" tick={{ fill: 'var(--txt-dim)', fontSize: 10 }} axisLine={false} tickLine={false} interval={0} angle={-20} textAnchor="end" height={40} />
                <YAxis tick={{ fill: 'var(--txt-dim)', fontSize: 10.5 }} axisLine={false} tickLine={false} allowDecimals={false} />
                <Tooltip {...tooltipStyle} cursor={{ fill: 'rgba(177,17,22,.08)' }} />
                <Bar dataKey="count" name="Employees" fill="#4C8DD6" radius={[4, 4, 0, 0]} cursor="pointer"
                  onClick={(entry: { name?: string }) => entry.name && openDrill(
                    department ? `${department} — "${entry.name}"` : `Employees with "${entry.name}"`,
                    rows.filter(r => (!department || r.departmentName === department) && r.skills.some(s => s.skillName === entry.name)))} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <ChartCard title="Certificates by category" hint={data.certificatesByCategory.length > 0 ? 'Click a slice to view employees' : undefined}>
          {data.certificatesByCategory.length === 0 ? (
            <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No certificates logged yet.</div>
          ) : (
            <ResponsiveContainer width="100%" height={240}>
              <PieChart>
                <Pie data={data.certificatesByCategory} cx="50%" cy="50%" innerRadius="50%" outerRadius="85%" dataKey="count" nameKey="name" strokeWidth={0}
                  cursor="pointer"
                  onClick={(entry: { name?: string }) => entry.name && openDrill(
                    `Certificates — ${entry.name}`, rows.filter(r => r.certificates.some(c => (c.categoryName ?? 'Uncategorized') === entry.name)))}>
                  {data.certificatesByCategory.map((_, i) => <Cell key={i} fill={PIE_PALETTE[i % PIE_PALETTE.length]} />)}
                </Pie>
                <Legend verticalAlign="bottom" height={28} formatter={(value) => <span style={{ color: 'var(--txt-mut)', fontSize: 12 }}>{value}</span>} />
                <Tooltip {...tooltipStyle} />
              </PieChart>
            </ResponsiveContainer>
          )}
        </ChartCard>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 12 }} className="nf-kpi-2x2-mobile">
        <KpiCard icon={<AlertTriangle size={14} />} label="Expiring in 30 days" value={data.certificateExpiryBreakdown.in30Days} danger={data.certificateExpiryBreakdown.in30Days > 0}
          onClick={() => openDrill('Certificates Expiring in 30 Days', rows.filter(r => r.certificates.some(c => expiresWithin(c.expiryDate, 30))))} />
        <KpiCard icon={<AlertTriangle size={14} />} label="Expiring in 60 days" value={data.certificateExpiryBreakdown.in60Days}
          onClick={() => openDrill('Certificates Expiring in 60 Days', rows.filter(r => r.certificates.some(c => expiresWithin(c.expiryDate, 60))))} />
        <KpiCard icon={<AlertTriangle size={14} />} label="Expiring in 90 days" value={data.certificateExpiryBreakdown.in90Days}
          onClick={() => openDrill('Certificates Expiring in 90 Days', rows.filter(r => r.certificates.some(c => expiresWithin(c.expiryDate, 90))))} />
      </div>

      <ChartCard title="Learning & development status" hint={data.learningStatusCounts.length > 0 ? 'Click a tile to view employees' : undefined}>
        {data.learningStatusCounts.length === 0 ? (
          <div style={{ padding: '20px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>
            <BookOpen size={20} style={{ color: 'var(--txt-dim)', marginBottom: 6 }} />
            <div>No active learning tracks logged org-wide.</div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
            {data.learningStatusCounts.map(s => (
              <button key={s.name} onClick={() => openDrill(
                `Learning — ${s.name}`, rows.filter(r => r.learningEntries.some(l => learningStatusLabel(l.status) === s.name)))}
                style={{
                  flex: '1 1 140px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '10px 14px',
                  textAlign: 'left', cursor: 'pointer', font: 'inherit',
                }}>
                <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{s.count}</div>
                <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{s.name}</div>
              </button>
            ))}
          </div>
        )}
      </ChartCard>

      {drillTitle !== null && (
        <DetailModalShell title={selectedEmployee ? (detail?.employeeName ?? 'Loading…') : drillTitle} onClose={closeDrill}>
          {selectedEmployee ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <button onClick={backToList} style={{ alignSelf: 'flex-start', background: 'none', border: 'none', color: 'var(--brand-bright)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
                ← Back to list
              </button>
              {detailLoading || !detail ? <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div> : <EmployeeDetailView detail={detail} />}
            </div>
          ) : drillEmployees.length === 0 ? (
            <div style={{ color: 'var(--txt-dim)', fontSize: 13, fontStyle: 'italic' }}>No employees match.</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              {drillEmployees.map((r, i) => (
                <button key={r.employeeUserId} onClick={() => selectEmployee(r.employeeUserId)} style={{
                  display: 'flex', alignItems: 'center', gap: 14, width: '100%', textAlign: 'left',
                  padding: '10px 4px', background: 'none', border: 'none', cursor: 'pointer', font: 'inherit',
                  borderTop: i === 0 ? 'none' : '1px solid var(--line)',
                }}>
                  <div style={{ flex: '0 0 180px', minWidth: 0 }}>
                    <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.employeeName}</div>
                    <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeCode} · {r.departmentName ?? '—'}</div>
                  </div>
                  <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5, minWidth: 0 }}>
                    {r.skills.slice(0, 3).map(s => (
                      <Pill key={s.id} tone={LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)'}>{s.skillName}</Pill>
                    ))}
                    {r.skills.length > 3 && <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>+{r.skills.length - 3} more</span>}
                  </div>
                </button>
              ))}
            </div>
          )}
        </DetailModalShell>
      )}
    </div>
  );
}
