import { useEffect, useState } from 'react';
import type { LucideIcon } from 'lucide-react';
import {
  BookOpen, Calendar, CalendarDays, CheckCircle2, ChevronDown, ClipboardList, Clock, FileText,
  FolderOpen, Gift, GraduationCap, Heart, HelpCircle, Paperclip, Phone, Plane, Send, User, Users,
  Wallet, X,
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
  ASSIGNED: { bg: 'rgba(76,141,214,.15)', color: '#4C8DD6' },
  IN_PROGRESS: { bg: 'rgba(99,102,241,.18)', color: '#818CF8' },
  WAITING_FOR_EMPLOYEE: { bg: 'rgba(224,169,59,.18)', color: '#E0A93B' },
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

  function load() {
    helpdeskApi.getTicket(ticketId, token).then(setTicket).finally(() => setLoading(false));
  }
  useEffect(load, [ticketId, token]);

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
        <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', textAlign: 'center' }}>This ticket is closed.</div>
      )}
    </div>
  );
}

// ── NEW: self-service knowledge-hub building blocks ────────
// Everything below is presentational + local config. No API calls, no new routes,
// no changes to the Help Desk data model — placeholder content only, replaceable later
// by editing the GUIDES/RESOURCES/FAQS arrays.

interface GuideItem {
  icon: LucideIcon;
  title: string;
  description: string;
}

const GUIDES: GuideItem[] = [
  { icon: BookOpen,       title: 'Employee Handbook',     description: 'Company culture, code of conduct, and everything you need to know as a OneHR employee.' },
  { icon: Calendar,       title: 'Leave Policy',           description: 'Leave types, accrual rules, and how to plan your time off.' },
  { icon: Clock,          title: 'Attendance Guidelines',  description: 'Shift timings, regularization rules, and how attendance is tracked.' },
  { icon: Heart,          title: 'Benefits',                description: 'Health insurance, wellness programs, and other employee benefits.' },
  { icon: Wallet,         title: 'Payroll Information',    description: 'Salary structure, payslips, tax deductions, and payment schedules.' },
  { icon: Plane,          title: 'Travel Policy',          description: 'Booking process, reimbursement rules, and travel allowances.' },
  { icon: FileText,       title: 'Company Policies',       description: 'Data security, remote work, and other company-wide policies.' },
  { icon: User,           title: 'Profile Management',     description: 'How to keep your personal and professional details up to date.' },
  { icon: GraduationCap,  title: 'Training Resources',     description: 'Learning paths, certifications, and internal training programs.' },
];

const RESOURCES: GuideItem[] = [
  { icon: CalendarDays,   title: 'Holiday Calendar',       description: 'Upcoming public and company holidays for your location.' },
  { icon: FolderOpen,     title: 'Company Documents',      description: 'Official templates, letters, and shared company documents.' },
  { icon: ClipboardList,  title: 'Forms',                   description: 'Commonly used HR forms and request templates.' },
  { icon: Phone,          title: 'HR Contacts',            description: 'The right HR contact for your location or department.' },
  { icon: Gift,           title: 'Benefits Information',   description: 'Insurance providers, coverage details, and enrolment windows.' },
  { icon: Users,          title: 'Employee Directory',     description: 'Look up colleagues and their contact details across the org.' },
];

const FAQS: { question: string; answer: string }[] = [
  { question: 'How do I apply for leave?', answer: 'Go to Leave & Holidays from the sidebar, click Request Leave, choose a leave type and dates, and submit. Your manager will be notified to approve or reject it.' },
  { question: 'How can I check my attendance record?', answer: 'Open My Attendance from the sidebar to see your daily punches, regularization requests, and monthly summary.' },
  { question: 'How do I raise an HR support ticket?', answer: 'Use the Contact HR Support button below. Pick a topic, describe your request, and submit — you’ll get a ticket number to track under My Requests.' },
  { question: 'How long does HR take to respond?', answer: 'Response times vary by request, but you’ll get an in-app notification the moment HR replies or updates your ticket’s status — no need to keep checking back.' },
  { question: 'How can I update my profile details?', answer: 'Go to your Profile page from the top-right menu to update your contact details and personal information.' },
  { question: 'Where can I find company policies and documents?', answer: 'Visit My Documents & Policies from the sidebar, or use the Helpful Resources section above for quick links.' },
  { question: 'How do I request an asset or submit an expense claim?', answer: 'Open Assets & Expenses from the sidebar to raise a new asset request or submit an expense claim for reimbursement.' },
  { question: 'Who do I contact about payroll or benefits questions?', answer: 'Raise a ticket under the Payroll or Benefits topic using Contact HR Support — it’ll route directly to the HR team.' },
];

