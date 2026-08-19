import { useEffect, useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { LogIn, LogOut } from 'lucide-react';
import { BrandMark } from './BrandMark';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { attendanceApi, type TodayAttendance, type AttendanceConfig } from '../api/attendance';
import { webClockInApi, type WebClockInRecord } from '../api/webClockIn';
import { WebClockInRequestModal } from './WebClockInRequestModal';

// ── Private helpers ──────────────────────────────────────────────────────────────

// Backend LocalDateTime strings are naive wall-clock digits already in the record's own
// resolved zone (browser-reported at Check-In/Web Clock-In, see AttendanceService.resolveZone)
// — there is nothing left to convert. Parsing with 'Z' and formatting with timeZone: 'UTC'
// reads those digits back out verbatim, regardless of the *viewer's* own browser timezone.
// Previously this appended '+05:30' (assumed IST) and let toLocaleTimeString re-project into
// the viewer's local zone — for any employee whose resolved zone isn't IST, or any viewer whose
// browser isn't IST either, that mislabeled the digits and then shifted them again, showing a
// check-in/check-out time that didn't match when the action actually happened.
function formatClockTime(iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso + 'Z');
  if (isNaN(d.getTime())) return null;
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'UTC' });
}

function formatWorkedMinutes(mins: number | null): string {
  if (mins == null) return '—';
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

function shiftStartMinutes(shiftStart: string): number {
  const [h, m] = shiftStart.split(':').map(Number);
  return h * 60 + m;
}

function nowLocalMinutes(): number {
  const d = new Date();
  return d.getHours() * 60 + d.getMinutes();
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

// ── Gradient background — matches the auth layout's left-panel dark treatment. ──

const HERO_BG = [
  'radial-gradient(120% 100% at 80% 10%, rgba(177,17,22,.34) 0%, transparent 55%)',
  'linear-gradient(160deg, #0a0b0e 0%, #12141a 100%)',
].join(', ');

// ── Shell components ─────────────────────────────────────────────────────────────

function HeroCard({ children }: { children: React.ReactNode }) {
  return (
    <div
      data-theme="dark"
      style={{
        position: 'relative',
        background: HERO_BG,
        borderRadius: 12,
        padding: '28px 32px',
        overflow: 'hidden',
        display: 'flex',
        alignItems: 'center',
        gap: 24,
        minHeight: 140,
      }}
    >
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
        {children}
      </div>
      <div style={{ flexShrink: 0, alignSelf: 'flex-start' }}>
        <BrandMark size="lg" />
      </div>
    </div>
  );
}

function HeroPill({ dot, label, pulse = false }: { dot: string; label: string; pulse?: boolean }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '4px 11px', borderRadius: 20,
      background: `color-mix(in srgb, ${dot} 15%, rgba(255,255,255,0.05))`,
      border: `1px solid color-mix(in srgb, ${dot} 28%, rgba(255,255,255,0.08))`,
      fontSize: 11.5, fontWeight: 600, color: '#E8EAED', whiteSpace: 'nowrap',
    }}>
      <span style={{
        width: 6, height: 6, borderRadius: '50%', background: dot, flexShrink: 0,
        ...(pulse ? { animation: 'nf-hero-pulse 2s ease-in-out infinite', boxShadow: `0 0 0 3px color-mix(in srgb, ${dot} 22%, transparent)` } : {}),
      }} />
      {label}
    </span>
  );
}

