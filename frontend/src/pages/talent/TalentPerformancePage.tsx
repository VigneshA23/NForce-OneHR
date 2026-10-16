import { useState } from 'react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { useSearchParams } from 'react-router-dom';
import { LayoutDashboard, Search, Users, Settings, TrendingUp } from 'lucide-react';
import { DashboardTab } from './DashboardTab';
import { SearchTab } from './SearchTab';
import { DirectoryTab } from './DirectoryTab';
import { SettingsTab, type SettingsSubTab } from './SettingsTab';

type Tab = 'dashboard' | 'search' | 'directory' | 'settings';

const TABS: { key: Tab; label: string; icon: typeof LayoutDashboard }[] = [
  { key: 'dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { key: 'search', label: 'Talent Search', icon: Search },
  { key: 'directory', label: 'Employee Directory', icon: Users },
  { key: 'settings', label: 'Settings', icon: Settings },
];

const VALID_TABS = new Set<string>(TABS.map(t => t.key));
const VALID_SETTINGS_SUB_TABS = new Set<string>(['skills', 'levels', 'categories', 'requests']);

/**
 * Organization > Performance (HR Admin/Super Admin only — enforced server-side by every endpoint
 * this page and its tabs call, same `hasAnyRole('HR_ADMIN','SUPER_ADMIN')` check used throughout;
 * this page itself has no client-side role gate, matching how /organization, /access, etc.
 * already work in this app). Four views over the Talent & Skills data built in earlier phases:
 * Dashboard (KPIs/charts), Talent Search ("who knows Java?"), Employee Directory (drill-down),
 * and Settings (master-list configuration, skill requests, migration review).
 */
export default function TalentPerformancePage() {
  // Deep-linked from a notification (e.g. a new Skill Request) via
  // /talent-performance?tab=settings&sub=requests — read once on mount, same as a normal initial
  // state; this page doesn't otherwise sync its tab state to the URL as the user clicks around.
  const [searchParams] = useSearchParams();
  const requestedTab = searchParams.get('tab');
  const requestedSub = searchParams.get('sub');
  const [tab, setTab] = useState<Tab>(
    requestedTab && VALID_TABS.has(requestedTab) ? (requestedTab as Tab) : 'dashboard'
  );
  const initialSettingsSubTab: SettingsSubTab | undefined =
    requestedSub && VALID_SETTINGS_SUB_TABS.has(requestedSub) ? (requestedSub as SettingsSubTab) : undefined;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <PageTitleBlock card style={{ marginBottom: 0 }} icon={TrendingUp}>
        <h1 style={{ fontSize: 22, fontWeight: 800, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Performance</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: '4px 0 0' }}>
          Org-wide skills, certificates, and learning - dashboard, search, and directory.
        </p>
      </PageTitleBlock>

      <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)' }}>
        {TABS.map(t => (
          <button key={t.key} onClick={() => setTab(t.key)} style={{
            display: 'flex', alignItems: 'center', gap: 6,
            background: 'none', border: 'none', cursor: 'pointer',
            padding: '10px 16px', fontSize: 13, fontWeight: tab === t.key ? 700 : 600,
            color: tab === t.key ? 'var(--brand-bright)' : 'var(--txt-mut)',
            borderBottom: tab === t.key ? '2px solid var(--brand-bright)' : '2px solid transparent',
            marginBottom: -1,
          }}>
            <t.icon size={14} aria-hidden="true" />
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'dashboard' && <DashboardTab />}
      {tab === 'search' && <SearchTab />}
      {tab === 'directory' && <DirectoryTab />}
      {tab === 'settings' && <SettingsTab initialSub={initialSettingsSubTab} />}
    </div>
  );
}