function SectionHeader({ title, description }: { title: string; description?: string }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <h2 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 16, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>{title}</h2>
      {description && <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 4, marginBottom: 0 }}>{description}</p>}
    </div>
  );
}

function GuideCard({ icon: Icon, title, description, onOpen }: GuideItem & { onOpen: () => void }) {
  const [hover, setHover] = useState(false);
  return (
    <div
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        background: 'var(--panel)', border: `1px solid ${hover ? 'var(--line2)' : 'var(--line)'}`, borderRadius: 10,
        padding: 18, display: 'flex', flexDirection: 'column', gap: 10, transition: 'border-color .15s',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
        <Icon size={17} style={{ color: 'var(--brand)', flexShrink: 0 }} />
        <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>{title}</span>
      </div>
      <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', margin: 0, flex: 1 }}>{description}</p>
      <button
        onClick={onOpen}
        style={{ alignSelf: 'flex-start', background: 'none', border: '1px solid var(--line2)', color: 'var(--brand)', fontSize: 12.5, fontWeight: 600, padding: '6px 13px', borderRadius: 6, cursor: 'pointer' }}
      >
        Open Guide
      </button>
    </div>
  );
}

function ResourceCard({ icon: Icon, title, onOpen }: Pick<GuideItem, 'icon' | 'title'> & { onOpen: () => void }) {
  const [hover, setHover] = useState(false);
  return (
    <button
      onClick={onOpen}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: 10, width: '100%', textAlign: 'left',
        background: hover ? 'var(--raised)' : 'var(--panel)', border: `1px solid ${hover ? 'var(--line2)' : 'var(--line)'}`,
        borderRadius: 10, padding: '13px 15px', cursor: 'pointer', transition: 'background .15s, border-color .15s',
      }}
    >
      <Icon size={16} style={{ color: 'var(--brand)', flexShrink: 0 }} />
      <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{title}</span>
    </button>
  );
}

/** One reusable modal for every guide and resource — swap `content` for real docs later. */
function GuideModal({ title, description, content, onClose, onContactHR }: {
  title: string; description?: string; content?: React.ReactNode; onClose: () => void; onContactHR: () => void;
}) {
  return (
    <div style={overlayStyle} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={modalStyle}>
        <ModalHeader title={title} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {description && <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 0, marginBottom: 16 }}>{description}</p>}
          <div style={{ background: 'var(--raised)', border: '1px dashed var(--line2)', borderRadius: 8, padding: '28px 18px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>
            {content ?? 'Detailed content for this guide is being finalized and will appear here soon.'}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 18, flexWrap: 'wrap', gap: 10 }}>
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