// Web Clock section — always rendered by every state below (pre-check-in, active session, on
// break, checked out), not just pre-check-in. Its own current action/status is driven entirely
// by `openWeb` (this employee's own currently-open Web Clock-In, if any — independent of
// `today`, which only tracks whichever session is currently open regardless of source), so it
// never disappears just because a Check-In/Check-Out or Web Clock-In/Out happened. Multiple
// Web Clock-In → Web Clock-Out cycles in one day are allowed (see WebClockInService.submit) —
// this row simply reflects whatever the current cycle's state is, same as before.
function WebClockInRow({ today, onSubmitted }: {
  today: TodayAttendance | null;
  onSubmitted: () => void;
}) {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const [showModal, setShowModal] = useState(false);
  const [openWeb, setOpenWeb] = useState<WebClockInRecord | null>(null);
  const [legacy, setLegacy] = useState<WebClockInRecord | null>(null);
  // Most recent Web Clock-In of the day, regardless of status/checked-out — its reason is reused
  // for every later cycle the same day/shift so the employee is only asked once (per requirement:
  // "If Web Clock-In is performed again during the same day/shift, do not ask for the reason
  // again"). Null once a NEW calendar/shift day starts, since todayIsoDate() no longer matches.
  const [reusableReason, setReusableReason] = useState<string | null>(null);
  const [checkingOut, setCheckingOut] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const refreshMine = useCallback(() => {
    webClockInApi.mine(token).then(list => {
      const todayIso = todayIsoDate();
      const todays = list.filter(r => r.workDate === todayIso);
      // PENDING counts as "currently open" alongside APPROVED — the attendance effect is
      // immediate regardless of HR review status (see WebClockInService.submit's doc comment).
      setOpenWeb(todays.find(r => (r.status === 'APPROVED' || r.status === 'PENDING') && !r.checkedOutAt) ?? null);
      setLegacy(todays.find(r => r.status === 'REJECTED' && !r.checkedOutAt) ?? null);
      // `list` is already newest-first (see webClockInApi.mine), so the first match for today is
      // the most recent cycle's reason.
      setReusableReason(todays[0]?.reason ?? null);
    }).catch(() => { setOpenWeb(null); setLegacy(null); setReusableReason(null); });
  }, [token]);

  useEffect(() => { refreshMine(); }, [refreshMine]);

  async function handleCheckOut() {
    setCheckingOut(true);
    try {
      const resp = await webClockInApi.checkOut(token);
      const at = formatClockTime(resp.checkedOutAt);
      showToast('success', `Checked out ${at ? `at ${at}` : 'successfully'}`);
      refreshMine();
      onSubmitted();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Web clock-out failed');
    } finally {
      setCheckingOut(false);
    }
  }

  // Reason already on file for today (a prior cycle this same day/shift) — skip the modal
  // entirely and resubmit straight away, reusing it.
  async function handleQuickWebClockIn(reason: string) {
    setSubmitting(true);
    try {
      const resp = await webClockInApi.submit(reason, token);
      const at = formatClockTime(resp.requestedCheckIn);
      showToast('success', `Checked in ${at ? `at ${at}` : 'successfully'}`);
      refreshMine();
      onSubmitted();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Web clock-in failed');
    } finally {
      setSubmitting(false);
    }
  }

  // A regular (non-web) session is currently open — Web Clock-In can't start a second,
  // concurrent session (same one-session-at-a-time rule as regular Check-In), but the section
  // itself still stays visible with an accurate status instead of vanishing.
  const otherSessionOpen = !openWeb && !today?.canCheckIn && !!today?.canCheckOut;

  return (
    <div style={{ borderTop: '1px solid var(--line)', paddingTop: 10, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      {openWeb && (
        <>
          <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
            Web clocked in since {formatClockTime(openWeb.requestedCheckIn) ?? '—'}
            {openWeb.status === 'PENDING' ? ' (awaiting HR approval)' : ''}
          </span>
          <button
            onClick={handleCheckOut}
            disabled={checkingOut}
            style={{ fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: checkingOut ? 'not-allowed' : 'pointer' }}
          >
            {checkingOut ? 'Web clocking out…' : 'Web Clock Out →'}
          </button>
        </>
      )}
      {!openWeb && legacy && (
        <>
          <span style={{ fontSize: 12, color: 'var(--risk)' }}>Web clock-in rejected{legacy.reviewComment ? `: ${legacy.reviewComment}` : '.'}</span>
          <button
            onClick={() => setShowModal(true)}
            style={{ fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            Resubmit →
          </button>
        </>
      )}
      {!openWeb && !legacy && today?.canCheckIn && (
        <button
          onClick={() => (reusableReason ? handleQuickWebClockIn(reusableReason) : setShowModal(true))}
          disabled={submitting}
          style={{ fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: submitting ? 'not-allowed' : 'pointer' }}
        >
          {submitting ? 'Checking in…' : 'Working remotely? Web Clock In →'}
        </button>
      )}
      {!openWeb && !legacy && otherSessionOpen && (
        <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
          Web Clock In will be available once you check out.
        </span>
      )}
      {showModal && (
        <WebClockInRequestModal onClose={() => setShowModal(false)} onSubmitted={() => { refreshMine(); onSubmitted(); }} />
      )}
    </div>
  );
}

// ── Main export ─────────────────────────────────────────────────────────────────
// Self-contained: reads token from auth store, manages its own today/config state.
// Drop into any dashboard view with zero props.

export function AttendanceHeroBanner() {
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [today, setToday]   = useState<TodayAttendance | null>(null);
  const [loading, setLoading] = useState(true);
  const [config, setConfig]   = useState<AttendanceConfig | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [now, setNow] = useState(() => new Date());

  const refresh = useCallback(() =>
    attendanceApi.today(token).then(setToday).catch(() => {}),
  [token]);

  useEffect(() => {
    refresh().finally(() => setLoading(false));
    attendanceApi.config(token).then(setConfig).catch(() => {});
    const pollId = setInterval(refresh, 60000);
    const tickId = setInterval(() => setNow(new Date()), 30000);
    return () => { clearInterval(pollId); clearInterval(tickId); };
  }, [refresh, token]);

  async function handlePunch(kind: 'in' | 'out') {
    setSubmitting(true);
    try {
      const record = kind === 'in' ? await attendanceApi.checkIn(token) : await attendanceApi.checkOut(token);
      const refreshed = await attendanceApi.today(token);
      setToday(refreshed);
      const at = formatClockTime(kind === 'in' ? refreshed.serverNow : record.checkOutAt);
      showToast('success', `Checked ${kind} ${at ? `at ${at}` : 'successfully'}`);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : `Check ${kind} failed`);
    } finally {
      setSubmitting(false);
    }
  }

  const record     = today?.record ?? null;
  const checkInAt  = record?.checkInAt  ?? null;
  const checkOutAt = record?.checkOutAt ?? null;

  const shiftInfo = useMemo(() => {
    if (!config) return null;
    const diffMin = shiftStartMinutes(config.shiftStart) - nowLocalMinutes();
    const pad2 = (n: number) => String(n).padStart(2, '0');
    const fmt12 = (hhmm: string) => {
      const [h, m] = hhmm.split(':').map(Number);
      return `${h % 12 === 0 ? 12 : h % 12}:${pad2(m)} ${h < 12 ? 'AM' : 'PM'}`;
    };
    return {
      shiftStartLabel: fmt12(config.shiftStart),
      shiftEndLabel: config.shiftEnd ? fmt12(config.shiftEnd) : null,
      shiftName: config.shiftName,
      diffMin,
    };
  }, [config, now]);

  // ── Loading skeleton ──────────────────────────────────────────────────────────
  if (loading) {
    return (
      <HeroCard>
        <div style={{ height: 28, width: 240, background: 'rgba(255,255,255,0.08)', borderRadius: 6, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
        <div style={{ height: 14, width: 360, background: 'rgba(255,255,255,0.05)', borderRadius: 4, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
        <div style={{ display: 'flex', gap: 7 }}>
          <div style={{ height: 26, width: 90, background: 'rgba(255,255,255,0.06)', borderRadius: 20, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
          <div style={{ height: 26, width: 70, background: 'rgba(255,255,255,0.06)', borderRadius: 20, animation: 'nf-hero-pulse 1.4s ease-in-out infinite' }} />
        </div>
      </HeroCard>
    );
  }

  // ── Status pill helpers ───────────────────────────────────────────────────────
  const statusPill = (() => {
    if (!record?.status) return null;
    const map: Record<string, { dot: string; label: string }> = {
      PRESENT:          { dot: '#2FB67C', label: 'On time' },
      // formatWorkedMinutes gives "1h 30m"/"1h"/"45m" — matches how worked/elapsed time is
      // already formatted on this same banner, instead of a raw, unconverted minute count.
      LATE:             { dot: '#E0A93B', label: `Late${record.lateByMinutes ? ` by ${formatWorkedMinutes(record.lateByMinutes)}` : ''}` },
      ABSENT:           { dot: '#B11116', label: 'Absent' },
      MISSING_CHECKOUT: { dot: '#F97316', label: 'Missing checkout' },
    };
    const def = map[record.status];
    return def ? <HeroPill key="status" dot={def.dot} label={def.label} /> : null;
  })();

  // ── State 3: Checked out ──────────────────────────────────────────────────────
  if (!today?.canCheckIn && !today?.canCheckOut) {
    const isComplete = !!(checkInAt && checkOutAt);
    const headline = isComplete
      ? `You completed today's workday. Total worked time: ${formatWorkedMinutes(record?.workedMinutes ?? null)}.`
      : 'No attendance recorded today.';
    const subtitle = isComplete
      ? `Checked in at ${formatClockTime(checkInAt)} · Checked out at ${formatClockTime(checkOutAt)}`
      : undefined;

    return (
      <HeroCard>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
          {headline}
        </div>
        {subtitle && <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>{subtitle}</p>}
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          <HeroPill dot="#2FB67C" label="Completed" />
          {statusPill}
        </div>
        <button
          onClick={() => navigate('/attendance')}
          style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'rgba(229,231,235,0.55)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          View full record →
        </button>
        <WebClockInRow today={today} onSubmitted={refresh} />
      </HeroCard>
    );
  }

  // ── State 2: Active session ───────────────────────────────────────────────────
  if (today?.canCheckOut) {
    // No "Working now." headline / "Session started at… elapsed… prior" line here by design —
    // removed per explicit request; Check-In/Check-Out functionality itself is unaffected.
    return (
      <HeroCard>
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          <HeroPill dot="#4E9EE8" label="Working" pulse />
          {statusPill}
        </div>
        <button
          onClick={() => handlePunch('out')}
          disabled={submitting}
          style={{
            alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 7,
            padding: '8px 18px', background: '#B11116', color: '#fff',
            border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 600,
            cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1,
          }}
        >
          <LogOut size={14} />
          {submitting ? 'Checking out…' : 'Check Out'}
        </button>
        <WebClockInRow today={today} onSubmitted={refresh} />
      </HeroCard>
    );
  }

  // ── State 1b: On break ────────────────────────────────────────────────────────
  if (today?.canCheckIn && record) {
    const workedSoFar = record.workedMinutes ?? 0;

    return (
      <HeroCard>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
          On a break.
        </div>
        <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>
          You've worked {formatWorkedMinutes(workedSoFar)} today — check in again to resume.
        </p>
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          {statusPill}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <button
            onClick={() => handlePunch('in')}
            disabled={submitting}
            style={{
              display: 'flex', alignItems: 'center', gap: 7,
              padding: '8px 18px', background: '#B11116', color: '#fff',
              border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 600,
              cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1,
            }}
          >
            <LogIn size={14} />
            {submitting ? 'Checking in…' : 'Check In Again'}
          </button>
          <button
            onClick={() => navigate('/attendance')}
            style={{ fontSize: 12, fontWeight: 600, color: 'rgba(229,231,235,0.55)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
          >
            View full record →
          </button>
        </div>
        <WebClockInRow today={today} onSubmitted={refresh} />
      </HeroCard>
    );
  }

  // ── State 1: Pre-check-in ─────────────────────────────────────────────────────
  const shiftLine = shiftInfo
    ? shiftInfo.diffMin > 0
      ? `Shift starts at ${shiftInfo.shiftStartLabel} — ${shiftInfo.diffMin < 60 ? `${shiftInfo.diffMin}m from now` : `in ${Math.floor(shiftInfo.diffMin / 60)}h ${shiftInfo.diffMin % 60}m`}.`
      : shiftInfo.diffMin < 0
        ? `Shift started ${Math.abs(shiftInfo.diffMin) < 60 ? `${Math.abs(shiftInfo.diffMin)}m ago` : `${Math.floor(Math.abs(shiftInfo.diffMin) / 60)}h ${Math.abs(shiftInfo.diffMin) % 60}m ago`}.`
        : 'Your shift starts now.'
    : '';
  const subtitle = shiftLine || 'You haven\'t checked in yet today.';

  return (
    <HeroCard>
      <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
        Not checked in yet.
      </div>
      <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>{subtitle}</p>
      <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
        <HeroPill dot="#6B7280" label="Shift not started" />
        {shiftInfo?.shiftName && <HeroPill dot="#6B7280" label={shiftInfo.shiftName} />}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <button
          onClick={() => handlePunch('in')}
          disabled={submitting}
          style={{
            display: 'flex', alignItems: 'center', gap: 7,
            padding: '8px 18px', background: '#B11116', color: '#fff',
            border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 600,
            cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1,
          }}
        >
          <LogIn size={14} />
          {submitting ? 'Checking in…' : 'Check In'}
        </button>
      </div>
      <WebClockInRow today={today} onSubmitted={refresh} />
    </HeroCard>
  );
}
