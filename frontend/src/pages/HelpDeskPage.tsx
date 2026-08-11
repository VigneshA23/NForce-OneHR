import { useEffect, useRef, useState } from 'react';
import type { LucideIcon } from 'lucide-react';
import {
  BookOpen, CheckCircle2, ChevronDown, Download, FileText, FolderOpen, HelpCircle, Paperclip,
  Plus, Search, Send, X,
} from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  helpdeskApi,
  type HelpdeskCategory,
  type ReplyItem,
  type TicketDetail,
  type TicketStatus,
  type TicketSummary,
} from '../api/helpdesk';
import {
  helpContentApi,
  hrHelpContentApi,
  type HelpContentDetail,
  type HelpContentSummary,
  type HelpContentType,
} from '../api/helpContent';
import { ContentFormModal, StatusChip } from '../components/helpContent/ContentFormModal';

// Same overlay/modal/input/label/table constants used across LeavePage, MyRequestsPage,
// EmployeeMasterPage etc. — this codebase has no shared component library, every page
// re-declares these locally, and Help & Guidance follows the same convention.
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

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

const STATUS_COLORS: Record<TicketStatus, { bg: string; color: string }> = {
  OPEN: { bg: 'rgba(245,158,11,.15)', color: '#F59E0B' },
  IN_PROGRESS: { bg: 'rgba(99,102,241,.18)', color: '#818CF8' },
  RESOLVED: { bg: 'rgba(16,185,129,.15)', color: '#10B981' },
  CLOSED: { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' },
};

function StatusBadge({ status }: { status: TicketStatus }) {
  const s = STATUS_COLORS[status] ?? { bg: 'rgba(107,114,128,.15)', color: '#9CA3AF' };
  return (
    <span style={{ fontSize: 10.5, fontWeight: 700, padding: '3px 8px', borderRadius: 20, background: s.bg, color: s.color, whiteSpace: 'nowrap' }}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}

function fmtDateTime(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

// ── Contact HR Support modal (ticket creation) — unchanged ─

function ContactHRModal({ categories, token, onClose, onCreated }: {
  categories: HelpdeskCategory[]; token: string; onClose: () => void; onCreated: () => void;
}) {
  const { showToast } = useToast();
  const [categoryId, setCategoryId] = useState<number | ''>(categories[0]?.id ?? '');
  const [description, setDescription] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<TicketDetail | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!categoryId) { setError('Please select a topic.'); return; }
    if (!description.trim()) { setError('Please describe your request.'); return; }
    setSubmitting(true); setError(null);
    try {
      const ticket = await helpdeskApi.createTicket({ categoryId, description: description.trim() }, token);
      setCreated(ticket);
      showToast('success', `Ticket ${ticket.ticketNumber} submitted`);
      onCreated();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submit failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  if (created) {
    return (
      <div style={overlayStyle}>
        <div style={modalStyle}>
          <ModalHeader title="Contact HR Support" onClose={onClose} />
          <div style={{ padding: '32px 24px', textAlign: 'center' }}>
            <CheckCircle2 size={40} style={{ color: 'var(--ok)', marginBottom: 12 }} />
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 16, fontWeight: 700, color: 'var(--txt)', marginBottom: 4 }}>
              Request Submitted
            </div>
            <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 18 }}>
              HR has been notified. You can track progress under My Requests.
            </div>
            <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '14px 18px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
              <div style={{ textAlign: 'left' }}>
                <div style={labelStyle}>Ticket Number</div>
                <div style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>{created.ticketNumber}</div>
              </div>
              <StatusBadge status={created.status} />
            </div>
            <button onClick={onClose} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
              View My Requests
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title="Contact HR Support" onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <Field label="Topic *">
            <select style={inputStyle} value={categoryId} onChange={e => setCategoryId(Number(e.target.value))}>
              {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </Field>
          <Field label="Description *">
            <textarea
              style={{ ...inputStyle, minHeight: 110, resize: 'vertical', fontFamily: 'inherit' }}
              value={description}
              onChange={e => setDescription(e.target.value)}
              placeholder="Describe your request in detail…"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Submitting…' : 'Submit Request'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Conversation / reply thread — unchanged ────────────────

function ReplyBubble({ reply, token }: { reply: ReplyItem; token: string }) {
  const isHr = reply.senderRole === 'HR';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: isHr ? 'flex-start' : 'flex-end', marginBottom: 12 }}>
      <div style={{
        maxWidth: '80%', background: isHr ? 'var(--raised)' : 'rgba(177,17,22,.08)',
        border: `1px solid ${isHr ? 'var(--line)' : 'rgba(177,17,22,.25)'}`,
        borderRadius: 10, padding: '10px 14px',
      }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', marginBottom: 4 }}>
          {reply.senderName} <span style={{ color: 'var(--txt-dim)', fontWeight: 500 }}>· {isHr ? 'HR' : 'You'}</span>
        </div>
        <div style={{ fontSize: 13, color: 'var(--txt)', whiteSpace: 'pre-wrap' }}>{reply.message}</div>
        {reply.hasAttachment && (
          <button
            onClick={async () => {
              const blob = await helpdeskApi.downloadAttachment(reply.id, token);
              const url = URL.createObjectURL(blob);
              const a = document.createElement('a');
              a.href = url; a.download = reply.attachmentName ?? 'attachment';
              a.click();
              URL.revokeObjectURL(url);
            }}
            style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '4px 9px', fontSize: 11.5, color: 'var(--txt-mut)', cursor: 'pointer' }}
          >
            <Paperclip size={11} /> {reply.attachmentName}
          </button>
        )}
      </div>
      <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 3 }}>{fmtDateTime(reply.createdAt)}</div>
    </div>
  );
}

