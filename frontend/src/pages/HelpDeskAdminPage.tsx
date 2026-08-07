import { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2, Clock, Inbox, Paperclip, Send, UserCog } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  helpdeskApi,
  hrHelpdeskApi,
  type AssignableAgent,
  type ReplyItem,
  type TicketDetail,
  type TicketStatus,
  type TicketSummary,
} from '../api/helpdesk';

const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const filterSelect: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', color: 'var(--txt)', fontSize: 12.5, outline: 'none' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

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

function Kpi({ icon, label, value, danger }: { icon: React.ReactNode; label: string; value: number; danger?: boolean }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
      </div>
      <div style={{ fontSize: 28, fontWeight: 700, fontFamily: '"Space Grotesk", sans-serif', color: danger && value > 0 ? '#E4373D' : 'var(--txt)', lineHeight: 1 }}>{value}</div>
    </div>
  );
}

function fmtDateTime(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

const NEXT_STATUS_OPTIONS: Record<TicketStatus, TicketStatus[]> = {
  OPEN: ['ASSIGNED', 'IN_PROGRESS', 'CLOSED'],
  ASSIGNED: ['IN_PROGRESS', 'OPEN', 'CLOSED'],
  IN_PROGRESS: ['WAITING_FOR_EMPLOYEE', 'RESOLVED', 'ASSIGNED', 'CLOSED'],
  WAITING_FOR_EMPLOYEE: ['IN_PROGRESS', 'RESOLVED', 'CLOSED'],
  RESOLVED: ['CLOSED', 'IN_PROGRESS'],
  CLOSED: [],
};

// ── Conversation thread (internal notes visually distinct) ─

function ReplyBubble({ reply, token }: { reply: ReplyItem; token: string }) {
  const isHr = reply.senderRole === 'HR';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: isHr ? 'flex-end' : 'flex-start', marginBottom: 12 }}>
      <div style={{
        maxWidth: '80%',
        background: reply.internal ? 'rgba(224,169,59,.12)' : isHr ? 'rgba(177,17,22,.08)' : 'var(--raised)',
        border: `1px solid ${reply.internal ? 'rgba(224,169,59,.35)' : isHr ? 'rgba(177,17,22,.25)' : 'var(--line)'}`,
        borderRadius: 10, padding: '10px 14px',
      }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', marginBottom: 4 }}>
          {reply.senderName} <span style={{ color: 'var(--txt-dim)', fontWeight: 500 }}>· {isHr ? 'HR' : 'Employee'}</span>
          {reply.internal && <span style={{ marginLeft: 6, color: '#E0A93B', fontWeight: 700 }}>INTERNAL NOTE</span>}
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

function TicketDetailView({ ticketId, token, agents, onBack, onChanged }: {
  ticketId: string; token: string; agents: AssignableAgent[]; onBack: () => void; onChanged: () => void;
}) {
  const { showToast } = useToast();
  const [ticket, setTicket] = useState<TicketDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState('');
  const [internal, setInternal] = useState(false);
  const [attachment, setAttachment] = useState<File | null>(null);
  const [sending, setSending] = useState(false);
  const [assigneeId, setAssigneeId] = useState('');
  const [savingAssign, setSavingAssign] = useState(false);
  const [savingStatus, setSavingStatus] = useState(false);

  function load() {
    hrHelpdeskApi.getTicket(ticketId, token).then(t => { setTicket(t); setAssigneeId(t.assignedTo ?? ''); }).finally(() => setLoading(false));
  }
  useEffect(load, [ticketId, token]);

  async function handleReply(e: React.FormEvent) {
    e.preventDefault();
    if (!message.trim()) return;
    setSending(true);
    try {
      await hrHelpdeskApi.reply(ticketId, message.trim(), internal, attachment, token);
      setMessage(''); setInternal(false); setAttachment(null);
      load();
      onChanged();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to send reply');
    } finally { setSending(false); }
  }

  async function handleAssign(newAssigneeId: string) {
    setAssigneeId(newAssigneeId);
    if (!newAssigneeId) return;
    setSavingAssign(true);
    try {
      await hrHelpdeskApi.assign(ticketId, newAssigneeId, token);
      showToast('success', 'Ticket assigned');
      load();
      onChanged();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to assign ticket');
    } finally { setSavingAssign(false); }
  }

  async function handleStatusChange(newStatus: TicketStatus) {
    setSavingStatus(true);
    try {
      await hrHelpdeskApi.updateStatus(ticketId, { status: newStatus }, token);
      showToast('success', `Status changed to ${newStatus.replace(/_/g, ' ')}`);
      load();
      onChanged();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to change status');
    } finally { setSavingStatus(false); }
  }

  if (loading || !ticket) {
    return <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>;
  }

  return (
    <div>
      <button onClick={onBack} style={{ background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 12.5, cursor: 'pointer', marginBottom: 14, padding: 0 }}>
        ← Back to queue
      </button>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 20, marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10, flexWrap: 'wrap', gap: 10 }}>
          <div>
            <div style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 4 }}>{ticket.ticketNumber}</div>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{ticket.categoryName}</div>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 2 }}>Raised by {ticket.employeeName}</div>
          </div>
          <StatusBadge status={ticket.status} />
        </div>
        <div style={{ fontSize: 13, color: 'var(--txt-mut)', whiteSpace: 'pre-wrap', marginBottom: 14 }}>{ticket.description}</div>

        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', paddingTop: 14, borderTop: '1px solid var(--line)' }}>
          <div style={{ minWidth: 180 }}>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 5 }}>Assigned To</div>
            <select style={filterSelect} value={assigneeId} disabled={savingAssign} onChange={e => handleAssign(e.target.value)}>
              <option value="">Unassigned</option>
              {agents.map(a => <option key={a.userId} value={a.userId}>{a.name}</option>)}
            </select>
          </div>
          <div style={{ minWidth: 180 }}>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 5 }}>Change Status</div>
            <select
              style={filterSelect}
              value=""
              disabled={savingStatus || NEXT_STATUS_OPTIONS[ticket.status].length === 0}
              onChange={e => e.target.value && handleStatusChange(e.target.value as TicketStatus)}
            >
              <option value="">{NEXT_STATUS_OPTIONS[ticket.status].length === 0 ? 'No further transitions' : 'Select next status…'}</option>
              {NEXT_STATUS_OPTIONS[ticket.status].map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
            </select>
          </div>
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 12 }}>
          Created {fmtDateTime(ticket.createdAt)} · Last updated {fmtDateTime(ticket.updatedAt)}
          {ticket.resolvedAt && <> · Resolved {fmtDateTime(ticket.resolvedAt)} by {ticket.resolvedByName}</>}
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

      <form onSubmit={handleReply} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <textarea
          style={{ ...inputStyle, minHeight: 70, resize: 'vertical', fontFamily: 'inherit' }}
          placeholder="Type a reply…"
          value={message}
          onChange={e => setMessage(e.target.value)}
        />
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
              <Paperclip size={13} />
              {attachment ? attachment.name : 'Attach a file'}
              <input type="file" style={{ display: 'none' }} onChange={e => setAttachment(e.target.files?.[0] ?? null)} />
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
              <input type="checkbox" checked={internal} onChange={e => setInternal(e.target.checked)} />
              Internal note (HR only)
            </label>
          </div>
          <button type="submit" disabled={sending || !message.trim()} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '8px 16px', fontSize: 13, fontWeight: 600, cursor: sending ? 'not-allowed' : 'pointer', opacity: sending ? 0.7 : 1 }}>
            <Send size={13} /> {sending ? 'Sending…' : 'Reply'}
          </button>
        </div>
      </form>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────

