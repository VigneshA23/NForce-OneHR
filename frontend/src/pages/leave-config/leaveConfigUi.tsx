import { useState } from 'react';
import { X } from 'lucide-react';
import type { CreditFrequency } from '../../api/leave';

/** Shared look of the Leave configuration areas (Organization Masters > Leave). */

export const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export const CURRENT_YEAR = new Date().getFullYear();

export const inputS: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px',
  fontSize: 13, color: 'var(--txt)', width: '100%', boxSizing: 'border-box',
};
export const labelS: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };
export const thS: React.CSSProperties = {
  padding: '10px 16px', textAlign: 'left', fontWeight: 600, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)',
  whiteSpace: 'nowrap', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase', background: 'var(--raised)',
};
export const tdS: React.CSSProperties = { padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 12.5, borderBottom: '1px solid var(--line)' };
export const hintS: React.CSSProperties = { margin: '0 0 14px', fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55 };
export const primaryBtn = (disabled = false): React.CSSProperties => ({
  padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600,
  color: '#fff', cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.6 : 1, whiteSpace: 'nowrap',
});
export const secondaryBtn: React.CSSProperties = {
  padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer', whiteSpace: 'nowrap',
};

export function days(n: number | null | undefined): string {
  if (n == null) return '–';
  return `${Number(n).toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '–';
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00` : iso);
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function errMsg(e: unknown) {
  return e instanceof Error ? e.message : 'Something went wrong';
}

export function frequencyLabel(f: CreditFrequency | null | undefined): string {
  return f === 'MONTHLY' ? 'Monthly' : f === 'QUARTERLY' ? 'Quarterly' : f === 'HALF_YEARLY' ? 'Half-yearly' : 'All at once';
}

export function Badge({ tone, children }: { tone: 'ok' | 'warn' | 'dim' | 'info'; children: React.ReactNode }) {
  const colors = {
    ok: ['rgba(47,182,124,.15)', 'var(--ok)'],
    warn: ['rgba(228,155,15,.15)', 'var(--warn, #b7791f)'],
    dim: ['rgba(107,114,128,.15)', 'var(--txt-dim)'],
    info: ['rgba(79,110,247,.14)', 'var(--brand)'],
  }[tone];
  return (
    <span style={{ display: 'inline-flex', padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600, background: colors[0], color: colors[1], whiteSpace: 'nowrap' }}>
      {children}
    </span>
  );
}

/** Same overlay/card chrome as the other Organization Masters dialogs (orgSetupModals.css). */
export function Dialog({ title, kind = 'edit', width = 460, onClose, children }: {
  title: string; kind?: 'add' | 'edit' | 'warn'; width?: number; onClose(): void; children: React.ReactNode;
}) {
  return (
    <div className="nf-om-dlg" role="dialog" aria-modal="true" aria-label={title}
      style={{ position: 'fixed', inset: 0, zIndex: 200, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="nf-om-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width, maxWidth: '94vw', maxHeight: '92vh', overflowY: 'auto', position: 'relative' }}>
        <h2 data-kind={kind}>{title}</h2>
        <button onClick={onClose} aria-label="Close" className="nf-om-x"><X size={16} /></button>
        {children}
      </div>
    </div>
  );
}

export function FormError({ error }: { error: string }) {
  return error ? <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginBottom: 12 }}>{error}</div> : null;
}

export function DialogActions({ busy, label, onCancel, danger }: { busy: boolean; label: string; onCancel(): void; danger?: boolean }) {
  return (
    <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
      <button type="button" onClick={onCancel} disabled={busy} style={secondaryBtn}>Cancel</button>
      <button type="submit" disabled={busy} style={{ ...primaryBtn(busy), background: danger ? 'var(--risk)' : 'var(--brand)' }}>
        {busy ? 'Please wait…' : label}
      </button>
    </div>
  );
}

export function Table({ head, children, empty }: { head: string[]; children: React.ReactNode; empty?: string | false }) {
  return (
    <div style={{ overflowX: 'auto', border: '1px solid var(--line)', borderRadius: 8 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
        <thead><tr>{head.map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {children}
          {empty && <tr><td colSpan={head.length} style={{ ...tdS, textAlign: 'center', padding: '28px 16px', color: 'var(--txt-dim)' }}>{empty}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/** Confirm-or-explain dialog, the Document Types deletion pattern. */
export function ConfirmDialog({ title, body, confirmLabel, danger, onConfirm, onClose }: {
  title: string; body: string; confirmLabel?: string; danger?: boolean; onConfirm?: () => Promise<void>; onClose(): void;
}) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!onConfirm) { onClose(); return; }
    setBusy(true);
    try { await onConfirm(); onClose(); } catch (err) { setError(errMsg(err)); setBusy(false); }
  }
  return (
    <Dialog title={title} kind={danger ? 'warn' : 'edit'} width={420} onClose={onClose}>
      <form onSubmit={submit}>
        <p style={{ margin: '0 0 20px', fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.55 }}>{body}</p>
        <FormError error={error} />
        <DialogActions busy={busy} label={confirmLabel ?? 'Got it'} danger={danger} onCancel={onClose} />
      </form>
    </Dialog>
  );
}

export type Confirm = { title: string; body: string; confirmLabel?: string; danger?: boolean; onConfirm?: () => Promise<void> };