function TicketDetailView({ ticketId, token, onBack, onChanged }: {
  ticketId: string; token: string; onBack: () => void; onChanged: () => void;
}) {
  const { showToast } = useToast();
  const [ticket, setTicket] = useState<TicketDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState('');
  const [attachment, setAttachment] = useState<File | null>(null);
  const [sending, setSending] = useState(false);
  const [closing, setClosing] = useState(false);

  function load() {
    helpdeskApi.getTicket(ticketId, token).then(setTicket).finally(() => setLoading(false));
  }
  useEffect(load, [ticketId, token]);

  async function handleClose() {
    setClosing(true);
    try {
      await helpdeskApi.close(ticketId, token);
      showToast('success', 'Ticket closed');
      load();
      onChanged();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to close ticket');
    } finally { setClosing(false); }
  }

  async function handleReply(e: React.FormEvent) {
    e.preventDefault();
    if (!message.trim()) return;
    setSending(true);
    try {
      await helpdeskApi.reply(ticketId, message.trim(), attachment, token);
      setMessage(''); setAttachment(null);
      load();
      onChanged();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to send reply');
    } finally { setSending(false); }
  }

  if (loading || !ticket) {
    return <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>;
  }

  return (
    <div>
      <button onClick={onBack} style={{ background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 12.5, cursor: 'pointer', marginBottom: 14, padding: 0 }}>
        ← Back to My Requests
      </button>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 20, marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 }}>
          <div>
            <div style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 4 }}>{ticket.ticketNumber}</div>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{ticket.categoryName}</div>
          </div>
          <StatusBadge status={ticket.status} />
        </div>
        <div style={{ fontSize: 13, color: 'var(--txt-mut)', whiteSpace: 'pre-wrap', marginBottom: 10 }}>{ticket.description}</div>
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>
          Created {fmtDateTime(ticket.createdAt)} · Last updated {fmtDateTime(ticket.updatedAt)}
          {ticket.resolvedAt && <> · Resolved {fmtDateTime(ticket.resolvedAt)}</>}
        </div>
      </div>

      <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Conversation</div>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 16, marginBottom: 16, maxHeight: 360, overflowY: 'auto' }}>
        {ticket.replies.length === 0 ? (
          <div style={{ fontSize: 13, color: 'var(--txt-dim)', textAlign: 'center', padding: 20 }}>No replies yet.</div>
        ) : (
          ticket.replies.map(r => <ReplyBubble key={r.id} reply={r} token={token} />)
        )}
      </div>

      {ticket.status === 'RESOLVED' && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12,
          background: 'rgba(16,185,129,.08)', border: '1px solid rgba(16,185,129,.25)', borderRadius: 8, padding: '12px 16px', marginBottom: 16,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
            <CheckCircle2 size={16} style={{ color: 'var(--ok)', flexShrink: 0 }} />
            <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
              HR marked this resolved. If everything looks good, close it — closed tickets can't be reopened.
            </span>
          </div>
          <button onClick={handleClose} disabled={closing} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 15px', fontSize: 12.5, fontWeight: 600, cursor: closing ? 'not-allowed' : 'pointer', opacity: closing ? 0.7 : 1, whiteSpace: 'nowrap' }}>
            {closing ? 'Closing…' : 'Close Ticket'}
          </button>
        </div>
      )}

      {ticket.status !== 'CLOSED' ? (
        <form onSubmit={handleReply} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <textarea
            style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
            placeholder="Type a reply…"
            value={message}
            onChange={e => setMessage(e.target.value)}
          />
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
              <Paperclip size={13} />
              {attachment ? attachment.name : 'Attach a file'}
              <input type="file" style={{ display: 'none' }} onChange={e => setAttachment(e.target.files?.[0] ?? null)} />
            </label>
            <button type="submit" disabled={sending || !message.trim()} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '8px 16px', fontSize: 13, fontWeight: 600, cursor: sending ? 'not-allowed' : 'pointer', opacity: sending ? 0.7 : 1 }}>
              <Send size={13} /> {sending ? 'Sending…' : 'Reply'}
            </button>
          </div>
        </form>
      ) : (
        <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', textAlign: 'center' }}>This ticket is closed and cannot be reopened.</div>
      )}
    </div>
  );
}

