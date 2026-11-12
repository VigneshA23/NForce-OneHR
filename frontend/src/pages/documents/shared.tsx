import { useRef, useState } from 'react';
import { Eye, Undo2, Upload, AlertTriangle } from 'lucide-react';
import { DocPopup } from './DocPopup';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import {
  uploadDocument, withdrawDocument, fetchDocumentFile,
  type EmployeeDocument, type RequiredDocument, type DocumentType,
} from '../../api/documents';

export const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', overflow: 'hidden' };
export const thS: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 800, color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.09em', whiteSpace: 'nowrap', borderBottom: '1px solid color-mix(in srgb, var(--brand) 22%, var(--line))', background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))' };
export const tdS: React.CSSProperties = { padding: '13px 16px', fontSize: 13.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

export interface RequiredDocumentBuckets {
  verified: RequiredDocument[];
  pending: RequiredDocument[];
  rejected: RequiredDocument[];
  missing: RequiredDocument[];
}

// Single source of truth for the Pending Review / Verified / Rejected / Not Submitted buckets
// shared by MyDocumentsSection's tabs and DocumentsPage's KPI tiles. REJECTED is its own bucket,
// never folded into `pending` — a rejected document must never be counted or displayed as
// "awaiting HR review", but must stay visible (with its rejection reason) so the employee still
// knows it needs a re-upload, instead of silently vanishing.
export function bucketRequiredDocuments(required: RequiredDocument[]): RequiredDocumentBuckets {
  return {
    verified: required.filter(r => r.status === 'VERIFIED'),
    pending: required.filter(r => r.status === 'PENDING_VERIFICATION'),
    rejected: required.filter(r => r.status === 'REJECTED'),
    missing: required.filter(r => !r.uploaded),
  };
}

export function StatusBadge({ status }: { status: string | null }) {
  if (!status) return <span style={{ color: 'var(--txt-dim)', fontSize: 12 }}>Not uploaded</span>;
  const map: Record<string, { label: string; color: string }> = {
    VERIFIED: { label: 'Verified', color: 'var(--ok)' },
    PENDING_VERIFICATION: { label: 'Pending Review', color: 'var(--warn)' },
    REJECTED: { label: 'Rejected', color: 'var(--risk)' },
  };
  const cfg = map[status] ?? { label: status, color: 'var(--txt-dim)' };
  const tone = status === 'PENDING_VERIFICATION' ? ' is-warn' : status === 'REJECTED' ? ' is-bad' : status === 'VERIFIED' ? '' : ' is-off';
  return (
    <span className={`nf-rep-pill${tone}`}>
      {cfg.label}
    </span>
  );
}

// ── Upload Modal ──────────────────────────────────────────

export function UploadModal({
  docType, existing, onClose, onUploaded,
}: {
  docType: DocumentType | RequiredDocument;
  existing?: EmployeeDocument | null;
  onClose(): void;
  onUploaded(doc: EmployeeDocument): void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const fileRef = useRef<HTMLInputElement>(null);
  const [issueDate, setIssueDate] = useState(existing?.issueDate?.split('T')[0] ?? '');
  const [expiryDate, setExpiryDate] = useState(existing?.expiryDate?.split('T')[0] ?? '');
  const [loading, setLoading] = useState(false);

  const dateError = issueDate && expiryDate && expiryDate < issueDate
    ? 'Expiry date cannot be earlier than issue date'
    : null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const file = fileRef.current?.files?.[0];
    if (!file) return showToast('error', 'Please select a file');
    if (dateError) return showToast('error', dateError);
    setLoading(true);
    try {
      const doc = await uploadDocument(token, {
        documentTypeId: (docType as any).id ?? (docType as any).documentTypeId,
        file,
        issueDate: issueDate || null,
        expiryDate: expiryDate || null,
      });
      onUploaded(doc);
      showToast('success', existing ? 'Document updated' : 'Document uploaded');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Upload failed');
    } finally {
      setLoading(false);
    }
  }

  const requiresExpiry = (docType as any).requiresExpiryDate;
  const name = (docType as any).name ?? (docType as any).documentTypeName;

  return (
    <DocPopup
      icon={Upload}
      size="md"
      title={`${existing ? 'Update' : 'Upload'}: ${name}`}
      sub={existing ? 'Choose a new file to replace the current one.' : 'PDF, JPG, PNG or Word files are accepted.'}
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="nf-dp-cancel" onClick={onClose} disabled={loading}>Cancel</button>
          <button type="submit" form="nf-dp-upload-form" className="nf-dp-go" disabled={loading || !!dateError}>
            <Upload size={15} /> {loading ? (existing ? 'Updating…' : 'Uploading…') : existing ? 'Update' : 'Upload'}
          </button>
        </>
      )}
    >
      <form id="nf-dp-upload-form" onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div className="nf-dp-f">
          <label className="nf-dp-l">File *</label>
          <input ref={fileRef} type="file" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx" required className="nf-dp-in nf-dp-file" />
        </div>
        <div className="nf-dp-grid nf-grid-2col-collapse">
          <div className="nf-dp-f">
            <label className="nf-dp-l">Issue date</label>
            <input type="date" value={issueDate} onChange={e => setIssueDate(e.target.value)} className="nf-dp-in" />
          </div>
          <div className="nf-dp-f">
            <label className="nf-dp-l">Expiry date {requiresExpiry ? '*' : ''}</label>
            <input type="date" value={expiryDate} onChange={e => setExpiryDate(e.target.value)} required={requiresExpiry}
              min={issueDate || undefined} className={`nf-dp-in${dateError ? ' is-bad' : ''}`} />
          </div>
        </div>
        {dateError && <p className="nf-dp-err" style={{ margin: 0 }}>{dateError}</p>}
      </form>
    </DocPopup>
  );
}

