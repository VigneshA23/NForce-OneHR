import { useState } from 'react';
import { History, Shield } from 'lucide-react';
import { AuditLogView } from '../components/audit/AuditLogView';
import { AccessChangesView } from '../components/audit/AccessChangesView';

type View = 'access' | 'activity';

const VIEWS = [
  { key: 'access' as const, label: 'Access changes', icon: Shield, color: 'var(--risk)' },
  { key: 'activity' as const, label: 'My audit activity', icon: History, color: 'var(--brand)' },
];

/** Audit & Security: the Super Admin's org-wide access changes, plus their own audited activity. Only holders of
 *  audit_security:VIEW:ALL reach this page (see AuditRouter in App.tsx); the server enforces it again per request. */
export default function AuditSecurityPage() {
  const [view, setView] = useState<View>('access');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div role="tablist" aria-label="Audit views" className="nf-audit-pills">
        {VIEWS.map(v => (
          <button key={v.key} type="button" role="tab" aria-selected={view === v.key}
            className={`nf-audit-pill${view === v.key ? ' is-active' : ''}`} style={{ ['--pc' as string]: v.color }}
            onClick={() => setView(v.key)}>
            <v.icon size={15} />
            <span>{v.label}</span>
          </button>
        ))}
      </div>
      {view === 'access' ? (
        <AccessChangesView />
      ) : (
        <AuditLogView config={{
          title: 'Audit & Security',
          subtitle: 'HR-operational actions and access-control events',
          showAccessCategory: true,
          exportFilenamePrefix: 'audit-security',
        }} />
      )}
    </div>
  );
}
