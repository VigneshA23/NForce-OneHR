/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW - NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  The summary tiles of each module page, rendered by that page's own tile component (the same ones the
 *  live pages use) with invented numbers; no login, session or API.
 *
 *    /tiles-ui-preview?page=performance|access|leave|requests|my-requests|assets|policies|documents
 *  Assets shows the Employee, Manager and HR Admin rows. The "Theme" swatches use the app's real
 *  accent-color setting.
 * ============================================================================
 */
import { useSearchParams } from 'react-router-dom';
import {
  Users, Sparkles, Award, AlertTriangle, Wallet, CheckCircle2, Hourglass, CalendarClock,
  Inbox, CircleDot, Clock, Lock,
} from 'lucide-react';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';
import { KpiCard } from '../talent/DashboardTab';
import { KpiTile } from '../LeavePage';
import { AccessStatTiles } from '../UserManagementPage';
import { Kpi } from '../HelpDeskAdminPage';
import { StatRing } from '../MyRequestsPage';
import { Tile } from '../assets/shared';
import { PolicyStatTiles } from '../PoliciesPage';
import { DocumentStatTiles } from '../DocumentsPage';

const PAGES: { key: string; label: string; title: string; subtitle: string }[] = [
  { key: 'performance', label: 'Performance', title: 'Performance', subtitle: 'Org-wide skills, certificates, and learning - dashboard, search, and directory.' },
  { key: 'access', label: 'Access', title: 'User Management', subtitle: 'Create and manage user accounts and roles.' },
  { key: 'leave', label: 'Leave', title: 'Leave & Holidays', subtitle: 'Your leave balances and requests.' },
  { key: 'requests', label: 'Requests (HR)', title: 'HR Service Requests', subtitle: 'Help Desk tickets raised by employees across the organization.' },
  { key: 'my-requests', label: 'Requests (Employee)', title: 'My Requests', subtitle: 'Track your requests at a glance - click a stat to filter.' },
  { key: 'assets', label: 'Assets & Expenses', title: 'Assets & Expense Management', subtitle: 'Assigned assets, expense claims and asset requests.' },
  { key: 'policies', label: 'Policies', title: 'Policies & Announcements', subtitle: 'Publish policies and company announcements.' },
  { key: 'documents', label: 'My Documents', title: 'My Documents & Policies', subtitle: 'Manage your required documents and acknowledge company policies.' },
];

const grid = (min: number): React.CSSProperties => ({ display: 'grid', gridTemplateColumns: `repeat(auto-fit, minmax(${min}px, 1fr))`, gap: 14 });
const rowTitle: React.CSSProperties = { fontSize: 11, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--txt-dim)', margin: '6px 0 -4px' };
const noop = () => {};

