import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Clock, LogIn, LogOut, CheckCircle2, CalendarPlus, X } from 'lucide-react';
import {
  attendanceApi, regularizationApi,
  type AttendanceRecord,
  type AttendanceStatus,
  type TodayAttendance,
  type RegularizationRecord,
  type SubmitRegularizationPayload,
} from '../api/attendance';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { toShellRole } from '../lib/nav.config';

// ─── Formatting helpers ───────────────────────────────────────────────────────
// Server timestamps are wall-clock strings in the business timezone (no offset), so they are
// formatted by slicing rather than via `new Date()` — that would re-interpret them in the
// browser's zone and shift the displayed time.

function formatTime(iso: string | null): string | null {
  if (!iso) return null;
  const time = iso.slice(11, 16);
  if (time.length < 5) return null;
  const [h, m] = time.split(':').map(Number);
  const suffix = h < 12 ? 'AM' : 'PM';
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, '0')} ${suffix}`;
}

function formatDuration(minutes: number | null): string | null {
  if (minutes == null) return null;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function formatDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, {
    weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
  });
}

/** Parses a zone-less server timestamp into epoch ms using the same fixed reference frame. */
function wallClockMs(iso: string): number {
  const [datePart, timePart = '00:00:00'] = iso.split('T');
  const [y, mo, d] = datePart.split('-').map(Number);
  const [h, mi, s] = timePart.split(':').map((v) => Math.floor(Number(v)));
  return Date.UTC(y, mo - 1, d, h, mi, s || 0);
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Regularization timestamps come back with an offset, so a plain slice is safe here. */
function fmtDateTime(dt: string | null) {
  if (!dt) return '—';
  return dt.replace('T', ' ').slice(0, 16);
}

const STATUS_COLORS: Record<AttendanceStatus, string> = {
  PRESENT: '#2FB67C',
  LATE: '#E0A93B',
  HALF_DAY: '#4C8DD6',
  ABSENT: '#E4373D',
};

const STATUS_LABELS: Record<AttendanceStatus, string> = {
  PRESENT: 'Present',
  LATE: 'Late',
  HALF_DAY: 'Half Day',
  ABSENT: 'Absent',
};

const REGULARIZATION_STATUS_COLOR: Record<string, string> = {
  PENDING: '#E0A93B', APPROVED: '#2FB67C', REJECTED: '#E4373D',
};

const dash = <span style={{ color: 'var(--txt-dim)' }}>—</span>;

const thStyle: React.CSSProperties = {
  padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700,
  color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em',
  borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap',
};
const tdStyle: React.CSSProperties = {
  padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)',
  borderBottom: '1px solid var(--line)', verticalAlign: 'middle',
};
const panelStyle: React.CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden',
};
const dateInputStyle: React.CSSProperties = {
  background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)',
  borderRadius: 7, padding: '7px 10px', fontSize: 13,
};

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.5)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

// ─── Shared bits ──────────────────────────────────────────────────────────────

function StatusPill({ status }: { status: AttendanceStatus | null }) {
  if (!status) return dash;
  const color = STATUS_COLORS[status] ?? 'var(--txt-mut)';
  return (
    <span style={{
      fontSize: 11, fontWeight: 600, color,
      background: 'var(--raised)', border: '1px solid var(--line)',
      borderRadius: 4, padding: '2px 7px', whiteSpace: 'nowrap',
    }}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

function RegularizationStatusPill({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: REGULARIZATION_STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {status}
    </span>
  );
}

function SourceTag({ source }: { source: string | null }) {
  if (!source) return dash;
  return <span>{source === 'REGULARIZATION' ? 'Regularized' : 'System'}</span>;
}

function SectionHeading({ title, hint }: { title: string; hint?: string }) {
  return (
    <div style={{ marginBottom: 12 }}>
      <h2 style={{
        fontFamily: '"Space Grotesk", sans-serif', fontSize: 15, fontWeight: 700,
        color: 'var(--txt)', margin: 0,
      }}>{title}</h2>
      {hint && <p style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 3 }}>{hint}</p>}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

// ─── Request Regularization Modal ─────────────────────────────────────────────
function RequestModal({ onClose, onCreated, token }: { onClose: () => void; onCreated: (r: RegularizationRecord) => void; token: string }) {
  const { showToast } = useToast();
  const today = todayIsoDate();
  const [attendanceDate, setAttendanceDate] = useState(today);
  const [checkIn, setCheckIn] = useState('');
  const [checkOut, setCheckOut] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!attendanceDate || !reason.trim()) { setError('Date and reason are required.'); return; }
    if (!checkIn && !checkOut) { setError('Provide a corrected check-in or check-out time.'); return; }
    setSubmitting(true); setError(null);
    try {
      const payload: SubmitRegularizationPayload = {
        attendanceDate,
        requestedCheckIn: checkIn || undefined,
        requestedCheckOut: checkOut || undefined,
        reason: reason.trim(),
      };
      const created = await regularizationApi.submit(payload, token);
      onCreated(created);
      showToast('success', 'Regularization request submitted');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Submission failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <ModalHeader title="Request Regularization" onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <Field label="Attendance Date *">
            <input type="date" style={inputStyle} value={attendanceDate} max={today} onChange={e => setAttendanceDate(e.target.value)} />
          </Field>
          <Field label="Corrected Check-In">
            <input type="datetime-local" style={inputStyle} value={checkIn} onChange={e => setCheckIn(e.target.value)} />
          </Field>
          <Field label="Corrected Check-Out">
            <input type="datetime-local" style={inputStyle} value={checkOut} onChange={e => setCheckOut(e.target.value)} />
          </Field>
          <Field label="Reason *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder="e.g. Forgot to punch out after client meeting"
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

// ─── Reject Modal ──────────────────────────────────────────────────────────────
function RejectModal({ request, onClose, onRejected, token }: { request: RegularizationRecord; onClose: () => void; onRejected: (r: RegularizationRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleReject() {
    if (!comment.trim()) { setError('A comment is required when rejecting a request.'); return; }
    setSubmitting(true); setError(null);
    try {
      const updated = await regularizationApi.reject(request.id, comment.trim(), token);
      onRejected(updated);
      showToast('success', 'Request rejected');
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Reject failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title={`Reject — ${request.employeeName}`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 14, fontSize: 13 }}>{error}</div>}
          <Field label="Reason for rejection *">
            <textarea
              style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }}
              value={comment}
              onChange={e => setComment(e.target.value)}
              placeholder="Explain why this request is being rejected"
            />
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 16 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={handleReject} disabled={submitting} style={{ background: '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Rejecting…' : 'Reject Request'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Day roster used by both the Manager and HR views. */
function RosterTable({ rows, loading, emptyMessage }: {
  rows: AttendanceRecord[]; loading: boolean; emptyMessage: string;
}) {
  return (
    <div style={panelStyle}>
      {loading ? (
        <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
      ) : rows.length === 0 ? (
        <div style={{ padding: 48, textAlign: 'center' }}>
          <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>Nothing to show</div>
          <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>{emptyMessage}</div>
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                {['Employee ID', 'Name', 'Check In', 'Check Out', 'Hours', 'Status', 'Source'].map((h) => (
                  <th key={h} style={thStyle}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.employeeUserId}>
                  <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: 12 }}>{r.employeeCode}</td>
                  <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.fullName}</td>
                  <td style={tdStyle}>{formatTime(r.checkInAt) ?? dash}</td>
                  <td style={tdStyle}>{formatTime(r.checkOutAt) ?? dash}</td>
                  <td style={tdStyle}>{formatDuration(r.workedMinutes) ?? dash}</td>
                  <td style={tdStyle}><StatusPill status={r.status} /></td>
                  <td style={tdStyle}><SourceTag source={r.source} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ─── My attendance (punch card + own history) ─────────────────────────────────

function MyAttendance() {
  const token = useAuthStore((s) => s.token)!;
  const { showToast } = useToast();

  const [today, setToday] = useState<TodayAttendance | null>(null);
  const [history, setHistory] = useState<AttendanceRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  // Offset between the browser clock and the server's business-timezone clock, captured on
  // load, so the live elapsed counter is correct in any browser timezone.
  const serverOffsetMs = useRef(0);
  const [tick, setTick] = useState(0);

  const loadHistory = useCallback(() => {
    const to = todayIsoDate();
    const from = new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10);
    return attendanceApi.myHistory(from, to, token);
  }, [token]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([attendanceApi.today(token), loadHistory()])
      .then(([t, h]) => {
        if (cancelled) return;
        serverOffsetMs.current = wallClockMs(t.serverNow) - Date.now();
        setToday(t);
        setHistory(h);
      })
      .catch((err) => {
        if (!cancelled) showToast('error', err instanceof Error ? err.message : 'Failed to load attendance');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, loadHistory, showToast]);

  const openSince = today?.canCheckOut ? today.record?.checkInAt ?? null : null;

  useEffect(() => {
    if (!openSince) return;
    const id = setInterval(() => setTick((n) => n + 1), 60000);
    return () => clearInterval(id);
  }, [openSince]);

  const elapsed = useMemo(() => {
    if (!openSince) return null;
    void tick; // re-derive on each tick
    const minutes = Math.floor(
      (Date.now() + serverOffsetMs.current - wallClockMs(openSince)) / 60000,
    );
    return minutes >= 0 ? formatDuration(minutes) : null;
  }, [openSince, tick]);

  async function punch(kind: 'in' | 'out') {
    setSubmitting(true);
    try {
      const record = kind === 'in'
        ? await attendanceApi.checkIn(token)
        : await attendanceApi.checkOut(token);

      // Re-read /today so canCheckIn/canCheckOut always come from the server, never inferred.
      const [refreshed, refreshedHistory] = await Promise.all([
        attendanceApi.today(token),
        loadHistory(),
      ]);
      serverOffsetMs.current = wallClockMs(refreshed.serverNow) - Date.now();
      setToday(refreshed);
      setHistory(refreshedHistory);

      const at = formatTime(kind === 'in' ? record.checkInAt : record.checkOutAt);
      showToast('success', `Checked ${kind} ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : `Check ${kind} failed`);
    } finally {
      setSubmitting(false);
    }
  }

  const primaryButtonStyle = (disabled: boolean): React.CSSProperties => ({
    display: 'flex', alignItems: 'center', gap: 8,
    background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8,
    padding: '12px 22px', fontSize: 14, fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.7 : 1,
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
      {/* Punch card */}
      <div style={{ ...panelStyle, padding: '22px 24px' }}>
        {loading ? (
          <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Loading…</div>
        ) : !today ? (
          <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Attendance unavailable right now.</div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 24, flexWrap: 'wrap' }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 7, color: 'var(--txt-dim)', fontSize: 12, marginBottom: 8 }}>
                <Clock size={13} /> {formatDay(today.workDate)}
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: 26, flexWrap: 'wrap' }}>
                <div>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', marginBottom: 4 }}>Check In</div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--txt)' }}>
                    {formatTime(today.record?.checkInAt ?? null) ?? dash}
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', marginBottom: 4 }}>Check Out</div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--txt)' }}>
                    {formatTime(today.record?.checkOutAt ?? null) ?? dash}
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', marginBottom: 4 }}>
                    {today.canCheckOut ? 'Elapsed' : 'Hours'}
                  </div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--txt)' }}>
                    {(today.canCheckOut ? elapsed : formatDuration(today.record?.workedMinutes ?? null)) ?? dash}
                  </div>
                </div>
                {today.record?.status && (
                  <div>
                    <div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', marginBottom: 6 }}>Status</div>
                    <StatusPill status={today.record.status} />
                  </div>
                )}
              </div>

              {today.record?.status === 'LATE' && (today.record.lateByMinutes ?? 0) > 0 && (
                <div style={{ fontSize: 12, color: '#E0A93B', marginTop: 10 }}>
                  Checked in {formatDuration(today.record.lateByMinutes)} past the grace period.
                </div>
              )}
            </div>

            {/* The button is driven only by the server's canCheckIn / canCheckOut flags. */}
            <div>
              {today.canCheckIn && (
                <button onClick={() => punch('in')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                  <LogIn size={15} /> {submitting ? 'Checking in…' : 'Check In'}
                </button>
              )}
              {today.canCheckOut && (
                <button onClick={() => punch('out')} disabled={submitting} style={primaryButtonStyle(submitting)}>
                  <LogOut size={15} /> {submitting ? 'Checking out…' : 'Check Out'}
                </button>
              )}
              {!today.canCheckIn && !today.canCheckOut && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ok)', fontSize: 13, fontWeight: 600 }}>
                  <CheckCircle2 size={16} /> Day complete
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Own history */}
      <div>
        <SectionHeading title="My recent attendance" hint="Last 30 days" />
        <div style={panelStyle}>
          {loading ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
          ) : history.length === 0 ? (
            <div style={{ padding: 48, textAlign: 'center' }}>
              <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No attendance yet</div>
              <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Your punches will appear here once you check in.</div>
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Date', 'Check In', 'Check Out', 'Hours', 'Status', 'Source'].map((h) => (
                      <th key={h} style={thStyle}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {history.map((r) => (
                    <tr key={r.workDate}>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{formatDay(r.workDate)}</td>
                      <td style={tdStyle}>{formatTime(r.checkInAt) ?? dash}</td>
                      <td style={tdStyle}>{formatTime(r.checkOutAt) ?? dash}</td>
                      <td style={tdStyle}>{formatDuration(r.workedMinutes) ?? dash}</td>
                      <td style={tdStyle}><StatusPill status={r.status} /></td>
                      <td style={tdStyle}><SourceTag source={r.source} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Regularization (request + my requests + pending approvals) ───────────────

function RegularizationSection({ token, canApprove }: { token: string; canApprove: boolean }) {
  const { showToast } = useToast();
  const [myRequests, setMyRequests] = useState<RegularizationRecord[]>([]);
  const [pending, setPending] = useState<RegularizationRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [rejecting, setRejecting] = useState<RegularizationRecord | null>(null);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [regularizationApi.mine(token).then(setMyRequests)];
    if (canApprove) calls.push(regularizationApi.pending(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load regularization requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showToast]);

  useEffect(() => { loadAll(); }, [loadAll]);

  async function handleApprove(reqId: string) {
    try {
      await regularizationApi.approve(reqId, token);
      showToast('success', 'Request approved and attendance record updated');
      loadAll();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Approve failed');
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <SectionHeading title="Attendance Regularization" hint="Request corrections for missed or incorrect punches." />
        <button onClick={() => setShowRequest(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
          <CalendarPlus size={14} /> Request Regularization
        </button>
      </div>

      {/* My Regularization Requests */}
      <div>
        <h3 style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>My Requests</h3>
        <div style={panelStyle}>
          {loading ? (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>Loading…</div>
          ) : myRequests.length === 0 ? (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No requests submitted yet.</div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr>{['Date', 'Requested In', 'Requested Out', 'Reason', 'Status', 'Reviewer Note'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                <tbody>
                  {myRequests.map(r => (
                    <tr key={r.id}>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{r.attendanceDate}</td>
                      <td style={tdStyle}>{fmtDateTime(r.requestedCheckIn)}</td>
                      <td style={tdStyle}>{fmtDateTime(r.requestedCheckOut)}</td>
                      <td style={{ ...tdStyle, maxWidth: 220 }}>{r.reason}</td>
                      <td style={tdStyle}><RegularizationStatusPill status={r.status} /></td>
                      <td style={tdStyle}>{r.reviewComment ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Pending Approvals — Manager / HR Admin / Super Admin only */}
      {canApprove && (
        <div>
          <h3 style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Pending Approvals</h3>
          <div style={panelStyle}>
            {pending.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No pending requests.</div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr>{['Employee', 'Date', 'Requested In', 'Requested Out', 'Reason', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                  <tbody>
                    {pending.map(r => (
                      <tr key={r.id}>
                        <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>
                          {r.employeeName}
                          <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeEmail}</div>
                        </td>
                        <td style={tdStyle}>{r.attendanceDate}</td>
                        <td style={tdStyle}>{fmtDateTime(r.requestedCheckIn)}</td>
                        <td style={tdStyle}>{fmtDateTime(r.requestedCheckOut)}</td>
                        <td style={{ ...tdStyle, maxWidth: 220 }}>{r.reason}</td>
                        <td style={tdStyle}>
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button onClick={() => handleApprove(r.id)} style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: '#2FB67C', cursor: 'pointer' }}>Approve</button>
                            <button onClick={() => setRejecting(r)} style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: '#E4373D', cursor: 'pointer' }}>Reject</button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {showRequest && (
        <RequestModal token={token} onClose={() => setShowRequest(false)} onCreated={r => setMyRequests(prev => [r, ...prev])} />
      )}
      {rejecting && (
        <RejectModal
          request={rejecting}
          token={token}
          onClose={() => setRejecting(null)}
          onRejected={updated => setPending(prev => prev.filter(r => r.id !== updated.id))}
        />
      )}
    </div>
  );
}

// ─── Roster view (Manager team / HR org-wide) ─────────────────────────────────

function DayRoster({ scope }: { scope: 'team' | 'all' }) {
  const token = useAuthStore((s) => s.token)!;
  const { showToast } = useToast();

  const [date, setDate] = useState(todayIsoDate());
  const [rows, setRows] = useState<AttendanceRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const fetcher = scope === 'team' ? attendanceApi.team : attendanceApi.day;
    fetcher(date, token)
      .then((r) => { if (!cancelled) setRows(r); })
      .catch((err) => {
        if (cancelled) return;
        setRows([]);
        showToast('error', err instanceof Error ? err.message : 'Failed to load attendance');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [scope, date, token, showToast]);

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <SectionHeading
          title={scope === 'team' ? 'Team attendance' : 'Organization attendance'}
          hint={scope === 'team'
            ? 'Your current direct reports for the selected day.'
            : 'All active employees for the selected day.'}
        />
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--txt-dim)', marginBottom: 12 }}>
          Date
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={dateInputStyle} />
        </label>
      </div>
      <RosterTable
        rows={rows}
        loading={loading}
        emptyMessage={scope === 'team'
          ? 'No direct reports are assigned to you yet.'
          : 'No employee records for this date.'}
      />
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AttendancePage() {
  // The router has no role guard, so — like Shell — the page resolves the role itself.
  const token = useAuthStore((s) => s.token)!;
  const role = toShellRole(useAuthStore((s) => s.user?.role));
  const canApprove = role === 'Manager' || role === 'HR Admin' || role === 'Super Admin';

  const subtitle = role === 'Manager'
    ? 'Punch in and out, and review your team’s attendance for any day.'
    : role === 'HR Admin' || role === 'Super Admin'
      ? 'Punch in and out, and review attendance across the organization.'
      : 'Punch in when you start your day and out when you finish.';

  return (
    <div>
      <div style={{ marginBottom: 22 }}>
        <h1 style={{
          fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700,
          color: 'var(--txt)', margin: 0,
        }}>Attendance</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>{subtitle}</p>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 30 }}>
        <MyAttendance />
        <RegularizationSection token={token} canApprove={canApprove} />
        {role === 'Manager' && <DayRoster scope="team" />}
        {(role === 'HR Admin' || role === 'Super Admin') && <DayRoster scope="all" />}
      </div>
    </div>
  );
}
