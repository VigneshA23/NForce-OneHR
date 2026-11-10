import { X, User as UserIcon, UserPlus, Pencil, KeyRound, Trash2, Power, CheckCircle2 } from 'lucide-react';

// Moved out of UserManagementPage.tsx (same reasoning as SharedControls.tsx/orgOptionsHelpers.ts)
// so both it and the four-step employee creation wizard can import these presentational
// primitives independently, without the wizard importing from the page that renders it. Styling,
// markup, and behavior are unchanged from the originals.

export const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(15,17,23,.6)', backdropFilter: 'blur(3px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500, padding: 16 };
export const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.4)' };
export const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 9, padding: '10px 12px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
export const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.05em' };
export const primaryBtnStyle: React.CSSProperties = { background: 'linear-gradient(135deg, var(--brand-bright), var(--brand))', color: '#fff', border: 'none', borderRadius: 9, padding: '10px 20px', fontSize: 13, fontWeight: 700, cursor: 'pointer', boxShadow: '0 6px 16px rgba(177,17,22,.25)' };
export const secondaryBtnStyle: React.CSSProperties = { background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 9, padding: '10px 18px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };

function headerIcon(title: string): React.ComponentType<{ size?: number }> {
  if (title.startsWith('Add')) return UserPlus;
  if (title.startsWith('Edit')) return Pencil;
  if (title.startsWith('Reset')) return KeyRound;
  if (title.startsWith('Delete')) return Trash2;
  if (title.startsWith('Deactivate') || title.startsWith('Activate') || title.startsWith('Reactivate')) return Power;
  if (title.includes('Created')) return CheckCircle2;
  return UserIcon;
}

export function ModalHeader({ title, subtitle, onClose }: { title: string; subtitle?: string; onClose: () => void }) {
  const Icon = headerIcon(title);
  return (
    <div className="nf-um-head">
      <span className="nf-um-head-ico"><Icon size={20} /></span>
      <div className="nf-um-head-txt">
        <div className="nf-um-head-title">{title}</div>
        {subtitle && <div className="nf-um-head-sub">{subtitle}</div>}
      </div>
      <button onClick={onClose} className="nf-um-close" aria-label="Close"><X size={16} /></button>
    </div>
  );
}

/** A titled card that groups related fields of a wizard step. Presentation only. */
export function Section({ icon: Icon, title, hint, children }: { icon: React.ComponentType<{ size?: number }>; title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="nf-ce-sec">
      <div className="nf-ce-sec-head">
        <span className="nf-ce-sec-ico"><Icon size={16} /></span>
        <div>
          <h3>{title}</h3>
          {hint && <p>{hint}</p>}
        </div>
      </div>
      <div className="nf-ce-grid nf-grid-2col-collapse">{children}</div>
    </section>
  );
}

export function Field({ label, span, children }: { label: string; span?: 'full'; children: React.ReactNode }) {
  return <div style={span === 'full' ? { gridColumn: '1/-1' } : undefined}><label style={labelStyle}>{label}</label>{children}</div>;
}
