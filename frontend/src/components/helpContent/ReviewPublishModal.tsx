import { useState } from 'react';
import { Paperclip, X } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import {
  hrHelpContentApi, helpContentApi,
  AUDIENCE_OPTIONS, AUDIENCE_LABEL,
  type Audience, type HelpContentDetail,
} from '../../api/helpContent';
import { Field } from './ContentFormModal';
import { AttachmentViewerModal } from './AttachmentViewerModal';

// Same local style-const convention used across every Help & Guidance component in this
// codebase — no shared component library, so these are re-declared here rather than imported
// from another component file.
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 560, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const readOnlyStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', whiteSpace: 'pre-wrap', lineHeight: 1.5 };

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

function ReadOnlyField({ label, value }: { label: string; value: React.ReactNode }) {
  return <Field label={label}><div style={readOnlyStyle}>{value}</div></Field>;
}

/**
 * Read-only review of a single FAQ/Guide's full content, plus the audience decision that
 * actually publishes it. Deliberately separate from ContentFormModal — publishing is an
 * authorization/visibility decision, not another editing pass over the content fields, so this
 * modal never writes title/description/body/category/featured/attachments. It mirrors that
 * form's visual structure (same Field/label layout) purely so a reviewer can recognize what
 * they're looking at, not because it shares its behavior.
 *
 * Only ever opened for APPROVED/UNPUBLISHED content (see AdminItemControls in HelpDeskPage.tsx),
 * so "Publish" is always the applicable action here — there's no separate "read-only preview"
 * mode without it.
 */
export function ReviewPublishModal({ item, token, onClose, onEdit, onPublished }: {
  item: HelpContentDetail;
  token: string;
  onClose: () => void;
  onEdit: (item: HelpContentDetail) => void;
  onPublished: (updated: HelpContentDetail) => void;
}) {
  const { showToast } = useToast();
  const typeLabel = item.type === 'FAQ' ? 'FAQ' : 'Guide';
  // Re-publishing (UNPUBLISHED -> PUBLISHED) starts from whatever was selected last time rather
  // than resetting to just Employee, so adjusting an existing selection doesn't require
  // re-picking everything; a first-time publish (APPROVED -> PUBLISHED) has no prior selection,
  // so it defaults to Employee per the required default.
  const [audience, setAudience] = useState<Set<Audience>>(
    new Set(item.audience.length > 0 ? item.audience : ['EMPLOYEE'])
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [viewingAttachments, setViewingAttachments] = useState(false);

  function toggleAudience(a: Audience) {
    setAudience(prev => {
      const next = new Set(prev);
      if (next.has(a)) next.delete(a); else next.add(a);
      return next;
    });
  }

  async function handlePublish() {
    if (audience.size === 0) { setError('Select at least one audience.'); return; }
    setSaving(true); setError(null);
    try {
      const updated = await hrHelpContentApi.publish(item.id, Array.from(audience), token);
      showToast('success', `Published to: ${Array.from(audience).map(a => AUDIENCE_LABEL[a]).join(', ')}`);
      onPublished(updated);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Publish failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSaving(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title={`Review & Publish ${typeLabel}`} onClose={onClose} />
        <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}

          <ReadOnlyField label={item.type === 'FAQ' ? 'Question' : 'Title'} value={item.title} />
          <ReadOnlyField label={item.type === 'FAQ' ? 'Short Answer' : 'Description'} value={item.description || <span style={{ color: 'var(--txt-dim)' }}>—</span>} />
          {item.type !== 'FAQ' && (
            <ReadOnlyField label="Body" value={item.body || <span style={{ color: 'var(--txt-dim)' }}>—</span>} />
          )}
          <div style={{ display: 'flex', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <ReadOnlyField label="Category" value={item.category || <span style={{ color: 'var(--txt-dim)' }}>—</span>} />
            </div>
            <div style={{ flex: 1 }}>
              <ReadOnlyField label="Featured" value={item.featured ? 'Yes' : 'No'} />
            </div>
          </div>

          <Field label={`Attachments (${item.attachments.length})`}>
            {item.attachments.length === 0 ? (
              <div style={{ ...readOnlyStyle, color: 'var(--txt-dim)' }}>No attachments.</div>
            ) : (
              <button
                type="button"
                onClick={() => setViewingAttachments(true)}
                style={{ display: 'flex', alignItems: 'center', gap: 8, width: '100%', textAlign: 'left', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, cursor: 'pointer' }}
              >
                <Paperclip size={13} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} />
                <span style={{ flex: 1 }}>
                  {item.attachments.length} file{item.attachments.length === 1 ? '' : 's'} — {item.attachments.map(a => a.fileName).join(', ')}
                </span>
                <span style={{ color: 'var(--brand)', fontWeight: 600, fontSize: 12, flexShrink: 0 }}>View</span>
              </button>
            )}
          </Field>

          {/* Publish To — visually distinct from the read-only content above: its own tinted
             panel, not just another Field, since this is the one thing this modal actually
             changes. */}
          <div style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: 16 }}>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 13, color: 'var(--txt)', marginBottom: 4 }}>Publish To</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 12 }}>Choose who should be able to see this content.</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              {AUDIENCE_OPTIONS.map(a => (
                <label key={a} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 8px', borderRadius: 6, cursor: 'pointer', fontSize: 13, color: 'var(--txt)' }}>
                  <input type="checkbox" checked={audience.has(a)} onChange={() => toggleAudience(a)} />
                  {AUDIENCE_LABEL[a]}
                </label>
              ))}
            </div>
            {audience.size === 0 && (
              <div style={{ color: 'var(--risk)', fontSize: 12, marginTop: 8 }}>Select at least one audience.</div>
            )}
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, paddingTop: 4 }}>
            <button type="button" onClick={onClose} disabled={saving} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: saving ? 'not-allowed' : 'pointer' }}>Cancel</button>
            <button type="button" onClick={() => onEdit(item)} disabled={saving} style={{ background: 'var(--raised2)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: saving ? 'not-allowed' : 'pointer' }}>Edit</button>
            <button
              type="button"
              onClick={handlePublish}
              disabled={saving || audience.size === 0}
              style={{
                background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600,
                cursor: (saving || audience.size === 0) ? 'not-allowed' : 'pointer', opacity: (saving || audience.size === 0) ? 0.6 : 1,
              }}
            >
              {saving ? 'Publishing…' : 'Publish'}
            </button>
          </div>
        </div>
      </div>
      {viewingAttachments && (
        <AttachmentViewerModal
          title={item.title}
          attachments={item.attachments}
          fetchBlob={attachmentId => helpContentApi.downloadAttachment(item.id, attachmentId, token)}
          onClose={() => setViewingAttachments(false)}
        />
      )}
    </div>
  );
}