function Tiles({ page }: { page: string }) {
  switch (page) {
    case 'performance':
      return (
        <>
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
            <KpiCard icon={<Users size={20} />} label="Total Employees" value={262} accent="var(--info)" onClick={noop} />
            <KpiCard icon={<Sparkles size={20} />} label="With Skills Logged" value={214} note="of 262" accent="var(--ok)" onClick={noop} />
            <KpiCard icon={<Award size={20} />} label="With Certificates" value={131} note="of 262" accent="var(--om-purple)" onClick={noop} />
            <KpiCard icon={<AlertTriangle size={20} />} label="Certs Expiring (30d)" value={9} danger accent="var(--warn)" onClick={noop} />
          </div>
          <div style={rowTitle}>Certificate expiry</div>
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 12 }}>
            <KpiCard icon={<AlertTriangle size={20} />} label="Expiring in 30 days" value={9} danger onClick={noop} />
            <KpiCard icon={<AlertTriangle size={20} />} label="Expiring in 60 days" value={14} accent="var(--warn)" onClick={noop} />
            <KpiCard icon={<AlertTriangle size={20} />} label="Expiring in 90 days" value={22} accent="var(--info)" onClick={noop} />
          </div>
        </>
      );
    case 'access':
      return <AccessStatTiles total={262} active={249} inactive={13} roleSummary="231 Employee · 24 Manager · 5 HR Admin · 2 Super Admin" />;
    case 'leave':
      return (
        <div className="nf-kpi-2x2-mobile" style={grid(180)}>
          <KpiTile icon={Wallet} label="Available Leave" value="14d" sub="Across all leave types" accent="var(--ok)" />
          <KpiTile icon={CheckCircle2} label="Used This Year" value="6d" sub="Approved & consumed" accent="var(--info)" />
          <KpiTile icon={Hourglass} label="Pending Requests" value="1" sub="Awaiting a decision" accent="var(--warn)" />
          <KpiTile icon={CalendarClock} label="Upcoming Leave" value="Oct 28" sub="Casual Leave" accent="var(--brand)" />
        </div>
      );
    case 'requests':
      return (
        <div style={grid(170)}>
          <Kpi icon={<Inbox size={20} />} tone="tone-brand" label="Active Queue" value={18} danger />
          <Kpi icon={<CircleDot size={20} />} tone="tone-warn" label="Open" value={11} />
          <Kpi icon={<Clock size={20} />} tone="tone-indigo" label="In Progress" value={7} />
          <Kpi icon={<CheckCircle2 size={20} />} tone="tone-ok" label="Resolved" value={64} />
          <Kpi icon={<Lock size={20} />} tone="tone-mute" label="Closed" value={120} />
        </div>
      );
    case 'my-requests':
      return (
        <div className="nf-rq-ring-row">
          <StatRing value={12} total={12} pct={100} label="Total Requests" caption="All time" tone="tone-indigo" active onClick={noop} />
          <StatRing value={3} total={12} pct={25} label="Pending" caption="Awaiting action" tone="tone-warn" active={false} onClick={noop} />
          <StatRing value={8} total={12} pct={67} label="Approved" caption="Granted" tone="tone-ok" active={false} onClick={noop} />
          <StatRing value={1} total={12} pct={8} label="Needs Action" caption="Requires your attention" tone="tone-risk" active={false} onClick={noop} />
        </div>
      );
    case 'assets':
      return (
        <>
          <div style={rowTitle}>Employee</div>
          <div style={grid(200)}>
            <Tile label="My Assigned Assets" value={3} sub="Laptop, monitor, phone" clickable onClick={noop} />
            <Tile label="Open Expense Claims" value={2} sub="Rs 8,400" clickable onClick={noop} />
            <Tile label="Approved This Month" value="Rs 12,000" clickable onClick={noop} />
            <Tile label="My Asset Requests" value={1} clickable onClick={noop} />
          </div>
          <div style={rowTitle}>Manager</div>
          <div style={grid(200)}>
            <Tile label="Team Assets Assigned" value={18} sub="6 team members" clickable onClick={noop} />
            <Tile label="Pending Expense Claims" value={4} sub="Rs 21,300" clickable onClick={noop} clickHint="Review in Approval Center →" />
            <Tile label="Overdue Returns" value={1} clickable onClick={noop} />
            <Tile label="Pending Asset Requests" value={2} clickable onClick={noop} clickHint="Review in Approval Center →" />
            <Tile label="Approved This Month" value="Rs 46,500" clickable onClick={noop} />
          </div>
          <div style={rowTitle}>HR Admin</div>
          <div style={grid(200)}>
            <Tile label="Assets Assigned" value={412} clickable onClick={noop} />
            <Tile label="Available Inventory" value={119} clickable onClick={noop} />
            <Tile label="Overdue Returns" value={7} clickable onClick={noop} />
            <Tile label="Pending Expense Clearance" value={9} sub="Rs 64,200" clickable onClick={noop} clickHint="Review in Approval Center →" />
            <Tile label="Pending Asset Fulfillment" value={5} clickable onClick={noop} />
          </div>
        </>
      );
    case 'policies':
      return <PolicyStatTiles activePolicies={14} pendingAck={37} announcements={9} />;
    case 'documents':
      return <DocumentStatTiles required={6} verified={3} pending={1} rejected={1} missing={1} />;
    default:
      return null;
  }
}

export default function TilesPreviewShell() {
  const [params, setParams] = useSearchParams();
  const { accent, setAccent } = useAccentColor();
  const active = PAGES.find((p) => p.key === params.get('page')) ?? PAGES[0];
  return (
    <div style={{ minHeight: '100vh', background: 'var(--shell)', padding: '24px clamp(16px, 4vw, 40px)', display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
        <div role="tablist" aria-label="Preview page" style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 4, padding: 4, borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)' }}>
          {PAGES.map((p) => {
            const on = p.key === active.key;
            return (
              <button key={p.key} role="tab" aria-selected={on} onClick={() => setParams({ page: p.key })}
                style={{ padding: '6px 12px', borderRadius: 7, border: 'none', cursor: 'pointer', fontSize: 12.5, fontWeight: 700, background: on ? 'var(--brand)' : 'transparent', color: on ? '#fff' : 'var(--txt-mut)' }}>
                {p.label}
              </button>
            );
          })}
        </div>
        <div role="radiogroup" aria-label="Theme color" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 12px', borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)', height: 40, boxSizing: 'border-box' }}>
          <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.08em', color: 'var(--txt-dim)' }}>THEME</span>
          {(Object.keys(ACCENT_SWATCHES) as AccentColor[]).map((key) => {
            const sw = ACCENT_SWATCHES[key];
            const on = accent === key;
            return (
              <button key={key} role="radio" aria-checked={on} aria-label={sw.label} title={sw.label} onClick={() => setAccent(key)}
                style={{ width: 16, height: 16, borderRadius: '50%', background: sw.brandBright, border: on ? '2px solid var(--txt)' : '2px solid transparent', cursor: 'pointer', padding: 0 }} />
            );
          })}
        </div>
        <div style={{ display: 'inline-flex', padding: '5px 10px', borderRadius: 6, background: 'rgba(99,102,241,.10)', border: '1px solid rgba(99,102,241,.25)', fontSize: 11.5, fontWeight: 600, color: '#8183f4' }}>
          🔍 UI PREVIEW · Mock Data (Not Real)
        </div>
      </div>
      <div>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: 'var(--txt)' }}>{active.title}</h1>
        <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--txt-dim)' }}>{active.subtitle}</p>
      </div>
      <Tiles page={active.key} />
    </div>
  );
}
