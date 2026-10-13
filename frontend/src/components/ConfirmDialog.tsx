// Generic "are you sure" modal — reused anywhere a risky action needs an explicit confirmation
// step before it fires (e.g. granting a high-risk additional role, lifting a Super Admin fence).
// Every existing confirm modal in the codebase (StatusModal, DeleteModal, ConfirmDeleteModal...)
// is bespoke/page-local; this is the first shared one, modeled on their shared visual idiom
// (fixed overlay + var(--panel) card, Cancel/danger-action footer).
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(15,17,23,.6)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600, padding: 16 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, width: '94vw', maxWidth: 440, boxShadow: '0 24px 64px rgba(0,0,0,.4)' };

export interface ConfirmDialogProps {
  title: string;
  /** Plain explanation of what's about to happen and why it needs confirming. */
  body: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Red/danger styling for the confirm button — use for anything that grants risky access or is hard to undo. */
  danger?: boolean;
  loading?: boolean;
  error?: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({ title, body, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger = true, loading = false, error, onConfirm, onCancel }: ConfirmDialogProps) {
  return (
    <div style={overlayStyle} onClick={onCancel}>
      <div style={modalStyle} onClick={e => e.stopPropagation()}>
        <div style={{ padding: '18px 22px', borderBottom: '1px solid var(--line)' }}>
          <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 16, color: 'var(--txt)' }}>{title}</div>
        </div>
        <div style={{ padding: 22 }}>
          <div style={{
            background: danger ? 'rgba(228,55,61,.08)' : 'rgba(224,169,59,.1)',
            border: `1px solid ${danger ? 'rgba(228,55,61,.2)' : 'rgba(224,169,59,.25)'}`,
            borderRadius: 8, padding: 14, marginBottom: 18, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7,
          }}>
            {body}
          </div>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 12, fontSize: 13 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onCancel} disabled={loading} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: loading ? 'not-allowed' : 'pointer' }}>
              {cancelLabel}
            </button>
            <button onClick={onConfirm} disabled={loading} style={{ background: danger ? '#C0392B' : 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>
              {loading ? 'Working…' : confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
