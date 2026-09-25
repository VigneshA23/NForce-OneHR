import { X } from 'lucide-react';

// ── Shared styles ─────────────────────────────────────────

export const panelStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
export const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
export const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
export const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
export const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
export const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
export const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };

export function fmtCurrency(n?: number | null) {
  if (n == null) return '—';
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n);
}

export function fmtDate(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { bg: string; color: string }> = {
    SUBMITTED: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
    MANAGER_APPROVED: { bg: 'rgba(99,102,241,.15)', color: '#818CF8' },
    CLEARED_FOR_PAYROLL: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
    PAID: { bg: 'rgba(47,182,124,.15)', color: '#2FB67C' },
    MANAGER_REJECTED: { bg: 'rgba(228,55,61,.15)', color: '#E4373D' },
    FINAL_REJECTED: { bg: 'rgba(228,55,61,.15)', color: '#E4373D' },
    PENDING: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
    APPROVED: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
    REJECTED: { bg: 'rgba(228,55,61,.15)', color: '#E4373D' },
    WITHDRAWN: { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' },
    AVAILABLE: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
    ASSIGNED: { bg: 'rgba(99,102,241,.15)', color: '#818CF8' },
    IN_REPAIR: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
    RETIRED: { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' },
    FULFILLED: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
  };
  const style = map[status] ?? { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' };
  return <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: style.bg, color: style.color }}>{status.replace(/_/g, ' ')}</span>;
}

export interface TileProps {
  label: string;
  value: string | number;
  sub?: string;
  clickable?: boolean;
  onClick?: () => void;
  clickHint?: string;
}
export function Tile({ label, value, sub, clickable, onClick, clickHint }: TileProps) {
  return (
    <div
      onClick={clickable ? onClick : undefined}
      style={{
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px',
        cursor: clickable ? 'pointer' : 'default',
        transition: clickable ? 'border-color .15s' : undefined,
        minWidth: 0,
      }}
      onMouseEnter={clickable && onClick ? e => (e.currentTarget.style.borderColor = 'var(--brand)') : undefined}
      onMouseLeave={clickable && onClick ? e => (e.currentTarget.style.borderColor = 'var(--line)') : undefined}
    >
      <div style={{ fontSize: 24, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{value}</div>
      <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3, fontWeight: 600 }}>{label}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>{sub}</div>}
      {clickable && onClick && clickHint && <div style={{ fontSize: 10, color: 'var(--brand)', marginTop: 6, fontWeight: 600 }}>{clickHint}</div>}
    </div>
  );
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
    <div style={overlayStyle}>
      <div style={modalStyle}>
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
