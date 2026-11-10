import { useEffect } from 'react';
import { KeyRound, X } from 'lucide-react';

// ─── Shared styles (same tokens/look as User Management) ────────────────────────
export const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(8,10,16,.6)', backdropFilter: 'blur(3px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500, padding: 16 };
export const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 12, padding: '10px 13px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
export const labelStyle: React.CSSProperties = { display: 'block', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.08em' };
export const primaryBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, background: 'linear-gradient(135deg, var(--brand), color-mix(in srgb, var(--brand) 70%, #000))', color: '#fff', border: 'none', borderRadius: 12, padding: '10px 18px', fontSize: 13, fontWeight: 700, cursor: 'pointer', boxShadow: '0 8px 18px color-mix(in srgb, var(--brand) 26%, transparent)' };
export const secondaryBtn: React.CSSProperties = { background: 'var(--raised)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 12, padding: '9px 16px', fontSize: 13, fontWeight: 700, cursor: 'pointer' };
export const dangerBtn: React.CSSProperties = { ...secondaryBtn, color: 'var(--risk)', background: 'color-mix(in srgb, var(--risk) 7%, var(--panel))', borderColor: 'color-mix(in srgb, var(--risk) 38%, var(--line2))' };
export const thStyle: React.CSSProperties = { padding: '12px 18px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', background: 'var(--raised)' };
export const tdStyle: React.CSSProperties = { padding: '13px 18px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

export function ModalHeader({ title, subtitle, onClose }: { title: string; subtitle?: string; onClose: () => void }) {
  return (
    <div className="nf-rl-head">
      <span className="nf-rl-head-ico"><KeyRound size={19} /></span>
      <div className="nf-rl-head-txt">
        <div className="nf-rl-head-title">{title}</div>
        {subtitle && <div className="nf-rl-head-sub">{subtitle}</div>}
      </div>
      <button type="button" onClick={onClose} aria-label="Close" className="nf-rl-x"><X size={16} /></button>
    </div>
  );
}

export function Banner({ tone, children }: { tone: 'info' | 'warn' | 'error'; children: React.ReactNode }) {
  const c = tone === 'error' ? 'var(--risk)' : tone === 'warn' ? '#E0A93B' : 'var(--info, #4C8DD6)';
  return <div role={tone === 'error' ? 'alert' : undefined} style={{ fontSize: 12.5, color: 'var(--txt)', background: `color-mix(in srgb, ${c} 10%, var(--panel))`, border: `1px solid color-mix(in srgb, ${c} 32%, transparent)`, borderRadius: 12, padding: '10px 13px', lineHeight: 1.5 }}>{children}</div>;
}

/** Escape + backdrop close for a modal, routed through `requestClose` so dirty state can intercept it. */
export function useDismiss(requestClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') requestClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [requestClose]);
}
