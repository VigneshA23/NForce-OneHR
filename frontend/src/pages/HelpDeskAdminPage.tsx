import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, CheckCircle2, ChevronLeft, ChevronRight, CircleDot, Clock, Eye, History, Inbox, Info,
  Lock, MessageSquareText, Paperclip, Play, Search, Send, SlidersHorizontal, Ticket, UserPlus,
} from 'lucide-react';
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
import './RequestsPage.css';

// Presentational only — tone class per status (see RequestsPage.css).
const STATUS_TONES: Record<TicketStatus, string> = {
  OPEN: 'tone-warn',
  IN_PROGRESS: 'tone-indigo',
  RESOLVED: 'tone-ok',
  CLOSED: 'tone-mute',
};

function StatusBadge({ status }: { status: TicketStatus }) {
  return (
    <span className={`nf-rq-badge ${STATUS_TONES[status] ?? 'tone-mute'}`}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}

function Kpi({ icon, label, value, danger, tone }: { icon: React.ReactNode; label: string; value: number; danger?: boolean; tone: string }) {
  return (
    <div className="nf-rq-kpi">
      <div className={`nf-rq-kpi-icon ${tone}`}>{icon}</div>
      <div style={{ minWidth: 0 }}>
        <div className="nf-rq-kpi-label">{label}</div>
        <div className={`nf-rq-kpi-value${danger && value > 0 ? ' nf-rq-kpi-value--danger' : ''}`}>{value}</div>
      </div>
    </div>
  );
}

/** Initials for the avatar chip — derived from the name already on the row, no photo fetch. */
function initials(name?: string | null) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '?';
}

function Person({ name, fallback, large }: { name?: string | null; fallback?: string; large?: boolean }) {
  return (
    <div className="nf-rq-person">
      <span className={`nf-rq-avatar${large ? ' nf-rq-avatar--lg' : ''}${name ? '' : ' nf-rq-avatar--muted'}`} aria-hidden="true">
        {name ? initials(name) : '–'}
      </span>
      <span className={name ? 'nf-rq-strong' : 'nf-rq-dim'}>{name ?? fallback ?? '—'}</span>
    </div>
  );
}

function PageHeader({ subtitle }: { subtitle?: string }) {
  return (
    <div className="nf-rq-header">
      <div className="nf-rq-header-main">
        <div className="nf-rq-header-icon"><Ticket size={24} /></div>
        <div>
          <h1 className="nf-rq-title">HR Service Requests</h1>
          {subtitle && <p className="nf-rq-subtitle">{subtitle}</p>}
        </div>
      </div>
    </div>
  );
}

function fmtDateTime(s?: string | null) {
  if (!s) return '—';
  return new Date(s).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

// Strictly linear lifecycle (see TicketStatus.java / V93) — every status has at most one legal
// next state, and CLOSED is permanently terminal with no reopen path.
const NEXT_STATUS_OPTIONS: Record<TicketStatus, TicketStatus[]> = {
  OPEN: ['IN_PROGRESS'],
  IN_PROGRESS: ['RESOLVED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
};

// ── Conversation thread (internal notes visually distinct) ─

function ReplyBubble({ reply, token }: { reply: ReplyItem; token: string }) {
  const isHr = reply.senderRole === 'HR';
  return (
    <div className={`nf-rq-msg${isHr ? ' nf-rq-msg--hr' : ''}`}>
      <div className={`nf-rq-bubble${reply.internal ? ' nf-rq-bubble--internal' : ''}`}>
        <div className="nf-rq-bubble-meta">
          {reply.senderName} <span className="nf-rq-bubble-role">· {isHr ? 'HR' : 'Employee'}</span>
          {reply.internal && <span className="nf-rq-internal-tag"><Lock size={9} /> INTERNAL NOTE</span>}
        </div>
        <div className="nf-rq-bubble-text">{reply.message}</div>
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
            className="nf-rq-attach"
          >
            <Paperclip size={12} /> <span>{reply.attachmentName}</span>
          </button>
        )}
      </div>
      <div className="nf-rq-msg-time">{fmtDateTime(reply.createdAt)}</div>
    </div>
  );
}

