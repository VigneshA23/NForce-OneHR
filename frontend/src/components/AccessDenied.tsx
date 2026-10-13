import { ShieldAlert } from 'lucide-react';

/**
 * Shown in place of a page whose route the current user's permissions don't satisfy — e.g. a
 * direct URL visit to an admin module their role doesn't grant. The backend's own @PreAuthorize
 * checks are the real enforcement (this never fires from a role that genuinely has access, and
 * never substitutes for the server rejecting the underlying API calls); this is purely so a
 * denied visit shows a clear, friendly message instead of a page that loads blank or spinners
 * forever waiting on 403s. Promoted from the one-off version WorkflowStudioPage used to carry.
 */
export function AccessDenied() {
  return (
    <div
      style={{
        background: 'var(--panel)',
        border: '1px solid var(--line2)',
        borderRadius: 12,
        padding: 40,
        textAlign: 'center',
      }}
    >
      <ShieldAlert size={28} style={{ color: 'var(--txt-dim)', marginBottom: 10 }} />
      <div style={{ fontSize: 15, color: 'var(--txt)', marginBottom: 6 }}>No access</div>
      <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
        You don't have permission to view this page. Contact your Super Admin if you believe this is a mistake.
      </div>
    </div>
  );
}