// ── Withdraw (Pending Review only) ─────────────────────────
// A document awaiting HR review can't be re-uploaded over directly (see
// DocumentService.uploadDocument) — the employee withdraws it first, which frees the slot and
// reverts the document type to "Not Submitted", then uploads fresh.

export function WithdrawButton({ doc, onWithdrawn }: {
  doc: EmployeeDocument;
  onWithdrawn(documentTypeId: number): void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [confirming, setConfirming] = useState(false);
  const [loading, setLoading] = useState(false);

  async function confirmWithdraw() {
    setLoading(true);
    try {
      await withdrawDocument(token, doc.id);
      onWithdrawn(doc.documentTypeId);
      showToast('success', 'Document withdrawn - you can now upload a new version');
      setConfirming(false);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Withdraw failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <button type="button" className="nf-act-btn" onClick={() => setConfirming(true)}>
        <Undo2 size={13} />Withdraw
      </button>
      {confirming && (
        <DocPopup
          icon={AlertTriangle}
          tone="risk"
          size="sm"
          title={`Withdraw ${doc.documentTypeName}?`}
          sub="This can't be undone, but you can upload again."
          onClose={() => setConfirming(false)}
          footer={(
            <>
              <button type="button" className="nf-dp-cancel" onClick={() => setConfirming(false)} disabled={loading}>Cancel</button>
              <button type="button" className="nf-dp-go is-risk" onClick={confirmWithdraw} disabled={loading}>
                <Undo2 size={15} /> {loading ? 'Withdrawing…' : 'Withdraw'}
              </button>
            </>
          )}
        >
          <p className="nf-dp-text">
            This pulls it out of Pending Review so HR won't act on it. You'll be able to upload a new version right after.
          </p>
        </DocPopup>
      )}
    </>
  );
}

// ── File View helper ──────────────────────────────────────

export function ViewButton({ docId }: { docId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);

  async function open() {
    setLoading(true);
    try {
      const url = await fetchDocumentFile(token, docId);
      window.open(url, '_blank');
    } catch {
      showToast('error', 'Could not open file');
    } finally {
      setLoading(false);
    }
  }

  return (
    <button type="button" className="nf-act-btn" onClick={open} disabled={loading}>
      <Eye size={13} /> {loading ? '…' : 'View'}
    </button>
  );
}
