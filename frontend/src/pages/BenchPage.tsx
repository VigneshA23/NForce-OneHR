import { useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import { UsersRound } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { hasPermission } from '../lib/permissions';
import { TabBar, type TabDef } from '../components/TabBar';
import OverviewSection from './bench/OverviewSection';
import BenchListSection from './bench/BenchListSection';
import MarkBenchSection from './bench/MarkBenchSection';
import SettingsSection from './bench/SettingsSection';
import TeamBenchSection from './bench/TeamBenchSection';

type BenchTab = 'overview' | 'list' | 'mark' | 'team' | 'settings';

const HR_TABS: TabDef<BenchTab>[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'list', label: 'Bench list' },
  { key: 'mark', label: 'Mark on bench' },
  { key: 'team', label: 'My team' },
  { key: 'settings', label: 'Settings' },
];

/**
 * Bench Management. HR/Super Admin get the full workspace (dashboard, filterable list with export,
 * mark/end, settings); a Manager only ever gets the read-only list of their own benched reports.
 */
export default function BenchPage() {
  const token = useAuthStore(s => s.token) ?? '';
  // bench:EDIT:ALL (V241) — mark/end/settings; seeded to HR Admin/Super Admin, the old gate.
  const canManage = hasPermission(useAuthStore(s => s.permissions), 'bench', 'EDIT', 'ALL');
  const [tab, setTab] = useState<BenchTab>('overview');
  const [flash, setFlash] = useState('');

  return (
    <div>
      <PageTitleBlock card icon={UsersRound} style={{ marginBottom: 14 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Bench Management</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          {canManage
            ? 'See who is benched and for how long, find people by skill, and move them back to active work.'
            : 'See which of your direct reports are currently on bench.'}
        </p>
      </PageTitleBlock>

      {canManage ? (
        <>
          <TabBar tabs={HR_TABS} active={tab} onChange={t => { setTab(t); setFlash(''); }} ariaLabel="Bench sections" style={{ marginBottom: 16 }} />
          {flash && (
            <div role="status" style={{ background: 'rgba(47,182,124,.12)', border: '1px solid rgba(47,182,124,.3)', borderRadius: 6, padding: '8px 10px', marginBottom: 12, color: 'var(--ok)', fontSize: 12.5 }}>
              {flash}
            </div>
          )}
          {tab === 'overview' && <OverviewSection token={token} />}
          {tab === 'list' && <BenchListSection token={token} />}
          {tab === 'mark' && <MarkBenchSection token={token} onMarked={name => { setFlash(`${name} is now on bench.`); setTab('list'); }} />}
          {tab === 'team' && <TeamBenchSection token={token} />}
          {tab === 'settings' && <SettingsSection token={token} />}
        </>
      ) : (
        <TeamBenchSection token={token} />
      )}
    </div>
  );
}
