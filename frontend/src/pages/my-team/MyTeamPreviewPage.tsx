/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Unauthenticated design-review preview of the redesigned My Team page.
 *
 *  It renders the REAL redesigned building blocks (pages/my-team/MyTeamRedesign.tsx — hero,
 *  summary cards, calendar + hover details, member cards, quick actions) that MyTeamPage.tsx also
 *  renders, fed with realistic MOCK data generated below. Nothing here touches the network, the
 *  auth store, MyTeamPage.tsx's data hooks, or any authorization code — `?role=` only chooses
 *  which mock dataset/shortcuts to show; it grants nothing.
 *
 *    /my-team-preview?role=manager      (default)
 *    /my-team-preview?role=hr-admin
 *    /my-team-preview?role=super-admin
 *    /my-team-preview?role=employee
 *
 *  Optional: `&view=peers` opens the Project Team view for non-employee roles (employees always
 *  see it). Theme/accent switches on the page are local to this preview.
 * ============================================================================
 */
import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Mail, MapPin, Sparkles, Users, X } from 'lucide-react';
import type { Role } from '../../lib/nav.config';
import { ACCENT_SWATCHES } from '../../lib/accentColor';
import {
  MyTeamHero, TeamStatusCard, AttendanceSummaryCard, QuickActionsCard, TeamCalendarPanel,
  MemberGridSection, MemberGrid, MemberCard, FilterChip, Pill, prettyEmploymentType,
  buildDayContext, summarizeMonth, buildQuickActions, isoOf, daysIn,
  type MemberRow, type TeamMetrics, type LiveStatus, type AvatarRenderer,
} from './MyTeamRedesign';
import { EffortsTab, NegligenceTab, PenaltiesTab, AssignmentsTab, PerformanceTab, ReportsTab } from './MyTeamPreviewTabs';

/* ══ Role presets (labels only — mirrors, never defines, the real rules) ══ */
type PreviewRole = 'manager' | 'hr-admin' | 'super-admin' | 'employee';
const ROLES: { key: PreviewRole; label: string; role: Role; userName: string; tabs: string[]; teamSize: number }[] = [
  { key: 'manager', label: 'Manager', role: 'Manager', userName: 'Anil Kumar', teamSize: 8, tabs: ['Overview', 'Efforts / Punctuality', 'Negligence', 'Regularize & Cancel Penalties', 'Employee Assignments', 'Performance', 'Reports'] },
  { key: 'hr-admin', label: 'HR Admin', role: 'HR Admin', userName: 'Priya Sharma', teamSize: 18, tabs: ['Overview', 'Reports'] },
  { key: 'super-admin', label: 'Super Admin', role: 'Super Admin', userName: 'Meera Nair', teamSize: 24, tabs: ['Overview', 'Efforts / Punctuality', 'Negligence', 'Regularize & Cancel Penalties', 'Employee Assignments', 'Performance', 'Reports'] },
  { key: 'employee', label: 'Employee', role: 'Employee', userName: 'Rohith Reddy', teamSize: 7, tabs: [] },
];

