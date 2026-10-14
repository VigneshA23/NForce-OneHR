import { useState } from 'react';
import { LayoutDashboard, Search, Users, Settings } from 'lucide-react';
import { DashboardTab } from './DashboardTab';
import { SearchTab } from './SearchTab';
import { DirectoryTab } from './DirectoryTab';
import { SettingsTab } from './SettingsTab';

type Tab = 'dashboard' | 'search' | 'directory' | 'settings';

const TABS: { key: Tab; label: string; icon: typeof LayoutDashboard }[] = [
  { key: 'dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { key: 'search', label: 'Talent Search', icon: Search },
  { key: 'directory', label: 'Employee Directory', icon: Users },
  { key: 'settings', label: 'Settings', icon: Settings },
];

/**
 * Organization > Performance (HR Admin/Super Admin only — enforced server-side by every endpoint
 * this page and its tabs call, same `hasAnyRole('HR_ADMIN','SUPER_ADMIN')` check used throughout;
 * this page itself has no client-side role gate, matching how /organization, /access, etc.
 * already work in this app). Four views over the Talent & Skills data built in earlier phases:
 * Dashboard (KPIs/charts), Talent Search ("who knows Java?"), Employee Directory (drill-down),
 * and Settings (master-list configuration, skill requests, migration review).
 */
export default function TalentPerformancePage() {
  const [tab, setTab] = useState<Tab>('dashboard');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <h1 style={{ fontSize: 22, fontWeight: 800, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Performance</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: '4px 0 0' }}>
          Org-wide skills, certificates, and learning - dashboard, search, and directory.
        </p>
      </div>

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
      {tab === 'settings' && <SettingsTab />}
    </div>
  );
}