// ── Help & Guidance content: FAQ / Quick Help / Guide / Document ──
// Backed by /api/help-content — HR-managed, published content. Employees are read-only here;
// see HrHelpContentController for the management surface.

const CONTENT_TYPE_ICON: Record<HelpContentType, LucideIcon> = {
  FAQ: HelpCircle,
  QUICK_HELP: FileText,
  GUIDE: BookOpen,
  DOCUMENT: FolderOpen,
};

const CONTENT_TYPE_LABEL: Record<HelpContentType, string> = {
  FAQ: 'FAQ',
  QUICK_HELP: 'Quick Help',
  GUIDE: 'Guide',
  DOCUMENT: 'Document',
};

// Inline management controls (HR Admin/Super Admin only) — deliberately plain text links, not
// icon buttons or a toolbar, so Help & Guidance keeps reading as a help center with a couple of
// extra actions rather than turning into a separate admin dashboard.
const adminLinkStyle: React.CSSProperties = { background: 'none', border: 'none', color: 'var(--brand)', fontSize: 11.5, fontWeight: 600, cursor: 'pointer', padding: 0 };
const adminLinkMutedStyle: React.CSSProperties = { ...adminLinkStyle, color: 'var(--txt-mut)' };
const addContentBtnStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: '1px solid var(--line2)', color: 'var(--brand)', fontSize: 12, fontWeight: 600, padding: '5px 10px', borderRadius: 6, cursor: 'pointer', whiteSpace: 'nowrap' };

/** Edit/Archive links + Draft/Archived chips shown under an FAQ or guide row, admins only. */
function AdminItemControls({ item, onEdit, onArchiveToggle }: {
  item: HelpContentSummary; onEdit: (item: HelpContentSummary) => void; onArchiveToggle: (item: HelpContentSummary) => void;
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
      {!item.published && <StatusChip label="Draft" tone="warn" />}
      {!item.active && <StatusChip label="Archived" tone="dim" />}
      <button onClick={() => onEdit(item)} style={adminLinkStyle}>Edit</button>
      <span style={{ color: 'var(--line2)', fontSize: 11 }}>|</span>
      <button onClick={() => onArchiveToggle(item)} style={adminLinkMutedStyle}>{item.active ? 'Archive' : 'Unarchive'}</button>
    </div>
  );
}

function SectionHeader({ title, description, action }: { title: string; description?: string; action?: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 14, display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
      <div>
        <h2 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 16, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>{title}</h2>
        {description && <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 4, marginBottom: 0 }}>{description}</p>}
      </div>
      {action}
    </div>
  );
}

/**
 * One row in the Quick Help & Guides list — deliberately a row, not a card, so this column
 * reads with the same rhythm as the FAQ accordion sitting next to it.
 */
