import { AuditLogView } from '../components/audit/AuditLogView';

export default function AuditSecurityPage() {
  return (
    <AuditLogView config={{
      title: 'Audit & Security',
      subtitle: 'HR-operational actions and access-control events',
      showAccessCategory: true,
      exportFilenamePrefix: 'audit-security',
    }} />
  );
}
