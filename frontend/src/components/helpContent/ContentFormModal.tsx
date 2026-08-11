import { useState } from 'react';
import { Paperclip, X } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import {
  hrHelpContentApi,
  type HelpContentDetail,
  type HelpContentType,
} from '../../api/helpContent';

// Same local style-const convention used across every page in this codebase — no shared
// component library, so these are re-declared here rather than imported from a page file.
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 560, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

const TYPE_OPTIONS: HelpContentType[] = ['FAQ', 'QUICK_HELP', 'GUIDE', 'DOCUMENT'];
const TYPE_LABEL: Record<HelpContentType, string> = { FAQ: 'FAQ', QUICK_HELP: 'Quick Help', GUIDE: 'Guide', DOCUMENT: 'Document' };

export function StatusChip({ label, tone }: { label: string; tone: 'ok' | 'warn' | 'dim' }) {
  const colors = {
    ok: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
    warn: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
    dim: { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' },
  }[tone];
  return <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: colors.bg, color: colors.color, whiteSpace: 'nowrap' }}>{label}</span>;
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

/**
 * Create/edit form for Help & Guidance content (FAQ/Quick Help/Guide/Document). Shared between
 * the inline Help & Guidance management controls and (for now, still-present but unrouted)
 * HelpContentAdminPage — extracted here so the two don't duplicate the same form.
 *
 * Type is fixed once created (mirrors ticket categories: pick at creation, don't reclassify
 * after). Publish/unpublish and delete live inside this modal rather than as inline buttons on
 * every card, keeping the Help & Guidance page from feeling like a separate admin dashboard —
 * "Edit" and "Archive" are the only actions exposed directly on a card/FAQ row.
 */
export function ContentFormModal({ editing, initialType, token, onClose, onSaved }: {
  editing: HelpContentDetail | null;
  initialType?: HelpContentType;
  token: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { showToast } = useToast();
  const [type, setType] = useState<HelpContentType>(editing?.type ?? initialType ?? 'FAQ');
  const [title, setTitle] = useState(editing?.title ?? '');
  const [description, setDescription] = useState(editing?.description ?? '');
  const [body, setBody] = useState(editing?.body ?? '');
  const [category, setCategory] = useState(editing?.category ?? '');
  const [featured, setFeatured] = useState(editing?.featured ?? false);
  const [displayOrder, setDisplayOrder] = useState(editing?.displayOrder ?? 0);
  const [attachment, setAttachment] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) { setError('Title is required.'); return; }
    setSaving(true); setError(null);
    try {
      const payload = { title: title.trim(), description: description.trim() || undefined, body: body.trim() || undefined, category: category.trim() || undefined, featured, displayOrder };
      const saved = editing
        ? await hrHelpContentApi.update(editing.id, payload, token)
        : await hrHelpContentApi.create({ type, ...payload }, token);
      if (attachment) {
        await hrHelpContentApi.uploadAttachment(saved.id, attachment, token);
      }
      showToast('success', editing ? 'Content updated' : 'Content created');
      onSaved();
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Save failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSaving(false); }
  }

  async function handleTogglePublish() {
    if (!editing) return;
    setSaving(true);
    try {
      const updated = await (editing.published ? hrHelpContentApi.unpublish(editing.id, token) : hrHelpContentApi.publish(editing.id, token));
      showToast('success', updated.published ? 'Published' : 'Unpublished');
      onSaved();
      onClose();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to update');
    } finally { setSaving(false); }
  }

  async function handleDelete() {
    if (!editing) return;
    if (!window.confirm(`Delete "${editing.title}"? This cannot be undone.`)) return;
    setSaving(true);
    try {
      await hrHelpContentApi.remove(editing.id, token);
      showToast('success', 'Deleted');
      onSaved();
      onClose();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to delete');
    } finally { setSaving(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title={editing ? 'Edit Content' : 'New Content'} onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <Field label="Type *">
            <select style={inputStyle} value={type} disabled={!!editing} onChange={e => setType(e.target.value as HelpContentType)}>
              {TYPE_OPTIONS.map(t => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
            </select>
          </Field>
          <Field label="Title *">
            <input style={inputStyle} value={title} onChange={e => setTitle(e.target.value)} placeholder={type === 'FAQ' ? 'The question, as an employee would ask it' : 'Title'} />
          </Field>
          <Field label={type === 'FAQ' ? 'Short Answer' : 'Description'}>
            <textarea style={{ ...inputStyle, minHeight: 60, resize: 'vertical', fontFamily: 'inherit' }} value={description} onChange={e => setDescription(e.target.value)} placeholder={type === 'FAQ' ? 'Shown directly under the question when expanded' : 'One-line summary shown on the card'} />
          </Field>
          {type !== 'FAQ' && (
            <Field label="Body">
              <textarea style={{ ...inputStyle, minHeight: 110, resize: 'vertical', fontFamily: 'inherit' }} value={body} onChange={e => setBody(e.target.value)} placeholder="Full content shown when an employee opens this item" />
            </Field>
          )}
          <div style={{ display: 'flex', gap: 12 }}>
            <div style={{ flex: 1 }}>
              <Field label="Category">
                <input style={inputStyle} value={category} onChange={e => setCategory(e.target.value)} placeholder="e.g. Leave, Payroll" />
              </Field>
            </div>
            <div style={{ width: 110 }}>
              <Field label="Order">
                <input type="number" style={inputStyle} value={displayOrder} onChange={e => setDisplayOrder(Number(e.target.value))} />
              </Field>
            </div>
          </div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            <input type="checkbox" checked={featured} onChange={e => setFeatured(e.target.checked)} />
            Featured (boosts ranking in "Top FAQs" / curated view)
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            <Paperclip size={13} />
            {attachment ? attachment.name : editing?.hasAttachment ? `Replace attachment (currently: ${editing.attachmentName})` : 'Attach a document (optional)'}
            <input type="file" style={{ display: 'none' }} onChange={e => setAttachment(e.target.files?.[0] ?? null)} />
          </label>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 10, paddingTop: 4 }}>
            <div>
              {editing && (
                <button type="button" onClick={handleDelete} disabled={saving} style={{ background: 'none', border: 'none', color: 'var(--risk)', fontSize: 12.5, fontWeight: 600, cursor: saving ? 'not-allowed' : 'pointer', padding: 0 }}>
                  Delete
                </button>
              )}
            </div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              {editing && (
                <button type="button" onClick={handleTogglePublish} disabled={saving} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: saving ? 'not-allowed' : 'pointer' }}>
                  {editing.published ? 'Unpublish' : 'Publish'}
                </button>
              )}
              <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
              <button type="submit" disabled={saving} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? 0.7 : 1 }}>
                {saving ? 'Saving…' : editing ? 'Save Changes' : 'Create'}
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
}
