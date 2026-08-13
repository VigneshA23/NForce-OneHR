import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Users, Clock, Calendar, TrendingUp, UserCheck, LogIn, LogOut, X } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { dashboardApi, type TeamJoiner, type ManagerDashboard } from '../api/dashboard';
import { attendanceApi, type AttendanceRecord, type TodayAttendance } from '../api/attendance';
import { webClockInApi, type WebClockInRecord } from '../api/webClockIn';
import { WebClockInRequestModal } from '../components/WebClockInRequestModal';
import { leaveApi } from '../api/leave';

function formatClockTime(iso: string | null): string | null {
  if (!iso) return null;
  const time = iso.slice(11, 16);
  if (time.length < 5) return null;
  const [h, m] = time.split(':').map(Number);
  const suffix = h < 12 ? 'AM' : 'PM';
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, '0')} ${suffix}`;
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

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
function buildJoinerData(joiners: TeamJoiner[]): { month: string; joiners: number }[] {
  const counts: Record<string, number> = {};
  const today = new Date();
  for (let i = 11; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth() - i, 1);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    counts[key] = 0;
  }
  for (const j of joiners) {
    const key = j.joinedTeamOn.slice(0, 7); // 'yyyy-MM'
    if (key in counts) counts[key] += 1;
  }
  return Object.entries(counts).map(([month, joiners]) => ({
    month: new Date(month + '-01').toLocaleDateString('en-US', { month: 'short', year: '2-digit' }),
    joiners,
  }));
}

function JoinersChart({ joiners }: { joiners: TeamJoiner[] }) {
  const [showModal, setShowModal] = useState(false);
  const data = useMemo(() => buildJoinerData(joiners), [joiners]);

  return (
    <>
      <div
        onClick={() => setShowModal(true)}
        role="button"
        tabIndex={0}
        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') setShowModal(true); }}
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px', cursor: 'pointer' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
            Team Joiners per Month
          </span>
          <span style={{ fontSize: 11, color: 'var(--txt-dim)', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 5, padding: '2px 8px' }}>
            Last 12 months
          </span>
        </div>
        <p style={{ margin: '0 0 16px', fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
          New team members joining per calendar month. Click for details.
        </p>
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
      </div>
      {showModal && <TeamJoinersModal joiners={joiners} onClose={() => setShowModal(false)} />}
    </>
  );
}

/* ── Team Joiners detail modal: enlarged chart + underlying employee list ── */
function TeamJoinersModal({ joiners, onClose }: { joiners: TeamJoiner[]; onClose: () => void }) {
  const data = useMemo(() => buildJoinerData(joiners), [joiners]);
  const sorted = useMemo(
    () => [...joiners].sort((a, b) => b.joinedTeamOn.localeCompare(a.joinedTeamOn)),
    [joiners]
  );

  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 880, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            Team Joiners — Last 12 Months
          </span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px' }}>
          <div style={{ padding: 20, borderRight: '1px solid var(--line)' }}>
            <ResponsiveContainer width="100%" height={320}>
              <BarChart data={data} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                <XAxis dataKey="month" tick={{ fill: 'var(--txt-dim)', fontSize: 11 }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fill: 'var(--txt-dim)', fontSize: 11 }} axisLine={false} tickLine={false} allowDecimals={false} />
                <Tooltip
                  contentStyle={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' }}
                  cursor={{ fill: 'rgba(177,17,22,.08)' }}
                />
                <Bar dataKey="joiners" name="New joiners" fill="#B11116" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 10, maxHeight: 460, overflowY: 'auto' }}>
            <span style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
              Employees ({sorted.length})
            </span>
            {sorted.length === 0 ? (
              <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '20px 0' }}>No one joined your team in the last 12 months.</div>
            ) : sorted.map((j, i) => (
              <div key={`${j.userId}-${j.joinedTeamOn}-${i}`} style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: '10px 12px' }}>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{j.fullName}</div>
                <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 1 }}>
                  {j.designationName ?? '—'}{j.departmentName ? ` · ${j.departmentName}` : ''}
                </div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', fontFamily: '"JetBrains Mono", monospace', marginTop: 2 }}>{j.employeeCode}</div>
                <div style={{ fontSize: 11.5, color: 'var(--brand)', marginTop: 6 }}>
                  Joined team: {new Date(j.joinedTeamOn).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * Working-remotely trigger for AttendanceStatusCard's own Check In/Check Out — driven by the
 * exact same shared `today: TodayAttendance` (not a separate WebClockInRecord fetch), so it
 * can never show a state that contradicts the Check In/Check Out button above it. Checkout is
 * handled solely by that shared button once Web Clock-In opens the day; this row never renders
 * its own checkout action.
 */
function WebClockInRow({ today, loading, onSubmitted }: {
  today: TodayAttendance | null;
  loading: boolean;
  onSubmitted: () => Promise<unknown>;
}) {
  const [showModal, setShowModal] = useState(false);
  const [legacy, setLegacy] = useState<WebClockInRecord | null>(null);
  const token = useAuthStore(s => s.token) ?? '';

  // Only for the legacy pre-self-approval PENDING/REJECTED messaging below — every new
  // submission is approved instantly (see WebClockInService.submit), so this is dead for any
  // web clock-in made after that change.
  useEffect(() => {
    webClockInApi.mine(token).then(list => {
      setLegacy(list.find(r => r.workDate === todayIsoDate() && r.status !== 'APPROVED') ?? null);
    }).catch(() => setLegacy(null));
  }, [token]);

  if (loading) return null;

  return (
    <div style={{ borderTop: '1px solid var(--line)', paddingTop: 10, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      {today?.canCheckIn && !legacy && (
        <button
          onClick={() => setShowModal(true)}
          style={{ fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          Working remotely? Web Clock In →
        </button>
      )}
      {legacy?.status === 'PENDING' && (
        <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Web clock-in pending approval.</span>
      )}
      {legacy?.status === 'REJECTED' && (
        <>
          <span style={{ fontSize: 12, color: 'var(--risk)' }}>Web clock-in rejected{legacy.reviewComment ? `: ${legacy.reviewComment}` : '.'}</span>
          <button
            onClick={() => setShowModal(true)}
            style={{ fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            Resubmit →
          </button>
        </>
      )}
      {showModal && (
        <WebClockInRequestModal onClose={() => setShowModal(false)} onSubmitted={() => { onSubmitted(); }} />
      )}
    </div>
  );
}

/* ── Live Attendance card (own check-in status) ──── */
function AttendanceStatusCard() {
  const token = useAuthStore(s => s.token) ?? '';
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [today, setToday] = useState<TodayAttendance | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = () => attendanceApi.today(token).then(setToday);

  useEffect(() => {
    setError(null);
    refresh()
      .catch((err) => { setToday(null); setError(err instanceof Error ? err.message : 'Failed to load attendance'); })
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  async function punch(kind: 'in' | 'out') {
    setSubmitting(true);
    try {
      const record = kind === 'in' ? await attendanceApi.checkIn(token) : await attendanceApi.checkOut(token);
      const refreshed = await attendanceApi.today(token);
      setToday(refreshed);
      // record.checkInAt is the day's *original* check-in (deliberately never updated on a
      // lunch-break resume, see AttendanceService.checkIn) — not what just happened on a
      // repeat check-in. checkOutAt is always the latest checkout, so it's fine as-is.
      const at = formatClockTime(kind === 'in' ? refreshed.serverNow : record.checkOutAt);
      showToast('success', `Checked ${kind} ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : `Check ${kind} failed`);
    } finally {
      setSubmitting(false);
    }
  }

  const record = today?.record ?? null;
  const checkedIn = formatClockTime(record?.checkInAt ?? null);
  const checkedOut = formatClockTime(record?.checkOutAt ?? null);

  const status = loading
    ? 'Loading…'
    : error
      ? error
      : checkedOut
        ? `Checked out at ${checkedOut}`
        : checkedIn
          ? `Checked in at ${checkedIn}`
          : 'Not checked in yet';

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
        <Clock size={14} style={{ color: 'var(--brand)' }} />
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>Attendance</span>
      </div>
      <p style={{ margin: 0, fontSize: 12, color: error ? 'var(--risk)' : 'var(--txt-mut)', lineHeight: 1.55 }}>{status}</p>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        {today?.canCheckIn && (
          <button
            onClick={() => punch('in')}
            disabled={submitting}
            style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: '#fff', background: 'var(--brand)', border: 'none', borderRadius: 6, padding: '6px 12px', cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
          >
            <LogIn size={13} /> {submitting ? 'Checking in…' : 'Check In'}
          </button>
        )}
        {today?.canCheckOut && (
          <button
            onClick={() => punch('out')}
            disabled={submitting}
            style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: '#fff', background: 'var(--brand)', border: 'none', borderRadius: 6, padding: '6px 12px', cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}
          >
            <LogOut size={13} /> {submitting ? 'Checking out…' : 'Check Out'}
          </button>
        )}
        <button
          onClick={() => navigate('/attendance')}
          style={{ fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          View attendance →
        </button>
      </div>
      <WebClockInRow today={today} loading={loading} onSubmitted={refresh} />
    </div>
  );
}

