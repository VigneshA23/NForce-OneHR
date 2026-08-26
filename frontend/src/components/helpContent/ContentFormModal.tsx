import { useState } from 'react';
import { ArrowDown, ArrowUp, Paperclip, X } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import {
  hrHelpContentApi,
  validateAttachmentFile,
  MAX_ATTACHMENTS_PER_CONTENT,
  type HelpContentDetail,
  type HelpContentType,
} from '../../api/helpContent';

// Same local style-const convention used across every page in this codebase — no shared
// component library, so these are re-declared here rather than imported from a page file.
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 560, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

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

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

/**
 * Internal confirmation modal — replaces window.confirm()/window.prompt() everywhere in this
 * module (Submit for Approval, Archive, Delete, Restore, Unpublish, remove-attachment, etc.).
 * Stays open and shows the error on failure instead of silently closing, and disables Cancel/
 * Confirm while the action is in flight so a slow request can't be double-submitted.
 */
export function ConfirmModal({ title, body, confirmLabel = 'Confirm', danger, onConfirm, onClose }: {
  title: string;
  body: React.ReactNode;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => Promise<void>;
  onClose: () => void;
}) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleConfirm() {
    setLoading(true);
    setError(null);
    try {
      await onConfirm();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Action failed');
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <ModalHeader title={title} onClose={onClose} />
        <div style={{ padding: 24 }}>
          <div style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.55, marginBottom: error ? 12 : 20 }}>{body}</div>
          {error && (
            <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 12.5, marginBottom: 16 }}>{error}</div>
          )}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>Cancel</button>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={loading}
              style={{
                background: danger ? '#C0392B' : 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7,
                padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1,
              }}
            >
              {loading ? 'Processing…' : confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function fmtSize(bytes: number | null) {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Create/edit form for Help & Guidance content (FAQ/Quick Help/Guide/Document). Shared between
 * the inline Help & Guidance management controls and (for now, still-present but unrouted)
 * HelpContentAdminPage — extracted here so the two don't duplicate the same form.
 *
 * Type is an implementation detail, not a form field: it's derived once from `initialType`
 * (set by which "Add" button opened this modal — FAQ vs Guide) on create, or from `editing.type`
 * on edit, and is never shown or user-editable — mirrors the backend, where `type` has no
 * corresponding field on UpdateHelpContentRequest at all. Publish/Archive/Delete/Submit/Withdraw
 * are row-level actions driven by status (see AdminItemControls in HelpDeskPage.tsx) — this
 * modal only edits fields and attachments.
 *
 * Attachment ids captured before this modal opened may not survive the save: editing PUBLISHED
 * content forks a brand-new draft row (see HelpContentService.prepareForEdit), whose cloned
 * attachments get fresh ids. `idMap` below correlates "the id I showed the user" to "the id the
 * server actually has right now" by position immediately after the first save call, since after
 * that no further fork can happen (the row is already DRAFT) and ids stay stable.
 */
export function ContentFormModal({ editing, initialType, token, onClose, onSaved }: {
  editing: HelpContentDetail | null;
  initialType?: HelpContentType;
  token: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { showToast } = useToast();
  // Not user-settable — derived once from whichever "Add" button opened this modal, or from the
  // content being edited. See the class doc above.
  const type: HelpContentType = editing?.type ?? initialType ?? 'FAQ';
  const typeLabel = type === 'FAQ' ? 'FAQ' : 'Guide';
  const modalTitle = editing ? `Edit ${typeLabel}` : `Add New ${typeLabel}`;
  const submitLabel = editing ? 'Save Changes' : `Add ${typeLabel}`;
  const [title, setTitle] = useState(editing?.title ?? '');
  const [description, setDescription] = useState(editing?.description ?? '');
  const [body, setBody] = useState(editing?.body ?? '');
  const [category, setCategory] = useState(editing?.category ?? '');
  const [featured, setFeatured] = useState(editing?.featured ?? false);

  // Attachment editing state — operates on `editing.attachments` snapshot; applied to the
  // server only on Save (see handleSubmit), same deferred pattern the single-attachment upload
  // already used (upload only after Save resolves an id).
  const [order, setOrder] = useState<string[]>((editing?.attachments ?? []).map(a => a.id));
  const [removedIds, setRemovedIds] = useState<Set<string>>(new Set());
  const [replacements, setReplacements] = useState<Map<string, File>>(new Map());
  const [newFiles, setNewFiles] = useState<File[]>([]);

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);

  const attachmentById = new Map((editing?.attachments ?? []).map(a => [a.id, a]));
  const totalAttachmentCount = order.length + newFiles.length;

  function moveExisting(id: string, dir: -1 | 1) {
    setOrder(prev => {
      const i = prev.indexOf(id);
      const j = i + dir;
      if (j < 0 || j >= prev.length) return prev;
      const next = [...prev];
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });
  }

  function confirmRemoveExisting(id: string) {
    setOrder(prev => prev.filter(x => x !== id));
    setRemovedIds(prev => new Set(prev).add(id));
    setReplacements(prev => { const next = new Map(prev); next.delete(id); return next; });
    setRemovingId(null);
  }

  function replaceExisting(id: string, file: File) {
    const msg = validateAttachmentFile(file);
    if (msg) { setAttachmentError(msg); return; }
    setAttachmentError(null);
    setReplacements(prev => new Map(prev).set(id, file));
  }

  function addNewFiles(files: File[]) {
    if (files.length === 0) return;
    if (totalAttachmentCount + files.length > MAX_ATTACHMENTS_PER_CONTENT) {
      setAttachmentError(`A FAQ/Guide can have at most ${MAX_ATTACHMENTS_PER_CONTENT} attachments (currently ${totalAttachmentCount}).`);
      return;
    }
    for (const f of files) {
      const msg = validateAttachmentFile(f);
      if (msg) { setAttachmentError(msg); return; }
    }
    setAttachmentError(null);
    setNewFiles(prev => [...prev, ...files]);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) { setError('Title is required.'); return; }
    setSaving(true); setError(null);
    try {
      const payload = { title: title.trim(), description: description.trim() || undefined, body: body.trim() || undefined, category: category.trim() || undefined, featured };
      let current = editing
        ? await hrHelpContentApi.update(editing.id, payload, token)
        : await hrHelpContentApi.create({ type, ...payload }, token);

      // Correlate pre-save attachment ids to whatever the server now has (identical ids unless
      // this save forked a new draft revision, in which case the clones sit at the same
      // positions in `current.attachments`).
      const idMap = new Map<string, string>();
      (editing?.attachments ?? []).forEach((old, i) => {
        const cur = current.attachments[i];
        if (cur) idMap.set(old.id, cur.id);
      });

      for (const originalId of removedIds) {
        const curId = idMap.get(originalId);
        if (curId) current = await hrHelpContentApi.removeAttachment(current.id, curId, token);
      }
      for (const [originalId, file] of replacements) {
        const curId = idMap.get(originalId);
        if (curId) current = await hrHelpContentApi.replaceAttachment(current.id, curId, file, token);
      }
      if (newFiles.length > 0) {
        current = await hrHelpContentApi.addAttachments(current.id, newFiles, token);
      }

      const originalSurvivingOrder = (editing?.attachments ?? []).map(a => a.id).filter(id => !removedIds.has(id));
      if (order.length > 0 && order.join('|') !== originalSurvivingOrder.join('|')) {
        const desiredIds = order.map(id => idMap.get(id)).filter((v): v is string => !!v);
        const rest = current.attachments.map(a => a.id).filter(id => !desiredIds.includes(id));
        current = await hrHelpContentApi.reorderAttachments(current.id, [...desiredIds, ...rest], token);
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

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title={modalTitle} onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          {editing?.rejectionReason && (
            <div style={{ color: '#F59E0B', background: 'rgba(245,158,11,.08)', border: '1px solid rgba(245,158,11,.25)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>
              <strong>Rejected:</strong> {editing.rejectionReason}
            </div>
          )}
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
          <Field label="Category">
            <input style={inputStyle} value={category} onChange={e => setCategory(e.target.value)} placeholder="e.g. Leave, Payroll" />
          </Field>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            <input type="checkbox" checked={featured} onChange={e => setFeatured(e.target.checked)} />
            Featured (gives this content priority over others on the employee-facing Help & Guidance page)
          </label>

          <Field label={`Attachments (${totalAttachmentCount}/${MAX_ATTACHMENTS_PER_CONTENT})`}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {attachmentError && (
                <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '8px 12px', fontSize: 12 }}>{attachmentError}</div>
              )}
              {order.map((id, i) => {
                const a = attachmentById.get(id);
                if (!a) return null;
                const replacement = replacements.get(id);
                return (
                  <div key={id} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px' }}>
                    <Paperclip size={13} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0, fontSize: 12.5, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {replacement ? `${replacement.name} (replacing ${a.fileName})` : a.fileName}
                      {!replacement && <span style={{ color: 'var(--txt-dim)' }}> · {fmtSize(a.fileSize)}</span>}
                    </div>
                    <button type="button" onClick={() => moveExisting(id, -1)} disabled={i === 0} style={{ background: 'none', border: 'none', color: 'var(--txt-mut)', cursor: i === 0 ? 'not-allowed' : 'pointer', padding: 2, display: 'flex' }}><ArrowUp size={13} /></button>
                    <button type="button" onClick={() => moveExisting(id, 1)} disabled={i === order.length - 1} style={{ background: 'none', border: 'none', color: 'var(--txt-mut)', cursor: i === order.length - 1 ? 'not-allowed' : 'pointer', padding: 2, display: 'flex' }}><ArrowDown size={13} /></button>
                    <label style={{ fontSize: 11.5, color: 'var(--brand)', cursor: 'pointer', fontWeight: 600 }}>
                      Replace
                      <input type="file" style={{ display: 'none' }} onChange={e => { const f = e.target.files?.[0]; if (f) replaceExisting(id, f); }} />
                    </label>
                    <button type="button" onClick={() => setRemovingId(id)} style={{ background: 'none', border: 'none', color: 'var(--risk)', cursor: 'pointer', padding: 0, fontSize: 11.5, fontWeight: 600 }}>Remove</button>
                  </div>
                );
              })}
              {newFiles.map((f, i) => (
                <div key={`${f.name}-${i}`} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px' }}>
                  <Paperclip size={13} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} />
                  <div style={{ flex: 1, minWidth: 0, fontSize: 12.5, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name} <span style={{ color: 'var(--txt-dim)' }}>(new)</span></div>
                  <button type="button" onClick={() => setNewFiles(prev => prev.filter((_, idx) => idx !== i))} style={{ background: 'none', border: 'none', color: 'var(--risk)', cursor: 'pointer', padding: 0, fontSize: 11.5, fontWeight: 600 }}>Remove</button>
                </div>
              ))}
              {totalAttachmentCount < MAX_ATTACHMENTS_PER_CONTENT && (
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
                  <Paperclip size={13} />
                  Add attachment(s) — select multiple at once
                  <input
                    type="file"
                    multiple
                    style={{ display: 'none' }}
                    onChange={e => { const files = Array.from(e.target.files ?? []); addNewFiles(files); e.target.value = ''; }}
                  />
                </label>
              )}
            </div>
          </Field>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, paddingTop: 4 }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={saving} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? 0.7 : 1 }}>
              {saving ? 'Saving…' : submitLabel}
            </button>
          </div>
        </form>
      </div>
      {removingId && (
        <ConfirmModal
          title="Remove Attachment"
          body="Remove this attachment? This still requires a fresh approval before publishing."
          confirmLabel="Remove"
          danger
          onConfirm={async () => confirmRemoveExisting(removingId)}
          onClose={() => setRemovingId(null)}
        />
      )}
    </div>
  );
}
