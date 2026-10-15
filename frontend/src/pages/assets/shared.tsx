import { X } from 'lucide-react';
import { AlarmClock, Hourglass, CheckCircle2, Boxes, Receipt, ClipboardList, Package } from 'lucide-react';
import DashTile from '../../components/DashTile';

// ── Shared styles ─────────────────────────────────────────

export const panelStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
export const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
export const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
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

// Semantic status → the app's theme tokens (--ok/--warn/--risk/--info), not fixed hex values, so
// badges follow the selected accent color and light/dark mode like the rest of the redesigned
// app instead of always rendering the same colors regardless of theme.
const STATUS_TONE: Record<string, string> = {
  SUBMITTED: 'var(--warn)',
  MANAGER_APPROVED: 'var(--info)',
  CLEARED_FOR_PAYROLL: 'var(--ok)',
  PAID: 'var(--ok)',
  MANAGER_REJECTED: 'var(--risk)',
  FINAL_REJECTED: 'var(--risk)',
  PENDING: 'var(--warn)',
  APPROVED: 'var(--ok)',
  REJECTED: 'var(--risk)',
  WITHDRAWN: 'var(--txt-dim)',
  AVAILABLE: 'var(--ok)',
  ASSIGNED: 'var(--info)',
  IN_REPAIR: 'var(--warn)',
  RETIRED: 'var(--txt-dim)',
  FULFILLED: 'var(--ok)',
};

export function StatusBadge({ status }: { status: string }) {
  const tone = STATUS_TONE[status] ?? 'var(--txt-dim)';
  return (
    <span style={{
      fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20,
      background: `color-mix(in srgb, ${tone} 15%, transparent)`, color: tone,
    }}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}

export interface TileProps {
  label: string;
  value: string | number;
  sub?: string;
  clickable?: boolean;
  onClick?: () => void;
  clickHint?: string;
}
// Icon + colour for a tile, chosen from its label so every call site stays as it was.
function tileLook(label: string): { icon: React.ReactNode; accent: string } {
  const l = label.toLowerCase();
  if (l.includes('overdue')) return { icon: <AlarmClock size={20} />, accent: 'var(--risk)' };
  if (l.includes('pending')) return { icon: <Hourglass size={20} />, accent: 'var(--warn)' };
  if (l.includes('approved')) return { icon: <CheckCircle2 size={20} />, accent: 'var(--ok)' };
  if (l.includes('available') || l.includes('inventory')) return { icon: <Boxes size={20} />, accent: 'var(--info)' };
  if (l.includes('expense') || l.includes('claim')) return { icon: <Receipt size={20} />, accent: 'var(--om-purple)' };
  if (l.includes('request')) return { icon: <ClipboardList size={20} />, accent: 'var(--brand)' };
  return { icon: <Package size={20} />, accent: 'var(--info)' };
}

// Same tile as the Home dashboard (components/DashTile). Click behaviour is unchanged: only a tile marked
// `clickable` with an `onClick` is a button.
export function Tile({ label, value, sub, clickable, onClick, clickHint }: TileProps) {
  const { icon, accent } = tileLook(label);
  const isClick = !!(clickable && onClick);
  const subText = [sub, isClick ? clickHint : undefined].filter(Boolean).join(' · ') || undefined;
  return <DashTile icon={icon} accent={accent} label={label} value={String(value)} sub={subText} onClick={isClick ? onClick : undefined} />;
}

export function SectionHead({ title, action }: { title: string; action?: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
      <h2 style={{ margin: 0, fontSize: 15, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{title}</h2>
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

// ── Modal wrapper ─────────────────────────────────────────

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div style={overlayStyle} className="nf-modal-overlay-in">
      <div style={modalStyle} className="nf-modal-panel-in">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex' }}><X size={16} /></button>
        </div>
        <div style={{ padding: 20 }}>{children}</div>
      </div>
    </div>
  );
}

export function FormRow({ children }: { children: React.ReactNode }) {
  return <div style={{ marginBottom: 14 }}>{children}</div>;
}

export function BtnPrimary({ children, onClick, disabled, type = 'button' }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean; type?: 'button' | 'submit' }) {
  return (
    <button type={type} onClick={onClick} disabled={disabled} style={{ background: disabled ? 'var(--raised)' : 'var(--brand)', color: disabled ? 'var(--txt-dim)' : '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer' }}>
      {children}
    </button>
  );
}

export function BtnGhost({ children, onClick }: { children: React.ReactNode; onClick: () => void }) {
  return (
    <button onClick={onClick} style={{ background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: 'pointer' }}>
      {children}
    </button>
  );
}

// ── File → base64 helper ──────────────────────────────────

export function fileToBase64(file: File): Promise<string> {
  return new Promise((res, rej) => {
    const reader = new FileReader();
    reader.onload = () => res(reader.result as string);
    reader.onerror = rej;
    reader.readAsDataURL(file);
  });
}