/* ── Live team attendance summary (manager view) ─── */
function useTeamAttendanceToday(token: string) {
  const [records, setRecords] = useState<AttendanceRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    attendanceApi.team(todayIsoDate(), token)
      .then(setRecords)
      .catch(() => setRecords([]))
      .finally(() => setLoading(false));
  }, [token]);

  return { records, loading };
}

/* ── Manager dashboard ───────────────────────────── */
function ManagerDashboardView() {
  const token = useAuthStore(s => s.token) ?? '';
  const user  = useAuthStore(s => s.user);
  const navigate = useNavigate();
  const [data, setData]   = useState<ManagerDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError]   = useState('');
  const { records: teamToday, loading: teamLoading } = useTeamAttendanceToday(token);
  const [onLeaveCount, setOnLeaveCount] = useState<number | null>(null);
  const [pendingLeaveCount, setPendingLeaveCount] = useState<number | null>(null);

  useEffect(() => {
    dashboardApi.managerDashboard(token)
      .then(setData)
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load team data'))
      .finally(() => setLoading(false));
  }, [token]);

  useEffect(() => {
    const today = todayIsoDate();
    leaveApi.team(today, today, token)
      .then(rows => setOnLeaveCount(new Set(rows.map(r => r.employeeUserId)).size))
      .catch(() => setOnLeaveCount(0));
  }, [token]);

  useEffect(() => {
    leaveApi.listApprovals(token)
      .then(rows => setPendingLeaveCount(rows.length))
      .catch(() => setPendingLeaveCount(0));
  }, [token]);

  const firstName = user?.firstName ?? user?.email?.split('@')[0] ?? 'Manager';
  const presentCount = teamToday.filter(r => r.checkInAt).length;
  const lateCount = teamToday.filter(r => r.status === 'LATE').length;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          Welcome back, {firstName}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>Manager Dashboard</p>
      </div>

      {/* Manager's own check-in/out — a Manager is an employee too */}
      <AttendanceStatusCard />

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

        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Clock size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Present Today</span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>
            {teamLoading ? '—' : `${presentCount}/${teamToday.length}`}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>checked in so far today</div>
        </div>

        <button
          onClick={() => navigate('/my-team?status=LEAVE')}
          style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', textAlign: 'left', cursor: 'pointer', font: 'inherit', width: '100%' }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Calendar size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>On Leave</span>
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1 }}>
            {onLeaveCount === null ? '—' : onLeaveCount}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>on leave today · view roster →</div>
        </button>
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
        <JoinersChart joiners={data?.teamJoiners ?? []} />
      </div>

      {/* Honest pending cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 10 }}>
        <div style={{
          background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
          padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 8,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
            <Clock size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>Attendance Summary</span>
          </div>
          <p style={{ margin: 0, fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
            {teamLoading
              ? 'Loading team check-in status…'
              : `${presentCount} of ${teamToday.length} checked in today${lateCount ? `, ${lateCount} late` : ''}.`}
          </p>
          <button
            onClick={() => navigate('/attendance')}
            style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            View team attendance →
          </button>
        </div>
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
            <Calendar size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>Pending Leave Requests</span>
          </div>
          <p style={{ margin: 0, fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
            {pendingLeaveCount === null
              ? 'Loading…'
              : pendingLeaveCount === 0
                ? 'No leave requests waiting on your approval.'
                : `${pendingLeaveCount} leave request${pendingLeaveCount === 1 ? '' : 's'} waiting on your approval.`}
          </p>
          <button
            onClick={() => navigate('/approvals?type=LEAVE')}
            style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            Review in Approval Center →
          </button>
        </div>
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
        {/* Super Admin has no punch clock of their own — see AttendanceController */}
        {role !== 'SUPER_ADMIN' && <AttendanceStatusCard />}
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
