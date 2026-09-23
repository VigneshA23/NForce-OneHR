import { useRef, useState } from 'react';
import { CheckCircle, Clock, Eye, XCircle } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import {
  uploadDocument, fetchDocumentFile,
  type EmployeeDocument, type RequiredDocument, type DocumentType,
} from '../../api/documents';

export const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
export const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)' };
export const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

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
    VERIFIED: { label: 'Verified', color: '#22c55e' },
    PENDING_VERIFICATION: { label: 'Pending Review', color: '#eab308' },
    REJECTED: { label: 'Rejected', color: '#ef4444' },
  };
  const cfg = map[status] ?? { label: status, color: 'var(--txt-dim)' };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, color: cfg.color }}>
      {status === 'VERIFIED' && <CheckCircle size={12} />}
      {status === 'PENDING_VERIFICATION' && <Clock size={12} />}
      {status === 'REJECTED' && <XCircle size={12} />}
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
      showToast('success', existing ? 'Document re-uploaded' : 'Document uploaded');
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
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 420, maxWidth: '94vw' }}>
        <h3 style={{ margin: '0 0 18px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{existing ? 'Re-upload' : 'Upload'}: {name}</h3>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>File *</label>
            <input ref={fileRef} type="file" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx" required
              style={{ width: '100%', padding: '8px 10px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 13 }} />
          </div>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 18 }}>
            <div>
              <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Issue Date</label>
              <input type="date" value={issueDate} onChange={e => setIssueDate(e.target.value)}
                style={{ width: '100%', padding: '8px 10px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 13 }} />
            </div>
            <div>
              <label style={{ fontSize: 12, color: 'var(--txt-dim)', display: 'block', marginBottom: 5 }}>Expiry Date {requiresExpiry ? '*' : ''}</label>
              <input type="date" value={expiryDate} onChange={e => setExpiryDate(e.target.value)} required={requiresExpiry}
                min={issueDate || undefined}
                style={{ width: '100%', padding: '8px 10px', background: 'var(--shell)', border: `1px solid ${dateError ? '#ef4444' : 'var(--line)'}`, borderRadius: 6, color: 'var(--txt)', fontSize: 13 }} />
            </div>
          </div>
          {dateError && (
            <p style={{ margin: '-10px 0 14px', fontSize: 12, color: '#ef4444' }}>{dateError}</p>
          )}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading}
              style={{ padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>
              Cancel
            </button>
            <button type="submit" disabled={loading || !!dateError}
              style={{ padding: '8px 20px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: loading || dateError ? 'not-allowed' : 'pointer', fontSize: 13, fontWeight: 600, opacity: loading || dateError ? .7 : 1 }}>
              {loading ? 'Uploading…' : 'Upload'}
            </button>
          </div>
        </form>
      </div>
    </div>
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
    <button onClick={open} disabled={loading}
      style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 5, color: 'var(--txt)', cursor: 'pointer', fontSize: 12 }}>
      <Eye size={12} /> {loading ? '…' : 'View'}
    </button>
  );
}
