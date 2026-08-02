import { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { AlertTriangle, X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { myCompliance } from '../api/documents';

export function ComplianceBanner() {
  const token = useAuthStore(s => s.token);
  const user = useAuthStore(s => s.user);
  const role = user?.role ?? '';
  const navigate = useNavigate();
  const location = useLocation();
  const [dismissed, setDismissed] = useState(false);
  const [summary, setSummary] = useState<{ missing: number; rejected: number; pendingPolicies: number } | null>(null);

  const isHR = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const onDocPage = location.pathname === '/documents' || location.pathname === '/policies';

  useEffect(() => {
    if (!token || isHR || onDocPage) return;
    myCompliance(token).then(s => setSummary({ missing: s.missing, rejected: s.rejected, pendingPolicies: s.pendingPolicies })).catch(() => {});
  }, [token, isHR, onDocPage]);

  if (!summary || dismissed || onDocPage || isHR) return null;

  const total = summary.missing + summary.rejected + summary.pendingPolicies;
  if (total === 0) return null;

  const parts: string[] = [];
  if (summary.missing > 0) parts.push(`${summary.missing} document${summary.missing > 1 ? 's' : ''} missing`);
  if (summary.rejected > 0) parts.push(`${summary.rejected} rejected`);
  if (summary.pendingPolicies > 0) parts.push(`${summary.pendingPolicies} polic${summary.pendingPolicies > 1 ? 'ies' : 'y'} to acknowledge`);

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      background: 'rgba(234,179,8,0.12)', border: '1px solid rgba(234,179,8,0.35)',
      borderRadius: 8, padding: '8px 14px', marginBottom: 18,
      fontSize: 13, color: 'var(--txt)',
    }}>
      <AlertTriangle size={15} color="#eab308" style={{ flexShrink: 0 }} />
      <span style={{ flex: 1 }}>
        <strong style={{ color: '#eab308' }}>Action needed:</strong>{' '}
        {parts.join(', ')}.{' '}
        <button
          onClick={() => navigate('/documents')}
          style={{ background: 'none', border: 'none', color: '#eab308', cursor: 'pointer', textDecoration: 'underline', fontSize: 13, padding: 0 }}
        >
          Go to Documents & Policies
        </button>
      </span>
      <button
        onClick={() => setDismissed(true)}
        style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 2, display: 'flex' }}
        title="Dismiss"
      >
        <X size={14} />
      </button>
    </div>
  );
}