/* ══ Mock people ══ */
interface MockPerson {
  userId: string; fullName: string; designation: string; department: string; employmentType: string;
  location: string; email: string; active: boolean; joiningDate: string | null; code: string;
}
const NAMES: [string, string, string][] = [
  ['Rohith Reddy', 'Frontend Developer', 'Engineering'], ['Sneha Priya', 'Backend Developer', 'Engineering'],
  ['Vikram Singh', 'QA Engineer', 'QA'], ['Ananya Das', 'UI/UX Designer', 'Design'],
  ['Karthik V', 'DevOps Engineer', 'DevOps'], ['Priya Sharma', 'HR Executive', 'HR'],
  ['Sumanth G', 'Product Manager', 'Product'], ['Rakesh Yadav', 'Full Stack Developer', 'Engineering'],
  ['Divya Menon', 'Business Analyst', 'Product'], ['Arjun Patel', 'Data Engineer', 'Engineering'],
  ['Neha Kapoor', 'Recruiter', 'HR'], ['Imran Shaikh', 'QA Engineer', 'QA'],
  ['Lakshmi Iyer', 'Visual Designer', 'Design'], ['Rahul Nair', 'Site Reliability Engineer', 'DevOps'],
  ['Pooja Verma', 'Payroll Specialist', 'Finance'], ['Siddharth Rao', 'Backend Developer', 'Engineering'],
  ['Meghana B', 'Frontend Developer', 'Engineering'], ['Tarun Joshi', 'Support Engineer', 'Support'],
  ['Aditi Kulkarni', 'Product Designer', 'Design'], ['Faisal Khan', 'Mobile Developer', 'Engineering'],
  ['Revathi S', 'Test Automation Engineer', 'QA'], ['Harsh Vardhan', 'Security Analyst', 'DevOps'],
  ['Nandini Pillai', 'Finance Associate', 'Finance'], ['Gautam Bose', 'Engineering Intern', 'Engineering'],
];
const EMPLOYMENT = ['FULL_TIME', 'FULL_TIME', 'FULL_TIME', 'CONTRACT', 'FULL_TIME', 'INTERN'];
const LOCATIONS = ['Hyderabad', 'Bengaluru', 'Pune', 'Chennai'];

function personAt(i: number, offset = 0): MockPerson {
  const [fullName, designation, department] = NAMES[(i + offset) % NAMES.length];
  return {
    userId: `mock-${offset}-${i}`, fullName, designation, department, code: `NF${String(1001 + i + offset).padStart(4, '0')}`,
    employmentType: EMPLOYMENT[(i + offset) % EMPLOYMENT.length], location: LOCATIONS[(i + offset) % LOCATIONS.length],
    email: `${fullName.toLowerCase().replace(/[^a-z]+/g, '.').replace(/\.$/, '')}@nforceone.example`,
    active: true,
    joiningDate: null,
  };
}

