import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Download, FileText, X } from 'lucide-react';
import type { Attachment } from '../../api/helpContent';

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 550, padding: 16 };
// "Roughly 50% of the screen (responsive)" — clamp so it never collapses too narrow on small
// viewports or stretches unreasonably wide on large ones.
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: 'clamp(340px, 50vw, 760px)', maxWidth: '96vw', maxHeight: '88vh', display: 'flex', flexDirection: 'column', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };

const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif']);

function fmtSize(bytes: number | null) {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function extOf(fileName: string) {
  return fileName.split('.').pop()?.toLowerCase() ?? '';
}

/**
 * Shared attachment list + inline preview + Previous/Next navigation. Used by the FAQ/Guide
 * "View Attachments" indicator, the full ContentModal, and the Approval Center's review section
 * — one fetch-blob + preview mechanism instead of three separate viewers. Bytes only ever reach
 * the DOM via an authenticated blob fetch (never raw BYTEA/base64 inlined), so whatever
 * authorization gate `fetchBlob` enforces server-side is preserved end-to-end.
 */
export function AttachmentViewerModal({ title, attachments, fetchBlob, onClose }: {
  title: string;
  attachments: Attachment[];
  fetchBlob: (attachmentId: string) => Promise<Blob>;
  onClose: () => void;
}) {
  const [index, setIndex] = useState(0);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState(false);
  const active = attachments[index] ?? null;

  useEffect(() => {
    if (!active) { setPreviewUrl(null); setPreviewError(false); return; }
    setPreviewError(false);
    let objectUrl: string | null = null;
    let cancelled = false;
    fetchBlob(active.id).then(blob => {
      if (cancelled) return;
      objectUrl = URL.createObjectURL(blob);
      setPreviewUrl(objectUrl);
    }).catch(() => { if (!cancelled) setPreviewError(true); });
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [active?.id]);

  function goPrev() { setIndex(i => Math.max(0, i - 1)); }
  function goNext() { setIndex(i => Math.min(attachments.length - 1, i + 1)); }

  function download() {
    if (!previewUrl || !active) return;
    const a = document.createElement('a');
    a.href = previewUrl; a.download = active.fileName;
    a.click();
  }

  const ext = active ? extOf(active.fileName) : '';
  const isImage = IMAGE_EXTENSIONS.has(ext);
  const isPdf = ext === 'pdf';
  const previewable = isImage || isPdf;

  return (
    <div style={overlayStyle} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)', flexShrink: 0 }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{title}</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', flexShrink: 0 }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20, overflowY: 'auto', flex: 1, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {attachments.length === 0 && (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No attachments.</div>
          )}

          {attachments.length > 0 && active && (
            <>
              {/* Position + Previous/Next — required even when the list below is also shown */}
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{active.fileName}</div>
                  <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2 }}>
                    {attachments.length > 1 ? `${index + 1} of ${attachments.length}` : fmtSize(active.fileSize)}
                    {attachments.length > 1 && active.fileSize != null && <> · {fmtSize(active.fileSize)}</>}
                  </div>
                </div>
                {attachments.length > 1 && (
                  <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                    <button onClick={goPrev} disabled={index === 0} aria-label="Previous attachment" style={{ display: 'flex', alignItems: 'center', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 8px', color: 'var(--txt-mut)', cursor: index === 0 ? 'not-allowed' : 'pointer', opacity: index === 0 ? 0.5 : 1 }}>
                      <ChevronLeft size={14} />
                    </button>
                    <button onClick={goNext} disabled={index === attachments.length - 1} aria-label="Next attachment" style={{ display: 'flex', alignItems: 'center', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 8px', color: 'var(--txt-mut)', cursor: index === attachments.length - 1 ? 'not-allowed' : 'pointer', opacity: index === attachments.length - 1 ? 0.5 : 1 }}>
                      <ChevronRight size={14} />
                    </button>
                  </div>
                )}
              </div>

              {/* Preview */}
              <div style={{ flex: 1, minHeight: isPdf ? 420 : undefined, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: previewable ? 'flex-start' : 'center' }}>
                {previewUrl && isPdf && (
                  <iframe src={previewUrl} title={active.fileName} style={{ width: '100%', height: 420, border: '1px solid var(--line)', borderRadius: 8 }} />
                )}
                {previewUrl && isImage && (
                  <img src={previewUrl} alt={active.fileName} style={{ maxWidth: '100%', maxHeight: 420, borderRadius: 8, border: '1px solid var(--line)', objectFit: 'contain' }} />
                )}
                {!previewable && !previewError && (
                  <div style={{ padding: '28px 18px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5, background: 'var(--raised)', border: '1px dashed var(--line2)', borderRadius: 8, width: '100%' }}>
                    Preview isn't available for this file type in the browser — download it to view.
                  </div>
                )}
                {previewError && (
                  <div style={{ padding: '20px 18px', textAlign: 'center', color: 'var(--risk)', fontSize: 12.5, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, width: '100%' }}>
                    Couldn't load a preview. You can still try downloading the file.
                  </div>
                )}
              </div>

              <button
                onClick={download}
                disabled={!previewUrl}
                style={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 13px', fontSize: 12.5, color: 'var(--txt-mut)', cursor: previewUrl ? 'pointer' : 'not-allowed', opacity: previewUrl ? 1 : 0.6 }}
              >
                <Download size={13} /> Download{previewUrl ? '' : '…'}
              </button>
            </>
          )}

          {/* Simple pagination list — lets the user jump directly to a specific attachment */}
          {attachments.length > 1 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, borderTop: '1px solid var(--line)', paddingTop: 12 }}>
              {attachments.map((a, i) => (
                <button key={a.id} onClick={() => setIndex(i)} style={{
                  display: 'flex', alignItems: 'center', gap: 10, width: '100%', textAlign: 'left',
                  background: i === index ? 'var(--raised)' : 'none', border: '1px solid var(--line2)',
                  borderRadius: 7, padding: '8px 12px', cursor: 'pointer', color: 'var(--txt)',
                }}>
                  <FileText size={14} style={{ color: 'var(--brand)', flexShrink: 0 }} />
                  <span style={{ fontSize: 11, color: 'var(--txt-dim)', flexShrink: 0, fontWeight: 700 }}>{i + 1}.</span>
                  <span style={{ flex: 1, fontSize: 12.5, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.fileName}</span>
                  <span style={{ fontSize: 11, color: 'var(--txt-dim)', flexShrink: 0 }}>{fmtSize(a.fileSize)}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
