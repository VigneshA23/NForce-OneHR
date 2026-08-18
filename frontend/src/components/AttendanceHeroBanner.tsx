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

function formatClockTime(iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso + '+05:30');
  if (isNaN(d.getTime())) return null;
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
}

function formatElapsedMs(ms: number): string {
  const totalMin = Math.max(0, Math.floor(ms / 60000));
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
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

// Web clock-in prompt — shows only in State 1 (pre-check-in) when no pending request exists.
function WebClockInRow({ today, loading, onSubmitted }: {
  today: TodayAttendance | null;
  loading: boolean;
  onSubmitted: () => void;
}) {
  const token = useAuthStore(s => s.token) ?? '';
  const [showModal, setShowModal] = useState(false);
  const [legacy, setLegacy] = useState<WebClockInRecord | null>(null);

  useEffect(() => {
    webClockInApi.mine(token).then(list => {
      setLegacy(list.find(r => r.workDate === todayIsoDate() && r.status !== 'APPROVED') ?? null);
    }).catch(() => setLegacy(null));
  }, [token]);

  if (loading) return null;

  return (
    <div style={{ borderTop: '1px solid var(--line)', paddingTop: 10, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      {today?.canCheckIn && !legacy && (
        <button
          onClick={() => setShowModal(true)}
          style={{ fontSize: 12, fontWeight: 600, color: 'var(--brand)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          Working remotely? Web Clock In →
        </button>
      )}
      {legacy?.status === 'PENDING' && (
        <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Web clock-in pending approval.</span>
      )}
      {legacy?.status === 'REJECTED' && (
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
      {showModal && (
        <WebClockInRequestModal onClose={() => setShowModal(false)} onSubmitted={() => { onSubmitted(); }} />
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
  const sessionStart = record?.sessionStartedAt ?? checkInAt;

  const fetchedAtRef = useMemo(() => ({ t: Date.now() }), [today]);
  const liveElapsedMs = useMemo(() => {
    if (!sessionStart || !today?.serverNow) return 0;
    const baseElapsed = new Date(today.serverNow + 'Z').getTime() - new Date(sessionStart + 'Z').getTime();
    return Math.max(0, baseElapsed + (now.getTime() - fetchedAtRef.t));
  }, [sessionStart, today, now, fetchedAtRef]);

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
      LATE:             { dot: '#E0A93B', label: `Late${record.lateByMinutes ? ` by ${record.lateByMinutes}m` : ''}` },
      HALF_DAY:         { dot: '#4E9EE8', label: 'Half day' },
      ABSENT:           { dot: '#B11116', label: 'Absent' },
      MISSING_CHECKOUT: { dot: '#F97316', label: 'Missing checkout' },
    };
    const def = map[record.status];
    return def ? <HeroPill key="status" dot={def.dot} label={def.label} /> : null;
  })();

  const workModePill = record?.workMode ? (
    <HeroPill
      key="mode"
      dot="#6B7280"
      label={record.workMode.charAt(0) + record.workMode.slice(1).toLowerCase()}
    />
  ) : null;

  // ── State 3: Checked out ──────────────────────────────────────────────────────
  if (!today?.canCheckIn && !today?.canCheckOut) {
    const isComplete = !!(checkInAt && checkOutAt);
    const headline = isComplete
      ? `You completed today's workday. Total worked time: ${formatWorkedMinutes(record?.workedMinutes ?? null)}.`
      : 'No attendance recorded today.';
    const subtitle = isComplete
      ? `Checked in at ${formatClockTime(checkInAt)} · Checked out at ${formatClockTime(checkOutAt)}`
      : undefined;
    const breakNote = today?.breakUsedMinutes != null
      ? `${today.breakUsedMinutes}m break of ${today.breakBudgetMinutes}m budget used`
      : null;

    return (
      <HeroCard>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
          {headline}
        </div>
        {subtitle && <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>{subtitle}</p>}
        {breakNote && <p style={{ margin: 0, fontSize: 12, color: 'rgba(229,231,235,0.4)' }}>{breakNote}</p>}
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          <HeroPill dot="#2FB67C" label="Completed" />
          {statusPill}
          {workModePill}
        </div>
        <button
          onClick={() => navigate('/attendance')}
          style={{ alignSelf: 'flex-start', fontSize: 12, fontWeight: 600, color: 'rgba(229,231,235,0.55)', background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
        >
          View full record →
        </button>
      </HeroCard>
    );
  }

  // ── State 2: Active session ───────────────────────────────────────────────────
  if (today?.canCheckOut) {
    const priorWorked = record?.workedMinutes ?? 0;
    const sessionLabel = priorWorked > 0
      ? `Session started at ${formatClockTime(sessionStart) ?? '—'} · ${formatElapsedMs(liveElapsedMs)} elapsed · ${formatWorkedMinutes(priorWorked)} prior`
      : `Checked in at ${formatClockTime(sessionStart) ?? '—'}${liveElapsedMs > 0 ? ` · ${formatElapsedMs(liveElapsedMs)} elapsed` : ''}`;
    const breakNote = today.breakUsedMinutes != null
      ? `${today.breakUsedMinutes}m break of ${today.breakBudgetMinutes}m budget used`
      : null;

    return (
      <HeroCard>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
          Working now.
        </div>
        <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>{sessionLabel}</p>
        {breakNote && <p style={{ margin: 0, fontSize: 12, color: 'rgba(229,231,235,0.4)' }}>{breakNote}</p>}
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          <HeroPill dot="#4E9EE8" label="Working" pulse />
          {statusPill}
          {workModePill}
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
      </HeroCard>
    );
  }

  // ── State 1b: On break ────────────────────────────────────────────────────────
  if (today?.canCheckIn && record) {
    const workedSoFar = record.workedMinutes ?? 0;
    const breakNote = today.breakUsedMinutes != null
      ? `${today.breakUsedMinutes}m break of ${today.breakBudgetMinutes}m budget used`
      : null;

    return (
      <HeroCard>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED', letterSpacing: '-0.01em', lineHeight: 1.25 }}>
          On a break.
        </div>
        <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)', lineHeight: 1.4 }}>
          You've worked {formatWorkedMinutes(workedSoFar)} today — check in again to resume.
        </p>
        {breakNote && <p style={{ margin: 0, fontSize: 12, color: 'rgba(229,231,235,0.4)' }}>{breakNote}</p>}
        <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
          <HeroPill dot="#E0A93B" label="On break" />
          {statusPill}
          {workModePill}
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
        <WebClockInRow today={today} loading={false} onSubmitted={refresh} />
      </div>
    </HeroCard>
  );
}