/** Ticket identity block shared by the preview and the workspace. */
function TicketHeading({ ticket, raisedBy }: { ticket: TicketDetail; raisedBy?: boolean }) {
  return (
    <div className="nf-rq-ticket-head">
      <div className="nf-rq-ticket-id">
        <span className="nf-rq-type-icon tone-brand" style={{ width: 44, height: 44, borderRadius: 12 }}><Ticket size={20} /></span>
        <div style={{ minWidth: 0 }}>
          <div className="nf-rq-ticket-kicker">{ticket.ticketNumber}</div>
          <h2 className="nf-rq-ticket-title">{ticket.categoryName}</h2>
          {raisedBy && <div className="nf-rq-ticket-by">Raised by {ticket.employeeName}</div>}
        </div>
      </div>
      <StatusBadge status={ticket.status} />
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
    return <div className="nf-rq-panel nf-rq-loading">Loading…</div>;
  }

  return (
    <>
      <button onClick={onBack} className="nf-rq-back">
        <ArrowLeft size={15} /> Back to queue
      </button>

      <div className="nf-rq-split">
        <div className="nf-rq-stack">
          <div className="nf-rq-panel">
            <div className="nf-rq-panel-body">
              <TicketHeading ticket={ticket} raisedBy />
              <div className="nf-rq-section-label">Description</div>
              <div className="nf-rq-quote">{ticket.description}</div>
            </div>
          </div>

          <div className="nf-rq-panel">
            <div className="nf-rq-panel-head">
              <h3 className="nf-rq-panel-title"><MessageSquareText size={16} /> Conversation</h3>
            </div>
            <div className="nf-rq-thread">
              {ticket.replies.length === 0 ? (
                <div className="nf-rq-thread-empty">No replies yet.</div>
              ) : (
                ticket.replies.map(r => <ReplyBubble key={r.id} reply={r} token={token} />)
              )}
            </div>

            <form onSubmit={handleReply} className="nf-rq-composer">
              <textarea
                className="nf-rq-textarea"
                placeholder="Type a reply…"
                value={message}
                onChange={e => setMessage(e.target.value)}
              />
              <div className="nf-rq-composer-bar">
                <div className="nf-rq-composer-opts">
                  <label className="nf-rq-opt nf-rq-opt--file">
                    <Paperclip size={13} />
                    <span>{attachment ? attachment.name : 'Attach a file'}</span>
                    <input type="file" style={{ display: 'none' }} onChange={e => setAttachment(e.target.files?.[0] ?? null)} />
                  </label>
                  <label className="nf-rq-opt">
                    <input type="checkbox" checked={internal} onChange={e => setInternal(e.target.checked)} />
                    Internal note (HR only)
                  </label>
                </div>
                <button type="submit" disabled={sending || !message.trim()} className="nf-rq-btn nf-rq-btn--primary">
                  <Send size={14} /> {sending ? 'Sending…' : 'Reply'}
                </button>
              </div>
            </form>
          </div>
        </div>

        <div className="nf-rq-stack">
          <div className="nf-rq-panel">
            <div className="nf-rq-panel-head">
              <h3 className="nf-rq-panel-title"><SlidersHorizontal size={16} /> Manage Ticket</h3>
            </div>
            <div className="nf-rq-panel-body" style={{ display: 'grid', gap: 16 }}>
              <div>
                <label className="nf-rq-field-label" htmlFor="nf-rq-assignee">Assigned To</label>
                <select id="nf-rq-assignee" className="nf-rq-select nf-rq-select--block" value={assigneeId} disabled={savingAssign} onChange={e => handleAssign(e.target.value)}>
                  <option value="">Unassigned</option>
                  {agents.filter(a => a.active !== false).map(a => <option key={a.userId} value={a.userId}>{a.name}</option>)}
                </select>
              </div>
              <div>
                <label className="nf-rq-field-label" htmlFor="nf-rq-next-status">Change Status</label>
                <select
                  id="nf-rq-next-status"
                  className="nf-rq-select nf-rq-select--block"
                  value=""
                  disabled={savingStatus || NEXT_STATUS_OPTIONS[ticket.status].length === 0}
                  onChange={e => e.target.value && handleStatusChange(e.target.value as TicketStatus)}
                >
                  <option value="">{NEXT_STATUS_OPTIONS[ticket.status].length === 0 ? 'No further transitions' : 'Select next status…'}</option>
                  {NEXT_STATUS_OPTIONS[ticket.status].map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
                </select>
              </div>
            </div>
          </div>

          <div className="nf-rq-panel">
            <div className="nf-rq-panel-head">
              <h3 className="nf-rq-panel-title"><History size={16} /> Activity</h3>
            </div>
            <div className="nf-rq-panel-body">
              <ul className="nf-rq-timeline">
                <li>
                  <span className="nf-rq-tl-dot nf-rq-tl-dot--filled tone-brand" />
                  <div><div className="nf-rq-tl-title">Created</div><div className="nf-rq-tl-sub">{fmtDateTime(ticket.createdAt)}</div></div>
                </li>
                <li>
                  <span className="nf-rq-tl-dot tone-info" />
                  <div><div className="nf-rq-tl-title">Last updated</div><div className="nf-rq-tl-sub">{fmtDateTime(ticket.updatedAt)}</div></div>
                </li>
                {ticket.resolvedAt && (
                  <li>
                    <span className="nf-rq-tl-dot nf-rq-tl-dot--filled tone-ok" />
                    <div><div className="nf-rq-tl-title">Resolved</div><div className="nf-rq-tl-sub">{fmtDateTime(ticket.resolvedAt)} by {ticket.resolvedByName}</div></div>
                  </li>
                )}
              </ul>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

// ── Ticket Preview: read-only, shown before entering the full workspace ────
// Opening/viewing a ticket must never mutate it — this component issues exactly one GET on
// mount and otherwise only calls the existing assign/startWorking endpoints, and only in
// response to an explicit button click. Reuses TicketDetail (same shape TicketDetailView
// already fetches) and the existing agents list — no new backend read is introduced.

function TicketPreview({ ticketId, token, agents, onBack, onOpenWorkspace, onChanged }: {
  ticketId: string; token: string; agents: AssignableAgent[];
  onBack: () => void; onOpenWorkspace: (id: string) => void; onChanged: () => void;
}) {
  const { showToast } = useToast();
  const [ticket, setTicket] = useState<TicketDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [showAssignPanel, setShowAssignPanel] = useState(false);
  const [assigneeChoice, setAssigneeChoice] = useState('');
  const [assigning, setAssigning] = useState(false);

  function load() {
    setLoading(true);
    hrHelpdeskApi.getTicket(ticketId, token).then(setTicket).finally(() => setLoading(false));
  }
  useEffect(load, [ticketId, token]);

  async function handleStartWorking() {
    setStarting(true);
    try {
      await hrHelpdeskApi.startWorking(ticketId, token);
      showToast('success', 'Request assigned to you and moved to In Progress.');
      onChanged();
      onOpenWorkspace(ticketId);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to start working on this request');
      setStarting(false);
    }
  }

  async function handleConfirmAssign() {
    if (!assigneeChoice) return;
    setAssigning(true);
    try {
      await hrHelpdeskApi.assign(ticketId, assigneeChoice, token);
      const name = agents.find(a => a.userId === assigneeChoice)?.name ?? 'the selected HR admin';
      showToast('success', `Request assigned to ${name}.`);
      setShowAssignPanel(false);
      setAssigneeChoice('');
      onChanged();
      load(); // refresh this preview to show the new assignee — status is untouched by design
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to assign request');
    } finally { setAssigning(false); }
  }

  if (loading || !ticket) {
    return <div className="nf-rq-panel nf-rq-loading">Loading…</div>;
  }

  const canStartWorking = ticket.status === 'OPEN';
  const canReassign = ticket.status === 'OPEN' || ticket.status === 'IN_PROGRESS';

  return (
    <>
      <button onClick={onBack} className="nf-rq-back">
        <ArrowLeft size={15} /> Back to queue
      </button>

      <div className="nf-rq-split">
        <div className="nf-rq-panel">
          <div className="nf-rq-panel-body">
            <TicketHeading ticket={ticket} />

            <div className="nf-rq-fields">
              <div>
                <div className="nf-rq-field-label">Requested By</div>
                <Person name={ticket.employeeName} />
              </div>
              <div>
                <div className="nf-rq-field-label">Created</div>
                <div className="nf-rq-field-value">{fmtDateTime(ticket.createdAt)}</div>
              </div>
              <div>
                <div className="nf-rq-field-label">Assigned To</div>
                <Person name={ticket.assignedToName} fallback="Unassigned" />
              </div>
            </div>

            <div className="nf-rq-section-label">Description</div>
            <div className="nf-rq-quote">{ticket.description}</div>
          </div>
        </div>

        <div className="nf-rq-panel">
          <div className="nf-rq-panel-head">
            <h3 className="nf-rq-panel-title">{showAssignPanel ? <><UserPlus size={16} /> Assign To</> : <><Play size={16} /> Actions</>}</h3>
          </div>
          <div className="nf-rq-panel-body">
            <div className="nf-rq-hint" style={{ marginBottom: 16 }}>
              <Info size={14} /> Just viewing — nothing changes until you choose an action below.
            </div>

            {showAssignPanel ? (
              <>
                <select className="nf-rq-select nf-rq-select--block" aria-label="Assign To" value={assigneeChoice} onChange={e => setAssigneeChoice(e.target.value)}>
                  <option value="">Select an HR Admin…</option>
                  {agents.filter(a => a.active !== false).map(a => <option key={a.userId} value={a.userId}>{a.name}</option>)}
                </select>
                <div className="nf-rq-actions-row" style={{ marginTop: 14 }}>
                  <button onClick={() => setShowAssignPanel(false)} className="nf-rq-btn nf-rq-btn--ghost">Cancel</button>
                  <button
                    onClick={handleConfirmAssign}
                    disabled={!assigneeChoice || assigning}
                    className="nf-rq-btn nf-rq-btn--primary"
                  >
                    {assigning ? 'Assigning…' : 'Assign'}
                  </button>
                </div>
              </>
            ) : (
              <div className="nf-rq-actions">
                {canStartWorking && (
                  <button onClick={handleStartWorking} disabled={starting} className="nf-rq-btn nf-rq-btn--primary nf-rq-btn--block">
                    <Play size={14} /> {starting ? 'Starting…' : 'Start Working'}
                  </button>
                )}
                {canReassign && (
                  <button onClick={() => setShowAssignPanel(true)} className="nf-rq-btn nf-rq-btn--outline nf-rq-btn--block">
                    <UserPlus size={14} /> Assign to HR
                  </button>
                )}
                {!canStartWorking && (
                  <button onClick={() => onOpenWorkspace(ticketId)} className="nf-rq-btn nf-rq-btn--primary nf-rq-btn--block">
                    <MessageSquareText size={14} /> Open Conversation
                  </button>
                )}
                {canStartWorking && (
                  <div style={{ textAlign: 'center', marginTop: 4 }}>
                    <button onClick={() => onOpenWorkspace(ticketId)} className="nf-rq-link">View conversation without starting work →</button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

// ── Main page ─────────────────────────────────────────────

// "Active Queue" (OPEN + IN_PROGRESS) is the default landing filter — those are the tickets
// that still need HR attention. Open/In Progress/Resolved/Closed narrow to exactly one status.
interface QueueFilter { key: string; label: string; statuses: TicketStatus[] }
const QUEUE_FILTERS: QueueFilter[] = [
  { key: 'ACTIVE', label: 'Active Queue', statuses: ['OPEN', 'IN_PROGRESS'] },
  { key: 'OPEN', label: 'Open', statuses: ['OPEN'] },
  { key: 'IN_PROGRESS', label: 'In Progress', statuses: ['IN_PROGRESS'] },
  { key: 'RESOLVED', label: 'Resolved', statuses: ['RESOLVED'] },
  { key: 'CLOSED', label: 'Closed', statuses: ['CLOSED'] },
];

const TABLE_HEADERS = ['Ticket Number', 'Employee', 'Topic', 'Status', 'Assigned To', 'Last Update'];

export default function HelpDeskAdminPage() {
  const token = useAuthStore(s => s.token)!;
  const [dashboard, setDashboard] = useState({ openCount: 0, inProgressCount: 0, resolvedCount: 0, closedCount: 0 });
  const [agents, setAgents] = useState<AssignableAgent[]>([]);
  const [tickets, setTickets] = useState<TicketSummary[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<string>('ACTIVE');
  const [assigneeFilter, setAssigneeFilter] = useState('');
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [previewId, setPreviewId] = useState<string | null>(null);

  // Deep-link support: the header/global search's Helpdesk results link here as
  // /requests?ticketId=<id> (opens the read-only preview, same as clicking a row) or
  // /requests?search=<term> — read once on mount, same as DirectoryPage's ?userId= convention.
  const [searchParams] = useSearchParams();
  useEffect(() => {
    const ticketId = searchParams.get('ticketId');
    if (ticketId) setPreviewId(ticketId);
    const q = searchParams.get('search');
    if (q) setSearch(q);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeFilter = QUEUE_FILTERS.find(f => f.key === statusFilter) ?? QUEUE_FILTERS[0];

  // Debounced 300ms, matching the content-search pattern on /help, so typing doesn't fire a
  // request per keystroke.
  useEffect(() => {
    const handle = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(handle);
  }, [search]);

  function loadQueue(p = page) {
    setLoading(true);
    hrHelpdeskApi.listQueue(token, {
      status: activeFilter.statuses,
      assignedTo: assigneeFilter || undefined,
      search: debouncedSearch || undefined,
      page: p, size: 10,
    }).then(res => { setTickets(res.content); setTotalPages(res.totalPages); setPage(res.number); setTotalElements(res.totalElements); })
      .finally(() => setLoading(false));
  }

  // Cards above are always global totals across every ticket, regardless of these filters —
  // that mismatch is expected, but only worth calling out once a search/assignee filter is
  // actually narrowing the list below.
  const hasActiveNarrowing = Boolean(debouncedSearch || assigneeFilter);

  function loadDashboard() {
    hrHelpdeskApi.dashboard(token).then(setDashboard);
  }

  useEffect(() => { hrHelpdeskApi.listAgents(token).then(setAgents); loadDashboard(); }, [token]);
  useEffect(() => { loadQueue(0); }, [token, statusFilter, assigneeFilter, debouncedSearch]);

  function refreshAll() { loadQueue(); loadDashboard(); }

  if (selectedId) {
    return (
      <div className="nf-rq">
        <PageHeader />
        <TicketDetailView ticketId={selectedId} token={token} agents={agents} onBack={() => { setSelectedId(null); refreshAll(); }} onChanged={refreshAll} />
      </div>
    );
  }

  // Clicking a ticket opens this read-only preview first — it never mutates anything on its
  // own. Only "Start Working" or a confirmed "Assign" click (both explicit) touch the ticket;
  // "Open Conversation" / going Back leave it exactly as it was.
  if (previewId) {
    return (
      <div className="nf-rq">
        <PageHeader />
        <TicketPreview
          ticketId={previewId}
          token={token}
          agents={agents}
          onBack={() => setPreviewId(null)}
          onOpenWorkspace={id => { setPreviewId(null); setSelectedId(id); }}
          onChanged={refreshAll}
        />
      </div>
    );
  }

  return (
    <div className="nf-rq">
      <PageHeader subtitle="Help Desk tickets raised by employees across the organization." />

      <div className="nf-rq-kpis">
        <Kpi icon={<Inbox size={20} />} tone="tone-brand" label="Active Queue" value={dashboard.openCount + dashboard.inProgressCount} danger />
        <Kpi icon={<CircleDot size={20} />} tone="tone-warn" label="Open" value={dashboard.openCount} />
        <Kpi icon={<Clock size={20} />} tone="tone-indigo" label="In Progress" value={dashboard.inProgressCount} />
        <Kpi icon={<CheckCircle2 size={20} />} tone="tone-ok" label="Resolved" value={dashboard.resolvedCount} />
        <Kpi icon={<Lock size={20} />} tone="tone-mute" label="Closed" value={dashboard.closedCount} />
      </div>
      <p className="nf-rq-note">
        Totals across all tickets — the search and assignee filters below narrow the list only.
      </p>

      <div className="nf-rq-card">
        <div className="nf-rq-tabs" role="tablist" aria-label="Filter by ticket status">
          {QUEUE_FILTERS.map(f => (
            <button
              key={f.key}
              role="tab"
              aria-selected={statusFilter === f.key}
              onClick={() => setStatusFilter(f.key)}
              className={`nf-rq-tab${statusFilter === f.key ? ' nf-rq-tab--active' : ''}`}
            >
              {f.label}
            </button>
          ))}
        </div>

        <div className="nf-rq-filters">
          <div className="nf-rq-search">
            <Search size={16} />
            <input
              className="nf-rq-input"
              placeholder="Search ticket number or description…"
              aria-label="Search ticket number or description"
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          <select
            value={assigneeFilter}
            onChange={e => setAssigneeFilter(e.target.value)}
            aria-label="Filter by assignee"
            className={`nf-rq-select${assigneeFilter ? ' nf-rq-select--active' : ''}`}
          >
            <option value="">All Assignees</option>
            {agents.map(a => <option key={a.userId} value={a.userId}>{a.name}</option>)}
          </select>
        </div>
        {hasActiveNarrowing && (
          <div className="nf-rq-narrowing">
            Showing {totalElements} ticket{totalElements === 1 ? '' : 's'} matching your search/assignee filter — the cards above still reflect all tickets.
          </div>
        )}

        {loading ? (
          <div className="nf-rq-table-wrap" aria-busy="true">
            <span className="nf-rq-sr-only">Loading…</span>
            <table className="nf-rq-table">
              <tbody>
                {Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="nf-rq-skel-row">
                    <td className="nf-rq-td-lead"><span className="nf-rq-skel" style={{ width: 90 }} /></td>
                    <td><span className="nf-rq-skel" style={{ width: 140 }} /></td>
                    <td><span className="nf-rq-skel" style={{ width: 120 }} /></td>
                    <td><span className="nf-rq-skel" style={{ width: 80 }} /></td>
                    <td><span className="nf-rq-skel" style={{ width: 110 }} /></td>
                    <td><span className="nf-rq-skel" style={{ width: 120 }} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : tickets.length === 0 ? (
          <div className="nf-rq-empty" style={{ borderTop: '1px solid var(--line)' }}>
            <div className="nf-rq-empty-icon"><Inbox size={24} /></div>
            <div className="nf-rq-empty-title">No tickets match these filters.</div>
          </div>
        ) : (
          <div className="nf-rq-table-wrap">
            <table className="nf-rq-table">
              <thead>
                <tr>
                  {TABLE_HEADERS.map(h => <th key={h}>{h}</th>)}
                  <th className="nf-rq-th-actions"><span className="nf-rq-sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {tickets.map(t => (
                  <tr key={t.id} onClick={() => setPreviewId(t.id)}>
                    <td className="nf-rq-td-lead" data-label="Ticket Number"><span className="nf-rq-ticket-no">{t.ticketNumber}</span></td>
                    <td data-label="Employee"><Person name={t.employeeName} /></td>
                    <td data-label="Topic">{t.categoryName}</td>
                    <td data-label="Status"><StatusBadge status={t.status} /></td>
                    <td data-label="Assigned To">{t.assignedToName ? <Person name={t.assignedToName} /> : <span className="nf-rq-dim">—</span>}</td>
                    <td data-label="Last Update" className="nf-rq-nowrap">{fmtDateTime(t.updatedAt)}</td>
                    <td className="nf-rq-td-actions">
                      {/* Same action as clicking the row — opens the read-only preview. */}
                      <button
                        type="button"
                        className="nf-rq-row-btn"
                        aria-label={`View ticket ${t.ticketNumber}`}
                        title="View ticket"
                        onClick={e => { e.stopPropagation(); setPreviewId(t.id); }}
                      >
                        <Eye size={16} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {totalPages > 1 && (
          <div className="nf-rq-footer" style={{ justifyContent: 'center' }}>
            <div className="nf-rq-pager">
              <button onClick={() => loadQueue(page - 1)} disabled={page === 0} className="nf-rq-btn nf-rq-btn--outline nf-rq-btn--sm">
                <ChevronLeft size={15} /> Prev
              </button>
              <span className="nf-rq-page-label">Page {page + 1} of {totalPages}</span>
              <button onClick={() => loadQueue(page + 1)} disabled={page >= totalPages - 1} className="nf-rq-btn nf-rq-btn--outline nf-rq-btn--sm">
                Next <ChevronRight size={15} />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
