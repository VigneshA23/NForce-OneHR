import { useEffect } from 'react';
import { X } from 'lucide-react';

// ─── Shared styles (same tokens/look as User Management) ────────────────────────
export const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(15,17,23,.6)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500, padding: 16 };
export const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 9, padding: '10px 12px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
export const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.05em' };
export const primaryBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
export const secondaryBtn: React.CSSProperties = { background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
export const dangerBtn: React.CSSProperties = { ...secondaryBtn, color: 'var(--risk)', borderColor: 'color-mix(in srgb, var(--risk) 40%, var(--line2))' };
export const thStyle: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', background: 'var(--raised)' };
export const tdStyle: React.CSSProperties = { padding: '12px 16px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };



export function ModalHeader({ title, subtitle, onClose }: { title: string; subtitle?: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, padding: '18px 22px', borderBottom: '1px solid var(--line)' }}>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 16, color: 'var(--txt)' }}>{title}</div>
        {subtitle && <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>{subtitle}</div>}
      </div>
      <button onClick={onClose} aria-label="Close" style={{ background: 'var(--raised2)', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 6, borderRadius: 8, display: 'flex' }}><X size={16} /></button>
    </div>
  );
}

export function Banner({ tone, children }: { tone: 'info' | 'warn' | 'error'; children: React.ReactNode }) {
  const c = tone === 'error' ? 'var(--risk)' : tone === 'warn' ? '#E0A93B' : 'var(--info, #4C8DD6)';
  return <div role={tone === 'error' ? 'alert' : undefined} style={{ fontSize: 12.5, color: 'var(--txt)', background: `color-mix(in srgb, ${c} 10%, transparent)`, border: `1px solid color-mix(in srgb, ${c} 35%, transparent)`, borderRadius: 9, padding: '9px 12px', lineHeight: 1.5 }}>{children}</div>;
}

/** Escape + backdrop close for a modal, routed through `requestClose` so dirty state can intercept it. */
export function useDismiss(requestClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') requestClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [requestClose]);
}