function FAQAccordion({ items }: { items: { question: string; answer: string }[] }) {
  const [openIndex, setOpenIndex] = useState<number | null>(null);
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
      {items.map((faq, i) => {
        const isOpen = openIndex === i;
        return (
          <div key={faq.question} style={{ borderBottom: i < items.length - 1 ? '1px solid var(--line)' : 'none' }}>
            <button
              onClick={() => setOpenIndex(isOpen ? null : i)}
              aria-expanded={isOpen}
              style={{
                width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
                padding: '14px 18px', background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left',
                fontSize: 13.5, fontWeight: 600, color: 'var(--txt)', fontFamily: 'inherit',
              }}
            >
              {faq.question}
              <ChevronDown size={16} style={{ color: 'var(--txt-dim)', flexShrink: 0, transform: isOpen ? 'rotate(180deg)' : 'none', transition: 'transform .15s' }} />
            </button>
            {isOpen && (
              <div style={{ padding: '0 18px 16px', fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.6 }}>{faq.answer}</div>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────

const STATUS_FILTERS: Array<TicketStatus | 'ALL'> = ['ALL', 'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'WAITING_FOR_EMPLOYEE', 'RESOLVED', 'CLOSED'];

export default function HelpDeskPage() {
  const token = useAuthStore(s => s.token)!;
  const [categories, setCategories] = useState<HelpdeskCategory[]>([]);
  const [showContactModal, setShowContactModal] = useState(false);
  const [activeInfo, setActiveInfo] = useState<GuideItem | null>(null);
  const [tickets, setTickets] = useState<TicketSummary[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<TicketStatus | 'ALL'>('ALL');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  function loadTickets(p = page) {
    setLoading(true);
    helpdeskApi.listMine(token, { status: statusFilter === 'ALL' ? undefined : statusFilter, search: search || undefined, page: p, size: 10 })
      .then(res => { setTickets(res.content); setTotalPages(res.totalPages); setPage(res.number); })
      .finally(() => setLoading(false));
  }

  useEffect(() => { helpdeskApi.listCategories(token).then(setCategories); }, [token]);
  useEffect(() => { loadTickets(0); }, [token, statusFilter, search]);

  function openContactFromModal() {
    setActiveInfo(null);
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

  return (
    <div>
      {/* 1. Header */}
      <div style={{ marginBottom: 28 }}>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Help & Guidance</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4, maxWidth: 640 }}>
          Browse HR guides, find company resources, and raise a support request — all from one place.
        </p>
      </div>

      {/* 2. Quick Help / Guides */}
      <div style={{ marginBottom: 32 }}>
        <SectionHeader title="Quick Help & Guides" description="Start here — most questions are already answered below." />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 14 }}>
          {GUIDES.map(guide => (
            <GuideCard key={guide.title} {...guide} onOpen={() => setActiveInfo(guide)} />
          ))}
        </div>
      </div>

      {/* 3. Helpful Resources */}
      <div style={{ marginBottom: 32 }}>
        <SectionHeader title="Helpful Resources" description="Quick links to things employees need most often." />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
          {RESOURCES.map(resource => (
            <ResourceCard key={resource.title} icon={resource.icon} title={resource.title} onOpen={() => setActiveInfo(resource)} />
          ))}
        </div>
      </div>

      {/* 4. FAQ */}
      <div style={{ marginBottom: 32 }}>
        <SectionHeader title="Frequently Asked Questions" description="Quick answers to the questions HR hears most." />
        <FAQAccordion items={FAQS} />
      </div>

      {/* 5. Contact HR Support — unchanged button/modal, just relocated */}
      <div style={{
        marginBottom: 32, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
        padding: '20px 22px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 14,
      }}>
        <div>
          <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 15, fontWeight: 700, color: 'var(--txt)' }}>Still need help?</div>
          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 3 }}>Raise a request and our HR team will get back to you.</div>
        </div>
        <button onClick={() => setShowContactModal(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
          <HelpCircle size={14} /> Contact HR Support
        </button>
      </div>

      {/* 6. My Requests — unchanged block, just relocated */}
      <div style={{ marginBottom: 18 }}>
        <h2 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 16, fontWeight: 700, color: 'var(--txt)', margin: '0 0 12px' }}>My Requests</h2>

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

        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          {loading ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
          ) : tickets.length === 0 ? (
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
                    {['Ticket Number', 'Category', 'Created', 'Last Updated', 'Status'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {tickets.map(t => (
                    <tr key={t.id} style={{ cursor: 'pointer' }} onClick={() => setSelectedId(t.id)}>
                      <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace', color: 'var(--txt)', fontWeight: 600 }}>{t.ticketNumber}</td>
                      <td style={tdStyle}>{t.categoryName}</td>
                      <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDateTime(t.createdAt)}</td>
                      <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDateTime(t.updatedAt)}</td>
                      <td style={tdStyle}><StatusBadge status={t.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {totalPages > 1 && (
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

      {activeInfo && (
        <GuideModal
          title={activeInfo.title}
          description={activeInfo.description}
          onClose={() => setActiveInfo(null)}
          onContactHR={openContactFromModal}
        />
      )}
    </div>
  );
}