function seeded(seed: number) {
  let t = seed + 0x6d2b79f5;
  return () => {
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function buildPeople(count: number, offset: number, withEdgeCases: boolean): MockPerson[] {
  const list = Array.from({ length: count }, (_, i) => personAt(i, offset));
  if (withEdgeCases && list.length > 6) {
    list[list.length - 1] = { ...list[list.length - 1], active: false };                 // inactive employee
    const d = new Date(); d.setDate(d.getDate() - 9);
    list[list.length - 2] = { ...list[list.length - 2], joiningDate: isoOf(d.getFullYear(), d.getMonth(), d.getDate()) }; // recent joiner
  }
  return list;
}

/* ══ Mock attendance for one month ══ */
function buildMonth(people: MockPerson[], year: number, month: number, today: string) {
  const attendance: Parameters<typeof buildDayContext>[0]['attendance'] = [];
  const leaves: Parameters<typeof buildDayContext>[0]['leaves'] = [];
  const wfhKeys = new Set<string>();
  const total = daysIn(year, month);
  people.forEach((p, pi) => {
    const rnd = seeded(pi * 977 + year * 12 + month);
    // A leave block for roughly a third of people.
    if (rnd() < 0.35) {
      const start = 3 + Math.floor(rnd() * (total - 8));
      const len = 1 + Math.floor(rnd() * 3);
      leaves.push({ employeeUserId: p.userId, startDate: isoOf(year, month, start), endDate: isoOf(year, month, start + len - 1), leaveTypeName: rnd() < 0.5 ? 'Casual Leave' : 'Sick Leave', halfDay: false });
    }
    // Everyone is on leave today for a couple of people so the live strip has all four states.
    if (pi % 7 === 3 && today.startsWith(isoOf(year, month, 1).slice(0, 7))) {
      leaves.push({ employeeUserId: p.userId, startDate: today, endDate: today, leaveTypeName: 'Casual Leave', halfDay: false });
    }
    for (let d = 1; d <= total; d++) {
      const iso = isoOf(year, month, d);
      const dow = new Date(year, month, d).getDay();
      if (dow === 0 || dow === 6 || iso > today) continue;
      if (p.joiningDate && iso < p.joiningDate) continue;
      if (rnd() < 0.08) { wfhKeys.add(`${p.userId}:${iso}`); }
      const r = rnd();
      if (iso === today) {
        if (pi % 5 === 4) continue; // not in yet
        const out = pi % 4 === 0;
        attendance.push({ employeeUserId: p.userId, workDate: iso, checkInAt: `${iso}T09:${String(pi % 5 * 6 + 2).padStart(2, '0')}:00`, checkOutAt: out ? `${iso}T18:05:00` : null, workedMinutes: out ? 543 : null, status: pi % 6 === 1 ? 'LATE' : 'PRESENT', lateByMinutes: pi % 6 === 1 ? 14 : null, source: 'SYSTEM', penalized: false });
        continue;
      }
      if (r < 0.03) continue; // genuine "no record" day
      const status = r < 0.12 ? 'LATE' : r < 0.17 ? 'HALF_DAY' : r < 0.2 ? 'ABSENT' : r < 0.24 ? 'MISSING_CHECKOUT' : 'PRESENT';
      const missingOut = status === 'MISSING_CHECKOUT' || status === 'ABSENT';
      attendance.push({
        employeeUserId: p.userId, workDate: iso,
        checkInAt: status === 'ABSENT' ? null : `${iso}T${status === 'LATE' ? '10:' + String(12 + (pi % 4) * 7) : '09:0' + (pi % 9)}:00`,
        checkOutAt: missingOut ? null : `${iso}T${status === 'HALF_DAY' ? '13:30' : '18:10'}:00`,
        workedMinutes: missingOut ? null : status === 'HALF_DAY' ? 270 : 530,
        status, lateByMinutes: status === 'LATE' ? 12 + (pi % 4) * 7 : null,
        source: rnd() < 0.08 ? 'WEB_REMOTE' : rnd() < 0.05 ? 'REGULARIZATION' : 'SYSTEM',
        penalized: status === 'MISSING_CHECKOUT' && rnd() < 0.4,
      });
    }
  });
  // Two mock company holidays on weekdays.
  const holidays: { holidayDate: string; holidayName: string }[] = [];
  let found = 0;
  for (let d = 6; d <= total && found < 2; d += found === 0 ? 9 : 1) {
    const dow = new Date(year, month, d).getDay();
    if (dow !== 0 && dow !== 6) { holidays.push({ holidayDate: isoOf(year, month, d), holidayName: found === 0 ? 'Founders Day' : 'Festival Holiday' }); found++; }
  }
  return { attendance, leaves, wfhKeys, holidays };
}

function liveStatusOf(personId: string, today: string, att: ReturnType<typeof buildMonth>): LiveStatus {
  if (att.leaves.some(l => l.employeeUserId === personId && today >= l.startDate && today <= l.endDate)) return 'LEAVE';
  const rec = att.attendance.find(r => r.employeeUserId === personId && r.workDate === today);
  return !rec?.checkInAt ? 'NOT_IN_YET' : !rec.checkOutAt ? 'IN' : 'OUT';
}

/* ══ Offline avatar (no photo fetch) ══ */
const renderMockAvatar: AvatarRenderer = (userId, name, size) => {
  const hue = [...userId].reduce((a, c) => a + c.charCodeAt(0), 0) % 360;
  const initials = name.split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase();
  return (
    <span aria-hidden style={{
      width: size, height: size, borderRadius: '50%', flexShrink: 0, display: 'grid', placeItems: 'center',
      fontSize: size * 0.34, fontWeight: 700, color: `hsl(${hue} 55% 32%)`, background: `hsl(${hue} 70% 88%)`,
    }}>{initials}</span>
  );
};

const STATUS_CHIPS: { key: 'all' | LiveStatus; label: string }[] = [
  { key: 'all', label: 'All' }, { key: 'IN', label: 'In' }, { key: 'OUT', label: 'Out' },
  { key: 'NOT_IN_YET', label: 'Not in yet' }, { key: 'LEAVE', label: 'On leave' },
];
// Same five swatches as the app's Theme Color setting (lib/accentColor.tsx), so the preview can
// show every accent the real page will follow.
const ACCENTS: Record<string, [string, string, string]> = Object.fromEntries(
  Object.values(ACCENT_SWATCHES).map(w => [w.label, [w.brand, w.brandBright, w.brandDeep]]),
);
const TEAM_CALENDAR_PAGE_SIZE = 15;

export default function MyTeamPreviewPage() {
  const [params, setParams] = useSearchParams();
  const cfg = ROLES.find(r => r.key === params.get('role')) ?? ROLES[0];
  const isEmployee = cfg.key === 'employee';
  const view: 'direct' | 'peers' = isEmployee || params.get('view') === 'peers' ? 'peers' : 'direct';

  const [theme, setTheme] = useState<'light' | 'dark'>('light');
  const [accent, setAccent] = useState('Purple');
  // Theme/accent are applied on <html> (where the real app applies them) so the portal tooltip
  // inherits them too; the previous values are restored when leaving the preview.
  useEffect(() => {
    const html = document.documentElement;
    const prev = { theme: html.getAttribute('data-theme'), brand: html.style.getPropertyValue('--brand'), bright: html.style.getPropertyValue('--brand-bright'), deep: html.style.getPropertyValue('--brand-deep') };
    const apply = () => {
      html.setAttribute('data-theme', theme);
      const [b, bb, bd] = ACCENTS[accent];
      html.style.setProperty('--brand', b); html.style.setProperty('--brand-bright', bb); html.style.setProperty('--brand-deep', bd);
    };
    apply();
    // AccentColorProvider re-applies the stored accent in a parent effect right after mount,
    // which would otherwise overwrite the first apply() — run once more on the next frame.
    const raf = requestAnimationFrame(apply);
    return () => {
      cancelAnimationFrame(raf);
      if (prev.theme) html.setAttribute('data-theme', prev.theme); else html.removeAttribute('data-theme');
      html.style.setProperty('--brand', prev.brand); html.style.setProperty('--brand-bright', prev.bright); html.style.setProperty('--brand-deep', prev.deep);
    };
  }, [theme, accent]);

  // Mock "today" is pinned to a weekday around the 20th of the current month, so the calendar
  // always has a populated past and a visible future regardless of the real date.
  const today = useMemo(() => {
    const t = new Date();
    let d = Math.min(20, daysIn(t.getFullYear(), t.getMonth()));
    while ([0, 6].includes(new Date(t.getFullYear(), t.getMonth(), d).getDay())) d--;
    return isoOf(t.getFullYear(), t.getMonth(), d);
  }, []);
  const [viewDate, setViewDate] = useState(() => { const t = new Date(); return new Date(t.getFullYear(), t.getMonth(), 1); });
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | LiveStatus>('all');
  const [profile, setProfile] = useState<MockPerson | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [tab, setTab] = useState('Overview');
  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(() => setToast(null), 2200);
    return () => window.clearTimeout(t);
  }, [toast]);

  const people = useMemo(
    () => (view === 'direct' ? buildPeople(cfg.teamSize, 0, cfg.teamSize > 8) : buildPeople(isEmployee ? cfg.teamSize : 6, 9, false)),
    [view, cfg.teamSize, isEmployee],
  );
  const month = useMemo(() => buildMonth(people, viewDate.getFullYear(), viewDate.getMonth(), today), [people, viewDate, today]);
  // Today's live status always comes from the current month's data, whichever month is displayed.
  const currentMonth = useMemo(() => {
    const t = new Date();
    return buildMonth(people, t.getFullYear(), t.getMonth(), today);
  }, [people, today]);

  const members: MemberRow[] = useMemo(() => people.map(p => ({
    userId: p.userId, fullName: p.fullName, designation: p.designation, department: p.department,
    active: p.active, joiningDate: p.joiningDate, status: liveStatusOf(p.userId, today, currentMonth),
  })), [people, today, currentMonth]);
  const metrics: TeamMetrics = useMemo(() => ({
    total: members.length,
    checkedIn: members.filter(m => m.status === 'IN' || m.status === 'OUT').length,
    onLeave: members.filter(m => m.status === 'LEAVE').length,
    notIn: members.filter(m => m.status === 'NOT_IN_YET').length,
  }), [members]);
  const ctx = useMemo(() => buildDayContext({ today, holidays: month.holidays, leaves: month.leaves, wfhKeys: month.wfhKeys, attendance: month.attendance }), [today, month]);
  const summary = useMemo(() => summarizeMonth(members, viewDate, ctx), [members, viewDate, ctx]);
  // Real shortcuts navigate (or open the Reports tab); the preview has no app behind it, so each one
  // reports where it would go instead.
  const quickActions = useMemo(() => buildQuickActions(cfg.role, { reportsTab: cfg.tabs.includes('Reports') && view === 'direct', onOpenReports: () => undefined })
    .map(a => ({ ...a, to: undefined, onClick: () => (a.key === 'reports' && cfg.tabs.includes('Reports') && view === 'direct' ? setTab('Reports') : setToast(`${a.label} opens ${a.to ?? 'the Reports tab'} in the real app`)) })), [cfg, view]);

  const tabPeople = useMemo(() => people.map(p => ({ userId: p.userId, fullName: p.fullName, designation: p.designation, department: p.department })), [people]);

  const q = search.trim().toLowerCase();
  const filtered = people.filter(p => {
    const st = members.find(m => m.userId === p.userId)!.status;
    const okStatus = view === 'peers' || statusFilter === 'all' || st === statusFilter;
    const okSearch = !q || p.fullName.toLowerCase().includes(q) || p.code.toLowerCase().includes(q)
      || (view === 'peers' && (p.department.toLowerCase().includes(q) || p.designation.toLowerCase().includes(q)));
    return okStatus && okSearch;
  });

  function setParam(k: string, v: string | null) {
    const next = new URLSearchParams(params);
    if (v === null) next.delete(k); else next.set(k, v);
    setParams(next, { replace: true });
    setPage(0); setSearch(''); setStatusFilter('all'); setTab('Overview');
  }

  const barBtn = (active: boolean): React.CSSProperties => ({
    padding: '6px 12px', borderRadius: 7, fontSize: 12, fontWeight: 700, cursor: 'pointer', fontFamily: 'inherit',
    border: `1px solid ${active ? 'var(--brand)' : 'var(--line2)'}`, background: active ? 'color-mix(in srgb, var(--brand) 14%, transparent)' : 'var(--panel)',
    color: active ? 'var(--brand-bright)' : 'var(--txt-mut)',
  });

  return (
    <div style={{ minHeight: '100dvh', background: 'var(--shell)', color: 'var(--txt)', fontFamily: 'Inter, system-ui, sans-serif' }}>
      {/* Preview control bar — not part of the product UI */}
      <div style={{ position: 'sticky', top: 0, zIndex: 50, background: 'var(--panel)', borderBottom: '1px solid var(--line)', padding: '10px 20px', display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'center' }}>
        <span style={{ fontSize: 11, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--warn)', background: 'color-mix(in srgb, var(--warn) 14%, transparent)', padding: '4px 9px', borderRadius: 6 }}>Mock preview · no backend</span>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }} role="group" aria-label="Role">
          {ROLES.map(r => <button key={r.key} style={barBtn(cfg.key === r.key)} onClick={() => { const n = new URLSearchParams(); n.set('role', r.key); setParams(n, { replace: true }); setPage(0); setSearch(''); setStatusFilter('all'); setTab('Overview'); }}>{r.label}</button>)}
        </div>
        <div style={{ display: 'flex', gap: 6 }} role="group" aria-label="Theme">
          <button style={barBtn(theme === 'light')} onClick={() => setTheme('light')}>Light</button>
          <button style={barBtn(theme === 'dark')} onClick={() => setTheme('dark')}>Dark</button>
        </div>
        <div style={{ display: 'flex', gap: 6 }} role="group" aria-label="Accent">
          {Object.keys(ACCENTS).map(a => <button key={a} style={barBtn(accent === a)} onClick={() => setAccent(a)}>{a}</button>)}
        </div>
        <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>URL: <code>?role={cfg.key}</code></span>
      </div>

      <div style={{ padding: 20, maxWidth: 1760, margin: '0 auto' }}>
        {/* Direct Reports / Project Team switch — same condition as production (never for employees) */}
        {!isEmployee && (
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
            <div role="tablist" aria-label="My Team view" style={{ display: 'inline-flex', gap: 4, background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 9, padding: 4 }}>
              {([['direct', 'Direct Reports', <Users size={13} key="u" />], ['peers', 'Project Team', <Sparkles size={13} key="s" />]] as const).map(([k, label, icon]) => (
                <button key={k} role="tab" aria-selected={view === k} onClick={() => setParam('view', k === 'peers' ? 'peers' : null)} style={{
                  display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600, padding: '7px 14px', borderRadius: 6, border: 'none', cursor: 'pointer',
                  background: view === k ? 'var(--brand)' : 'transparent', color: view === k ? '#fff' : 'var(--txt-mut)',
                }}>{icon} {label}</button>
              ))}
            </div>
          </div>
        )}
        {view === 'direct' && cfg.tabs.length > 0 && (
          <div style={{ display: 'flex', gap: 6, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: 4, width: 'fit-content', maxWidth: '100%', marginBottom: 16, flexWrap: 'wrap' }}>
            {cfg.tabs.map(t => (
              <button key={t} onClick={() => setTab(t)} style={{ padding: '8px 14px', border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600, fontSize: 12.5, background: tab === t ? 'var(--brand)' : 'transparent', color: tab === t ? '#fff' : 'var(--txt-mut)', whiteSpace: 'nowrap' }}>{t}</button>
            ))}
          </div>
        )}

        {view === 'direct' && tab !== 'Overview' ? (
          <>
            <div style={{ marginBottom: 16 }}>
              <h1 style={{ fontSize: 22, fontWeight: 700, margin: '0 0 4px' }}>My Team</h1>
              <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: 0, maxWidth: '62ch' }}>Attendance, leave, and open requests for your direct reports, in one place, so you don't have to check four separate pages to know how your team is doing today.</p>
            </div>
            {tab === 'Efforts / Punctuality' && <EffortsTab people={tabPeople} avatar={renderMockAvatar} />}
            {tab === 'Negligence' && <NegligenceTab people={tabPeople} avatar={renderMockAvatar} />}
            {tab === 'Regularize & Cancel Penalties' && <PenaltiesTab people={tabPeople} avatar={renderMockAvatar} toast={setToast} />}
            {tab === 'Employee Assignments' && <AssignmentsTab people={tabPeople} avatar={renderMockAvatar} toast={setToast} />}
            {tab === 'Performance' && <PerformanceTab people={tabPeople} avatar={renderMockAvatar} />}
            {tab === 'Reports' && <ReportsTab toast={setToast} />}
          </>
        ) : (
        <div className="mt-root">
          <MyTeamHero
            userName={cfg.userName} metrics={metrics} memberLabel={view === 'direct' ? 'Total Team Members' : 'Project Team Members'}
            subtitle={view === 'direct' ? "Here's a quick overview of your team's attendance and activity today." : "Here's a quick overview of your project team's attendance and activity today."} />

          {view === 'peers' && (
            <section className="mt-card" style={{ padding: '14px 18px', display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              {renderMockAvatar('mgr', 'Sumanth G', 38)}
              <div style={{ flex: 1, minWidth: 160 }}>
                <div style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Your reporting manager</div>
                <div style={{ fontSize: 14, fontWeight: 700 }}>Sumanth G</div>
              </div>
              <button className="mt-link" onClick={() => setToast('Appreciate: preview only')}>Appreciate your lead</button>
            </section>
          )}

          <div className="mt-trio">
            <TeamStatusCard metrics={metrics} memberLabel={view === 'direct' ? 'team members' : 'project team members'} />
            <AttendanceSummaryCard summary={summary} monthLabel={viewDate.toLocaleDateString('en-US', { month: 'long', year: 'numeric' })} />
            <QuickActionsCard actions={quickActions} />
          </div>

          <TeamCalendarPanel
            subtitle={view === 'direct' ? 'Attendance status for your team' : 'Attendance status for your project team'}
            memberHeader={view === 'direct' ? 'Team Member' : 'Teammate'} members={members}
            viewDate={viewDate} onMonthChange={d => setViewDate(d)} ctx={ctx} renderAvatar={renderMockAvatar}
            page={page} onPageChange={setPage} pageSize={TEAM_CALENDAR_PAGE_SIZE} />

          <MemberGridSection
            title={view === 'direct' ? 'Team Members' : `Project Team (${filtered.length})`}
            subtitle={view === 'direct' ? 'Your direct reports and their details' : 'Avatar, designation, live status, location, department and email. Find and reach a teammate without leaving this page.'}
            search={search} onSearch={setSearch}
            searchPlaceholder={view === 'direct' ? 'Search by name or employee code…' : 'Search by name, designation, or department…'}
            chips={view === 'direct' ? STATUS_CHIPS.map(c => <FilterChip key={c.key} active={statusFilter === c.key} onClick={() => setStatusFilter(c.key)}>{c.label}</FilterChip>) : undefined}
          >
            <MemberGrid scroll={view === 'direct'} empty={filtered.length === 0 ? 'No one matches this filter.' : undefined}>
              {filtered.map(p => {
                const m = members.find(x => x.userId === p.userId)!;
                const emp = prettyEmploymentType(p.employmentType);
                const rec = ctx.records.get(`${p.userId}:${today}`);
                return (
                  <MemberCard
                    key={p.userId} avatar={renderMockAvatar(p.userId, p.fullName, 44)} name={p.fullName} designation={p.designation}
                    active={p.active} status={m.status}
                    tags={<>
                      <Pill>{p.department}</Pill>{emp && <Pill tone="ok">{emp}</Pill>}
                      {view === 'direct' && rec?.status === 'LATE' && <Pill tone="warn">Late</Pill>}
                      {m.status === 'LEAVE' && <Pill tone="leave">Casual Leave</Pill>}
                    </>}
                    extras={view === 'direct' ? (
                      <div style={{ color: 'var(--txt-dim)', fontSize: 10.5, marginTop: -4 }}>{p.code}</div>
                    ) : (<>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: 'var(--txt-mut)' }}><MapPin size={11} /> {p.location}</div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: 'var(--txt-mut)', overflow: 'hidden' }}><Mail size={11} style={{ flexShrink: 0 }} /><span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.email}</span></div>
                    </>)}
                    secondaryAction={p.active && <button className="mt-link" onClick={() => setToast(`Appreciate ${p.fullName}: preview only`)}>Appreciate</button>}
                    onViewProfile={view === 'direct' ? () => setProfile(p) : undefined}
                  />
                );
              })}
            </MemberGrid>
          </MemberGridSection>
        </div>
        )}
      </div>

      {profile && (
        <div role="dialog" aria-modal="true" aria-label="Employee profile (mock)" onClick={() => setProfile(null)} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'grid', placeItems: 'center', zIndex: 500, padding: 16 }}>
          <div onClick={e => e.stopPropagation()} className="mt-card" style={{ width: 'min(92vw, 400px)', padding: 20 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              {renderMockAvatar(profile.userId, profile.fullName, 48)}
              <div style={{ flex: 1 }}><div style={{ fontWeight: 700 }}>{profile.fullName}</div><div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{profile.designation}</div></div>
              <button className="mt-iconbtn" aria-label="Close" onClick={() => setProfile(null)}><X size={16} /></button>
            </div>
            <p style={{ fontSize: 12, color: 'var(--txt-dim)', margin: '14px 0 0' }}>Mock stand-in for the production employee detail modal that “View Profile” opens.</p>
          </div>
        </div>
      )}
      {toast && (
        <div role="status" style={{ position: 'fixed', bottom: 20, left: '50%', transform: 'translateX(-50%)', background: 'var(--txt)', color: 'var(--panel)', padding: '9px 16px', borderRadius: 9, fontSize: 12.5, fontWeight: 600, zIndex: 600, boxShadow: '0 8px 24px rgba(0,0,0,.3)' }}>
          {toast}
        </div>
      )}
    </div>
  );
}
