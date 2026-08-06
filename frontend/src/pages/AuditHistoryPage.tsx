import { AuditLogView } from '../components/audit/AuditLogView';

export default function AuditHistoryPage() {
  return (
    <AuditLogView config={{
      title: 'Audit History',
      subtitle: 'HR-operational actions across the organization',
      showAccessCategory: false,
      exportFilenamePrefix: 'audit-history',
    }} />
  );
}