function GuideListItem({ item, onOpen, isAdmin, onEdit, onArchiveToggle }: {
  item: HelpContentSummary; onOpen: () => void;
  isAdmin?: boolean; onEdit?: (item: HelpContentSummary) => void; onArchiveToggle?: (item: HelpContentSummary) => void;
}) {
  const Icon = CONTENT_TYPE_ICON[item.type];
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, padding: '14px 18px' }}>
      <Icon size={16} style={{ color: 'var(--brand)', flexShrink: 0, marginTop: 2 }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{item.title}</div>
        {item.description && <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3 }}>{item.description}</div>}
        {isAdmin && onEdit && onArchiveToggle && (
          <div style={{ marginTop: 7 }}><AdminItemControls item={item} onEdit={onEdit} onArchiveToggle={onArchiveToggle} /></div>
        )}
      </div>
      <button
        onClick={onOpen}
        style={{ alignSelf: 'center', background: 'none', border: '1px solid var(--line2)', color: 'var(--brand)', fontSize: 12, fontWeight: 600, padding: '5px 11px', borderRadius: 6, cursor: 'pointer', flexShrink: 0, whiteSpace: 'nowrap' }}
      >
        Open
      </button>
    </div>
  );
}

function GuideList({ items, onOpen, isAdmin, onEdit, onArchiveToggle }: {
  items: HelpContentSummary[]; onOpen: (id: string) => void;
  isAdmin?: boolean; onEdit?: (item: HelpContentSummary) => void; onArchiveToggle?: (item: HelpContentSummary) => void;
}) {
  if (items.length === 0) {
    return <div style={{ padding: 24, textAlign: 'center', fontSize: 13, color: 'var(--txt-dim)' }}>No guides published yet.</div>;
  }
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
      {items.map((item, i) => (
        <div key={item.id} style={{ borderBottom: i < items.length - 1 ? '1px solid var(--line)' : 'none' }}>
          <GuideListItem item={item} onOpen={() => onOpen(item.id)} isAdmin={isAdmin} onEdit={onEdit} onArchiveToggle={onArchiveToggle} />
        </div>
      ))}
    </div>
  );
}

