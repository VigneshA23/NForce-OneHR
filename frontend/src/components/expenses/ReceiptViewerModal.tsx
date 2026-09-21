import { useEffect, useState } from 'react';
import { Download, FileWarning, X } from 'lucide-react';
import { expensesApi } from '../../api/expenses';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 550, padding: 16 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: 'clamp(340px, 50vw, 760px)', maxWidth: '96vw', maxHeight: '88vh', display: 'flex', flexDirection: 'column', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };

/**
 * Fetches the receipt via the secure, role-checked backend endpoint (ExpenseService#getReceipt)
 * as an authenticated blob — never a raw data: URI baked into a list response — so whatever
 * authorization the backend enforces (owner / current manager / HR_ADMIN / SUPER_ADMIN) is what
 * actually gates viewing, regardless of which page renders the "View Receipt" button. Mirrors
 * AttachmentViewerModal's fetch-blob → object-URL → inline preview pattern.
 */
export function ReceiptViewerModal({ claimId, token, onClose }: {
  claimId: string;
  token: string;
  onClose: () => void;
}) {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [contentType, setContentType] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    setLoading(true);
    setError(null);
    expensesApi.getReceipt(claimId, token)
      .then(blob => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setPreviewUrl(objectUrl);
        setContentType(blob.type);
      })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load receipt'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [claimId, token]);

  function download() {
    if (!previewUrl) return;
    const a = document.createElement('a');
    a.href = previewUrl;
    a.download = `receipt-${claimId}`;
    a.click();
  }

  const isImage = !!contentType?.startsWith('image/');
  const isPdf = contentType === 'application/pdf';

  return (
    <div style={overlayStyle} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)', flexShrink: 0 }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>Receipt</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20, overflowY: 'auto', flex: 1, display: 'flex', flexDirection: 'column', gap: 14, alignItems: 'center', justifyContent: loading || error ? 'center' : 'flex-start' }}>
          {loading && (
            <div style={{ padding: 24, color: 'var(--txt-dim)', fontSize: 13 }}>Loading receipt…</div>
          )}

          {!loading && error && (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '28px 18px', textAlign: 'center' }}>
              <FileWarning size={28} style={{ color: 'var(--txt-dim)' }} />
              {/* Covers both "No receipt attached to this claim" (404) and an access-denied
                  message — the backend's own wording is shown verbatim rather than re-worded, so
                  it never drifts from what the API actually enforces. */}
              <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>{error}</div>
            </div>
          )}

          {!loading && !error && previewUrl && (
            <>
              <div style={{ width: '100%', minHeight: isPdf ? 420 : undefined, display: 'flex', justifyContent: 'center' }}>
                {isPdf && (
                  <iframe src={previewUrl} title="Receipt" style={{ width: '100%', height: 420, border: '1px solid var(--line)', borderRadius: 8 }} />
                )}
                {isImage && (
                  <img src={previewUrl} alt="Receipt" style={{ maxWidth: '100%', maxHeight: 420, borderRadius: 8, border: '1px solid var(--line)', objectFit: 'contain' }} />
                )}
                {!isPdf && !isImage && (
                  <div style={{ padding: '28px 18px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5, background: 'var(--raised)', border: '1px dashed var(--line2)', borderRadius: 8, width: '100%' }}>
                    Preview isn't available for this file type in the browser — download it to view.
                  </div>
                )}
              </div>
              <button
                onClick={download}
                style={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 13px', fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}
              >
                <Download size={13} /> Download
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