const STATUS_FILTERS: Array<TicketStatus | 'ALL'> = ['ALL', 'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'WAITING_FOR_EMPLOYEE', 'RESOLVED', 'CLOSED'];

export default function HelpDeskAdminPage() {
  const token = useAuthStore(s => s.token)!;
  const [dashboard, setDashboard] = useState({ openCount: 0, assignedCount: 0, inProgressCount: 0, waitingForEmployeeCount: 0, resolvedCount: 0, closedCount: 0 });
  const [agents, setAgents] = useState<AssignableAgent[]>([]);
  const [tickets, setTickets] = useState<TicketSummary[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<TicketStatus | 'ALL'>('ALL');
  const [assigneeFilter, setAssigneeFilter] = useState('');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  function loadQueue(p = page) {
    setLoading(true);
    hrHelpdeskApi.listQueue(token, {
      status: statusFilter === 'ALL' ? undefined : statusFilter,
      assignedTo: assigneeFilter || undefined,
      search: search || undefined,
      page: p, size: 10,
    }).then(res => { setTickets(res.content); setTotalPages(res.totalPages); setPage(res.number); })
      .finally(() => setLoading(false));
  }

  function loadDashboard() {
    hrHelpdeskApi.dashboard(token).then(setDashboard);
  }

  useEffect(() => { hrHelpdeskApi.listAgents(token).then(setAgents); loadDashboard(); }, [token]);
  useEffect(() => { loadQueue(0); }, [token, statusFilter, assigneeFilter, search]);

  function refreshAll() { loadQueue(); loadDashboard(); }

  if (selectedId) {
    return (
      <div>
        <div style={{ marginBottom: 18 }}>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>HR Service Requests</h1>
        </div>
        <TicketDetailView ticketId={selectedId} token={token} agents={agents} onBack={() => { setSelectedId(null); refreshAll(); }} onChanged={refreshAll} />
      </div>
    );
  }

  return (
    <div>
      <div style={{ marginBottom: 22 }}>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>HR Service Requests</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>Help Desk tickets raised by employees across the organization.</p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginBottom: 22 }}>
        <Kpi icon={<Inbox size={14} />} label="Open" value={dashboard.openCount} />
        <Kpi icon={<UserCog size={14} />} label="Assigned" value={dashboard.assignedCount} />
        <Kpi icon={<Clock size={14} />} label="In Progress" value={dashboard.inProgressCount} />
        <Kpi icon={<AlertTriangle size={14} />} label="Waiting on Employee" value={dashboard.waitingForEmployeeCount} danger />
        <Kpi icon={<CheckCircle2 size={14} />} label="Resolved" value={dashboard.resolvedCount} />
      </div>

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
        <input
          placeholder="Search ticket number or description…"
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={{ flex: '1 1 240px', minWidth: 200, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 12px', color: 'var(--txt)', fontSize: 13, outline: 'none' }}
        />
        <select value={assigneeFilter} onChange={e => setAssigneeFilter(e.target.value)} style={filterSelect}>
          <option value="">All Assignees</option>
          {agents.map(a => <option key={a.userId} value={a.userId}>{a.name}</option>)}
        </select>
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
            <div style={{ fontSize: 15, color: 'var(--txt-mut)' }}>No tickets match these filters.</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Ticket Number', 'Employee', 'Topic', 'Status', 'Assigned To', 'Last Update'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {tickets.map(t => (
                  <tr key={t.id} style={{ cursor: 'pointer' }} onClick={() => setSelectedId(t.id)}>
                    <td style={{ ...tdStyle, fontFamily: '"JetBrains Mono", monospace', color: 'var(--txt)', fontWeight: 600 }}>{t.ticketNumber}</td>
                    <td style={tdStyle}>{t.employeeName}</td>
                    <td style={tdStyle}>{t.categoryName}</td>
                    <td style={tdStyle}><StatusBadge status={t.status} /></td>
                    <td style={tdStyle}>{t.assignedToName ?? '—'}</td>
                    <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDateTime(t.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {totalPages > 1 && (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 12, padding: '12px 0', borderTop: '1px solid var(--line)' }}>
            <button onClick={() => loadQueue(page - 1)} disabled={page === 0} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)', cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? 0.5 : 1 }}>← Prev</button>
            <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Page {page + 1} of {totalPages}</span>
            <button onClick={() => loadQueue(page + 1)} disabled={page >= totalPages - 1} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)', cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page >= totalPages - 1 ? 0.5 : 1 }}>Next →</button>
          </div>
        )}
      </div>
    </div>
  );
}