/** Full-content view for any content item — fetches its own detail and, if present, previews/downloads its attachment. */
function ContentModal({ id, token, onClose, onContactHR }: {
  id: string; token: string; onClose: () => void; onContactHR: () => void;
}) {
  const [item, setItem] = useState<HelpContentDetail | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  useEffect(() => {
    helpContentApi.getOne(id, token).then(setItem);
    helpContentApi.trackView(id, token);
  }, [id, token]);

  useEffect(() => {
    if (!item?.hasAttachment) return;
    let objectUrl: string | null = null;
    helpContentApi.downloadAttachment(id, token).then(blob => {
      objectUrl = URL.createObjectURL(blob);
      setPreviewUrl(objectUrl);
    }).catch(() => { /* preview is best-effort; download button still won't render without a blob */ });
    return () => { if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [item?.hasAttachment, id, token]);

  function download() {
    if (!previewUrl || !item) return;
    const a = document.createElement('a');
    a.href = previewUrl; a.download = item.attachmentName ?? 'download';
    a.click();
  }

  if (!item) {
    return (
      <div style={overlayStyle}>
        <div style={modalStyle}><div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div></div>
      </div>
    );
  }

  const ext = (item.attachmentName ?? '').split('.').pop()?.toLowerCase();
  const isImage = ext === 'png' || ext === 'jpg' || ext === 'jpeg';
  const isPdf = ext === 'pdf';

  return (
    <div style={overlayStyle} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ ...modalStyle, maxWidth: 620 }}>
        <ModalHeader title={item.title} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {item.description && <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 0, marginBottom: 14 }}>{item.description}</p>}
          {item.body && <div style={{ fontSize: 13, color: 'var(--txt)', whiteSpace: 'pre-wrap', lineHeight: 1.6, marginBottom: 16 }}>{item.body}</div>}

          {item.hasAttachment && (
            <div style={{ marginBottom: 16 }}>
              {previewUrl && isPdf && (
                <iframe src={previewUrl} title={item.attachmentName ?? 'preview'} style={{ width: '100%', height: 380, border: '1px solid var(--line)', borderRadius: 8 }} />
              )}
              {previewUrl && isImage && (
                <img src={previewUrl} alt={item.attachmentName ?? ''} style={{ maxWidth: '100%', borderRadius: 8, border: '1px solid var(--line)' }} />
              )}
              <button
                onClick={download}
                disabled={!previewUrl}
                style={{ marginTop: 10, display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)', cursor: previewUrl ? 'pointer' : 'not-allowed' }}
              >
                <Download size={13} /> {item.attachmentName ?? 'Download attachment'}
              </button>
            </div>
          )}

          {!item.body && !item.hasAttachment && (
            <div style={{ background: 'var(--raised)', border: '1px dashed var(--line2)', borderRadius: 8, padding: '28px 18px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13, marginBottom: 16 }}>
              Detailed content for this item is being finalized and will appear here soon.
            </div>
          )}

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
            <button onClick={onContactHR} style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
              Still need help? Contact HR Support →
            </button>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '8px 16px', fontSize: 13, cursor: 'pointer' }}>
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Answers render straight from the list payload's `description` — no per-item fetch needed. */
function FAQAccordion({ items, token, isAdmin, onEdit, onArchiveToggle }: {
  items: HelpContentSummary[]; token: string;
  isAdmin?: boolean; onEdit?: (item: HelpContentSummary) => void; onArchiveToggle?: (item: HelpContentSummary) => void;
}) {
  const [openId, setOpenId] = useState<string | null>(null);
  const viewedRef = useRef<Set<string>>(new Set());

  function toggle(item: HelpContentSummary) {
    const opening = openId !== item.id;
    setOpenId(opening ? item.id : null);
    if (opening && !viewedRef.current.has(item.id)) {
      viewedRef.current.add(item.id);
      helpContentApi.trackView(item.id, token);
    }
  }

  if (items.length === 0) {
    return <div style={{ padding: 24, textAlign: 'center', fontSize: 13, color: 'var(--txt-dim)' }}>No FAQs published yet.</div>;
  }

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
      {items.map((faq, i) => {
        const isOpen = openId === faq.id;
        return (
          <div key={faq.id} style={{ borderBottom: i < items.length - 1 ? '1px solid var(--line)' : 'none' }}>
            <button
              onClick={() => toggle(faq)}
              aria-expanded={isOpen}
              style={{
                width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
                padding: '14px 18px', background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left',
                fontSize: 13.5, fontWeight: 600, color: 'var(--txt)', fontFamily: 'inherit',
              }}
            >
              {faq.title}
              <ChevronDown size={16} style={{ color: 'var(--txt-dim)', flexShrink: 0, transform: isOpen ? 'rotate(180deg)' : 'none', transition: 'transform .15s' }} />
            </button>
            {isOpen && (
              <div style={{ padding: '0 18px 16px', fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.6 }}>
                {faq.description || 'No further detail available.'}
              </div>
            )}
            {isAdmin && onEdit && onArchiveToggle && (
              <div style={{ padding: '0 18px 12px' }}><AdminItemControls item={faq} onEdit={onEdit} onArchiveToggle={onArchiveToggle} /></div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function AllFaqsModal({ token, isAdmin, refreshToken, onEdit, onArchiveToggle, onClose }: {
  token: string; isAdmin: boolean; refreshToken: number;
  onEdit?: (item: HelpContentSummary) => void; onArchiveToggle?: (item: HelpContentSummary) => void; onClose: () => void;
}) {
  const [items, setItems] = useState<HelpContentSummary[] | null>(null);
  useEffect(() => {
    const call = isAdmin ? hrHelpContentApi.list(token, { type: 'FAQ', size: 100 }) : helpContentApi.list(token, { type: 'FAQ', size: 100 });
    call.then(res => setItems(res.content));
  }, [token, isAdmin, refreshToken]);
  return (
    <div style={overlayStyle} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ ...modalStyle, maxWidth: 620 }}>
        <ModalHeader title="All FAQs" onClose={onClose} />
        <div style={{ padding: 24, maxHeight: '70vh', overflowY: 'auto' }}>
          {items === null ? (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
          ) : (
            <FAQAccordion items={items} token={token} isAdmin={isAdmin} onEdit={onEdit} onArchiveToggle={onArchiveToggle} />
          )}
        </div>
      </div>
    </div>
  );
}

function AllGuidesModal({ token, isAdmin, refreshToken, onOpenItem, onEdit, onArchiveToggle, onClose }: {
  token: string; isAdmin: boolean; refreshToken: number; onOpenItem: (id: string) => void;
  onEdit?: (item: HelpContentSummary) => void; onArchiveToggle?: (item: HelpContentSummary) => void; onClose: () => void;
}) {
  const [items, setItems] = useState<HelpContentSummary[] | null>(null);
  useEffect(() => {
    const call = isAdmin ? hrHelpContentApi.list(token, { size: 100 }) : helpContentApi.list(token, { size: 100 });
    call.then(res => setItems(res.content.filter(c => c.type !== 'FAQ')));
  }, [token, isAdmin, refreshToken]);
  return (
    <div style={overlayStyle} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ ...modalStyle, maxWidth: 620 }}>
        <ModalHeader title="All Guides & Quick Help" onClose={onClose} />
        <div style={{ padding: 24, maxHeight: '70vh', overflowY: 'auto' }}>
          {items === null ? (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
          ) : (
            <GuideList items={items} onOpen={onOpenItem} isAdmin={isAdmin} onEdit={onEdit} onArchiveToggle={onArchiveToggle} />
          )}
        </div>
      </div>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────

const STATUS_FILTERS: Array<TicketStatus | 'ALL'> = ['ALL', 'OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];

export default function HelpDeskPage() {
  const token = useAuthStore(s => s.token)!;
  const user = useAuthStore(s => s.user);
  const { showToast } = useToast();
  // HR_ADMIN/SUPER_ADMIN see the same page as employees, plus inline content-management
  // controls — matching the ADMIN_ROLES check already authoritative server-side in
  // HelpdeskService/HrHelpContentController. Hiding these controls is presentation only;
  // every mutating call still goes through the same @PreAuthorize-guarded HR endpoints.
  const isAdmin = user?.role === 'HR_ADMIN' || user?.role === 'SUPER_ADMIN';

  const [categories, setCategories] = useState<HelpdeskCategory[]>([]);
  const [showContactModal, setShowContactModal] = useState(false);
  const [activeContentId, setActiveContentId] = useState<string | null>(null);
  const [showAllFaqs, setShowAllFaqs] = useState(false);
  const [showAllGuides, setShowAllGuides] = useState(false);

  // Curated Help & Guidance content (FAQ/Quick Help/Guide/Document), split client-side.
  // Employees only ever see published+active content; admins see everything (incl. drafts and
  // archived items) so they can manage the full catalog from this same page.
  const [allContent, setAllContent] = useState<HelpContentSummary[]>([]);
  const [contentVersion, setContentVersion] = useState(0);
  const faqs = allContent.filter(c => c.type === 'FAQ');
  const guides = allContent.filter(c => c.type !== 'FAQ');

  const [formOpen, setFormOpen] = useState(false);
  const [formInitialType, setFormInitialType] = useState<HelpContentType>('FAQ');
  const [editingContent, setEditingContent] = useState<HelpContentDetail | null>(null);

  // Lightweight search across the same content, scoped to this page only.
  const [contentSearch, setContentSearch] = useState('');
  const [searchResults, setSearchResults] = useState<HelpContentSummary[]>([]);
  const [searching, setSearching] = useState(false);

  const [tickets, setTickets] = useState<TicketSummary[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<TicketStatus | 'ALL'>('ALL');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showAllRequests, setShowAllRequests] = useState(false);

  function loadTickets(p = page) {
    setLoading(true);
    const size = showAllRequests ? 10 : 5;
    helpdeskApi.listMine(token, {
      status: showAllRequests && statusFilter !== 'ALL' ? statusFilter : undefined,
      search: showAllRequests && search ? search : undefined,
      page: p, size,
    })
      .then(res => { setTickets(res.content); setTotalPages(res.totalPages); setPage(res.number); })
      .finally(() => setLoading(false));
  }

  function refreshContent() {
    const call = isAdmin
      ? hrHelpContentApi.list(token, { size: 100 })
      : helpContentApi.list(token, { sort: 'popular', size: 50 });
    call.then(res => setAllContent(res.content));
    // Bumped on every mutation so the "View all" modals (which fetch their own, larger page)
    // know to refetch too — they don't share state with this page's `allContent`.
    setContentVersion(v => v + 1);
  }

  useEffect(() => { helpdeskApi.listCategories(token).then(setCategories); }, [token]);
  useEffect(refreshContent, [token, isAdmin]);
  useEffect(() => { loadTickets(0); }, [token, statusFilter, search, showAllRequests]);

  function openCreateContent(type: HelpContentType) {
    setEditingContent(null);
    setFormInitialType(type);
    setFormOpen(true);
  }

  async function openEditContent(item: HelpContentSummary) {
    const detail = await hrHelpContentApi.getOne(item.id, token);
    setEditingContent(detail);
    setFormOpen(true);
  }

  async function handleArchiveToggle(item: HelpContentSummary) {
    try {
      await (item.active ? hrHelpContentApi.archive(item.id, token) : hrHelpContentApi.reactivate(item.id, token));
      showToast('success', item.active ? 'Archived' : 'Unarchived');
      refreshContent();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to update');
    }
  }

  // Debounced content search — fires 300ms after typing stops, clears back to the curated view when empty.
  useEffect(() => {
    const q = contentSearch.trim();
    if (!q) { setSearchResults([]); setSearching(false); return; }
    setSearching(true);
    const handle = setTimeout(() => {
      helpContentApi.list(token, { search: q, size: 20 }).then(res => setSearchResults(res.content)).finally(() => setSearching(false));
    }, 300);
    return () => clearTimeout(handle);
  }, [contentSearch, token]);

  function openContactFromModal() {
    setActiveContentId(null);
    setShowContactModal(true);
  }

  if (selectedId) {
    return (
      <div>
        <div style={{ marginBottom: 18 }}>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Help & Guidance</h1>
        </div>
        <TicketDetailView ticketId={selectedId} token={token} onBack={() => { setSelectedId(null); loadTickets(); }} onChanged={loadTickets} />
      </div>
    );
  }

  const visibleTickets = showAllRequests ? tickets : tickets.slice(0, 5);

  return (
    <div>
      {/* 1. Header */}
      <div style={{ marginBottom: 20 }}>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Help & Guidance</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4, maxWidth: 640 }}>
          Find an answer yourself, or reach HR directly — all from one place.
        </p>
      </div>

      {/* 2. Hero row: search + Contact HR Support — both visible without scrolling */}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 32 }}>
        <div style={{ position: 'relative', flex: '1 1 320px', minWidth: 220 }}>
          <Search size={14} style={{ position: 'absolute', left: 13, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input
            placeholder="Search FAQs, guides, and quick help…"
            value={contentSearch}
            onChange={e => setContentSearch(e.target.value)}
            style={{ width: '100%', boxSizing: 'border-box', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '11px 14px 11px 34px', color: 'var(--txt)', fontSize: 13.5, outline: 'none' }}
          />
        </div>
        <button onClick={() => setShowContactModal(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '0 20px', fontSize: 13.5, fontWeight: 700, cursor: 'pointer', whiteSpace: 'nowrap' }}>
          <HelpCircle size={15} /> Contact HR Support
        </button>
      </div>

      {contentSearch.trim() ? (
        /* 3a. Search results — replaces the curated view while a query is active */
        <div style={{ marginBottom: 32 }}>
          <SectionHeader
            title={`Results for "${contentSearch.trim()}"`}
            description={searching ? 'Searching…' : `${searchResults.length} match${searchResults.length === 1 ? '' : 'es'}`}
          />
          {!searching && searchResults.length === 0 ? (
            <div style={{ padding: 32, textAlign: 'center', fontSize: 13, color: 'var(--txt-dim)', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10 }}>
              No matches. Try different words, or{' '}
              <button onClick={() => setShowContactModal(true)} style={{ background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0, font: 'inherit' }}>contact HR</button>.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {searchResults.map(r => {
                const Icon = CONTENT_TYPE_ICON[r.type];
                return (
                  <button key={r.id} onClick={() => setActiveContentId(r.id)} style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', textAlign: 'left', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px', cursor: 'pointer' }}>
                    <Icon size={15} style={{ color: 'var(--brand)', flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{r.title}</div>
                      {r.description && <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.description}</div>}
                    </div>
                    <span style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em', flexShrink: 0 }}>{CONTENT_TYPE_LABEL[r.type]}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      ) : (
        /* 3b. FAQ + Quick Help & Guides — side by side on desktop, stacking only when the
           viewport is too narrow for both (see minmax below), per the required layout. */
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 24, marginBottom: 32, alignItems: 'start' }}>
          <div>
            <SectionHeader
              title="Frequently Asked Questions"
              description="Quick answers to the questions HR hears most."
              action={
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                  {isAdmin && (
                    <button onClick={() => openCreateContent('FAQ')} style={addContentBtnStyle}><Plus size={12} /> Add FAQ</button>
                  )}
                  {faqs.length > 0 && (
                    <button onClick={() => setShowAllFaqs(true)} style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
                      View all →
                    </button>
                  )}
                </div>
              }
            />
            <FAQAccordion items={faqs.slice(0, 5)} token={token} isAdmin={isAdmin} onEdit={openEditContent} onArchiveToggle={handleArchiveToggle} />
          </div>

          <div>
            <SectionHeader
              title="Quick Help & Guides"
              description="Guides, policies, and documents curated by HR."
              action={
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                  {isAdmin && (
                    <button onClick={() => openCreateContent('GUIDE')} style={addContentBtnStyle}><Plus size={12} /> Add Guide</button>
                  )}
                  {guides.length > 0 && (
                    <button onClick={() => setShowAllGuides(true)} style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
                      View all →
                    </button>
                  )}
                </div>
              }
            />
            <GuideList items={guides.slice(0, 5)} onOpen={id => setActiveContentId(id)} isAdmin={isAdmin} onEdit={openEditContent} onArchiveToggle={handleArchiveToggle} />
          </div>
        </div>
      )}

      {/* 5. My Requests — compact by default, "View all" reveals search/filter/pagination */}
      <div style={{ marginBottom: 18 }}>
        <SectionHeader
          title="My Requests"
          action={!showAllRequests && tickets.length > 0 && (
            <button onClick={() => setShowAllRequests(true)} style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0 }}>
              View all requests →
            </button>
          )}
        />

        {showAllRequests && (
          <>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
              <input
                placeholder="Search ticket number or description…"
                value={search}
                onChange={e => setSearch(e.target.value)}
                style={{ flex: '1 1 240px', minWidth: 200, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 12px', color: 'var(--txt)', fontSize: 13, outline: 'none' }}
              />
            </div>
            <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
              {STATUS_FILTERS.map(s => (
                <button key={s} onClick={() => setStatusFilter(s)} style={{
                  padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600, cursor: 'pointer', border: 'none',
                  background: statusFilter === s ? 'var(--brand)' : 'var(--raised)',
                  color: statusFilter === s ? '#fff' : 'var(--txt-mut)',
                }}>
                  {s === 'ALL' ? 'All' : s.replace(/_/g, ' ')}
                </button>
              ))}
            </div>
          </>
        )}

        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          {loading ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
          ) : visibleTickets.length === 0 ? (
            <div style={{ padding: 48, textAlign: 'center' }}>
              <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>
                {statusFilter === 'ALL' && !search ? "You haven't raised any requests yet." : 'No matching requests.'}
              </div>
              <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Use "Contact HR Support" above to raise your first request.</div>
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Ticket Number', 'Topic', 'Status', 'Last Updated'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {visibleTickets.map(t => (
                    <tr key={t.id} style={{ cursor: 'pointer' }} onClick={() => setSelectedId(t.id)}>
                      <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace', color: 'var(--txt)', fontWeight: 600 }}>{t.ticketNumber}</td>
                      <td style={tdStyle}>{t.categoryName}</td>
                      <td style={tdStyle}><StatusBadge status={t.status} /></td>
                      <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDateTime(t.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {showAllRequests && totalPages > 1 && (
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 12, padding: '12px 0', borderTop: '1px solid var(--line)' }}>
              <button onClick={() => loadTickets(page - 1)} disabled={page === 0} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)', cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? 0.5 : 1 }}>← Prev</button>
              <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Page {page + 1} of {totalPages}</span>
              <button onClick={() => loadTickets(page + 1)} disabled={page >= totalPages - 1} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)', cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page >= totalPages - 1 ? 0.5 : 1 }}>Next →</button>
            </div>
          )}
        </div>
      </div>

      {showContactModal && (
        <ContactHRModal
          categories={categories}
          token={token}
          onClose={() => { setShowContactModal(false); loadTickets(0); }}
          onCreated={() => loadTickets(0)}
        />
      )}

      {activeContentId && (
        <ContentModal
          id={activeContentId}
          token={token}
          onClose={() => setActiveContentId(null)}
          onContactHR={openContactFromModal}
        />
      )}

      {showAllFaqs && (
        <AllFaqsModal
          token={token}
          isAdmin={isAdmin}
          refreshToken={contentVersion}
          onEdit={isAdmin ? openEditContent : undefined}
          onArchiveToggle={isAdmin ? handleArchiveToggle : undefined}
          onClose={() => setShowAllFaqs(false)}
        />
      )}

      {showAllGuides && (
        <AllGuidesModal
          token={token}
          isAdmin={isAdmin}
          refreshToken={contentVersion}
          onOpenItem={id => { setShowAllGuides(false); setActiveContentId(id); }}
          onEdit={isAdmin ? openEditContent : undefined}
          onArchiveToggle={isAdmin ? handleArchiveToggle : undefined}
          onClose={() => setShowAllGuides(false)}
        />
      )}

      {formOpen && (
        <ContentFormModal
          editing={editingContent}
          initialType={formInitialType}
          token={token}
          onClose={() => setFormOpen(false)}
          onSaved={refreshContent}
        />
      )}
    </div>
  );
}
