import { useEffect, useState } from 'react';
import { X, Wallet, FileCheck2, Receipt, ClipboardList, PauseCircle, Undo2 } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { useAuthStore } from '../../store/authStore';
import { employeesApi, type EmployeeRecord } from '../../api/employees';
import './payroll.css';

/** Payslips/review items/ad-hoc entries carry only an employeeId — this resolves it to a name for
 * display and for the employee pickers on the ad-hoc payment/deduction/hold forms. One fetch per
 * page mount (the full org roster — this console is HR Admin/Super Admin only, who already have
 * org-wide employee visibility), not per row. */
export function useEmployeeDirectory() {
  const token = useAuthStore(s => s.token) ?? '';
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);

  useEffect(() => {
    if (!token) return;
    employeesApi.list(token).then(setEmployees).catch(() => {});
  }, [token]);

  const nameFor = (userId: string) => employees.find(e => e.userId === userId)?.fullName ?? userId;
  return { employees, nameFor };
}

// ── Shared styles (same shape as pages/assets/shared.tsx / pages/bench/ui.tsx) ──

export const panelStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', overflow: 'hidden' };
export const thStyle: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 800, color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.09em', borderBottom: '1px solid color-mix(in srgb, var(--brand) 22%, var(--line))', whiteSpace: 'nowrap', background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))' };
export const tdStyle: React.CSSProperties = { padding: '13px 16px', fontSize: 13.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
export const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
export const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
export const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
export const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };

export function fmtCurrency(n?: number | null) {
  if (n == null) return '–';
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n);
}

export function fmtDate(s?: string | null) {
  if (!s) return '–';
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function fmtDateTime(s?: string | null) {
  if (!s) return '–';
  return new Date(s).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

// Semantic status/decision → the app's theme tokens (--ok/--warn/--risk/--info), never a fixed hex,
// so badges follow the selected accent color and light/dark mode. Covers both PayrollRun statuses
// and the C22/C23 review decisions (same badge component for both — they never appear in the same
// column, so there's no ambiguity in sharing one map).
const STATUS_TONE: Record<string, string> = {
  DRAFT: 'var(--txt-dim)',
  SUBMITTED: 'var(--warn)',
  APPROVED: 'var(--ok)',
  DISBURSED: 'var(--ok)',
  PENDING: 'var(--warn)',
  REJECTED: 'var(--risk)',
  MARK_LOP: 'var(--ok)',
  DEDUCT_FROM_LEAVE: 'var(--info)',
  NO_ACTION: 'var(--info)',
  ACTIVE: 'var(--warn)',
  RELEASED: 'var(--ok)',
  CANCELLED: 'var(--txt-dim)',
  COMPLETED: 'var(--ok)',
  APPLIED: 'var(--ok)',
};

export function StatusBadge({ status }: { status: string }) {
  const tone = STATUS_TONE[status] ?? 'var(--txt-dim)';
  return (
    <span className="nf-asset-pill" style={{
      background: `color-mix(in srgb, ${tone} 13%, transparent)`, color: tone,
      border: `1px solid color-mix(in srgb, ${tone} 30%, transparent)`,
    }}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}

export function SectionHead({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
      <h2 style={{ margin: 0, fontSize: 18, fontWeight: 800, letterSpacing: '-.01em', fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{title}</h2>
      {action}
    </div>
  );
}

export function EmptyRow({ cols, msg }: { cols: number; msg: string }) {
  return (
    <tr>
      <td colSpan={cols} style={{ padding: '36px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>{msg}</td>
    </tr>
  );
}

/** KPI tile: label and icon on top, the number at the bottom, a coloured edge on the left. */
export function StatTile({ label, value, hint, icon, tone }: { label: string; value: string; hint?: string; icon: ReactNode; tone: string }) {
  return (
    <div className="nf-pr-tile" style={{ ['--mk' as string]: tone }}>
      <div className="nf-pr-tile-top">
        <span className="nf-pr-tile-label">{label}</span>
        <span className="nf-pr-tile-ico">{icon}</span>
      </div>
      <div className="nf-pr-tile-val">{value}</div>
      {hint && <div className="nf-pr-tile-hint">{hint}</div>}
    </div>
  );
}

// ── Modal wrapper ─────────────────────────────────────────

function modalIconFor(title: string): LucideIcon {
  const t = title.toLowerCase();
  if (t.includes('rollback')) return Undo2;
  if (t.includes('hold')) return PauseCircle;
  if (t.includes('deduction')) return ClipboardList;
  if (t.includes('payment')) return Receipt;
  if (t.includes('override') || t.includes('lop')) return FileCheck2;
  return Wallet;
}

export function Modal({ title, subtitle, onClose, children }: { title: string; subtitle?: string | null; onClose: () => void; children: ReactNode }) {
  const Icon = modalIconFor(title);
  return (
    <div style={overlayStyle} className="nf-modal-overlay-in nf-pr-dlg">
      <div style={modalStyle} className="nf-modal-panel-in nf-pr-card">
        <div className="nf-pr-head">
          <span className="nf-pr-ico"><Icon size={20} /></span>
          <div className="nf-pr-titles">
            <div className="nf-pr-title">{title}</div>
            {subtitle && <div className="nf-pr-sub">{subtitle}</div>}
          </div>
          <button onClick={onClose} className="nf-pr-x" aria-label="Close"><X size={16} /></button>
        </div>
        <div className="nf-pr-body">{children}</div>
      </div>
    </div>
  );
}

export function FormRow({ children }: { children: ReactNode }) {
  return <div style={{ marginBottom: 14 }}>{children}</div>;
}

export function BtnPrimary({ children, onClick, disabled, type = 'button' }: { children: ReactNode; onClick?: () => void; disabled?: boolean; type?: 'button' | 'submit' }) {
  return (
    <button type={type} onClick={onClick} disabled={disabled} style={{ background: disabled ? 'var(--raised)' : 'var(--brand)', color: disabled ? 'var(--txt-dim)' : '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer' }}>
      {children}
    </button>
  );
}

export function BtnGhost({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{ background: 'var(--raised)', color: disabled ? 'var(--txt-dim)' : 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: disabled ? 'not-allowed' : 'pointer' }}>
      {children}
    </button>
  );
}
