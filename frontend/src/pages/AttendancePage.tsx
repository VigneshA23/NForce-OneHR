import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Dialog, DialogActions,
  DialogContent, DialogTitle, Divider, IconButton, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography,
  ThemeProvider as MuiThemeProvider, createTheme, alpha,
} from '@mui/material';
import LoginIcon from '@mui/icons-material/Login';
import LogoutIcon from '@mui/icons-material/Logout';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CloseIcon from '@mui/icons-material/Close';
import AccessTimeIcon from '@mui/icons-material/AccessTime';
import AddIcon from '@mui/icons-material/Add';
import EventBusyIcon from '@mui/icons-material/EventBusy';
import InboxIcon from '@mui/icons-material/Inbox';
import GroupsIcon from '@mui/icons-material/Groups';
import CorporateFareIcon from '@mui/icons-material/CorporateFare';
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
import { useTheme as useAppTheme } from '../lib/theme';

// ─── Formatting helpers (unchanged) ────────────────────────────────────────────
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

// ─── MUI theme bridge ──────────────────────────────────────────────────────────
// This page is styled with real MUI components, but the rest of the app uses hand-rolled
// CSS variables (see src/index.css) that flip value under [data-theme="dark"/"light"] on
// <html>. MUI's createTheme runs real color math (lighten/darken/alpha) on the
// primary/error/warning/success/info palette slots to auto-derive hover/contrast shades —
// that math requires an actually-parseable color, NOT a var(--x) reference, or createTheme
// throws and takes the whole render down with it (no error boundary catches it → blank page).
// text/background/divider ALSO turned out to be unsafe as var(--x) refs — MUI derives its
// action.hover/selected/disabled opacity overlays from palette.text.primary via the same
// color math, so every palette slot below must be a real, parseable color. This theme is
// rebuilt (via useMemo keyed on the app's own dark/light toggle) whenever that toggle
// flips, so it still stays in sync — just via a fresh literal-hex object instead of a live
// CSS variable.
const SWATCHES = {
  dark: {
    brand: '#B11116', brandDeep: '#7A0C10', risk: '#E4373D', warn: '#E0A93B', ok: '#2FB67C', info: '#4C8DD6',
    shell: '#0E0F12', panel: '#16181D', line: '#2A2E37', txt: '#E8EAED', txtMut: '#9BA1AC',
  },
  light: {
    brand: '#B11116', brandDeep: '#7A0C10', risk: '#C81A1F', warn: '#896010', ok: '#1A7A52', info: '#1A5FAA',
    shell: '#F7F8FA', panel: '#FFFFFF', line: '#E3E6EA', txt: '#1A1D23', txtMut: '#5A616B',
  },
} as const;

function buildMuiTheme(mode: 'light' | 'dark') {
  const c = SWATCHES[mode];
  return createTheme({
    palette: {
      mode,
      primary: { main: c.brand, dark: c.brandDeep, contrastText: '#fff' },
      error: { main: c.risk },
      warning: { main: c.warn },
      success: { main: c.ok },
      info: { main: c.info },
      background: { default: c.shell, paper: c.panel },
      text: { primary: c.txt, secondary: c.txtMut },
      divider: c.line,
    },
    shape: { borderRadius: 12 },
    typography: {
      fontFamily: "'Inter', system-ui, sans-serif",
      h4: { fontFamily: "'Space Grotesk', system-ui, sans-serif", fontWeight: 700 },
      h6: { fontFamily: "'Space Grotesk', system-ui, sans-serif", fontWeight: 700 },
    },
    components: {
      MuiCard: { styleOverrides: { root: { border: '1px solid var(--line)', backgroundImage: 'none' } } },
      MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' } } },
      MuiTableCell: { styleOverrides: { root: { borderColor: 'var(--line)' } } },
      MuiButton: { styleOverrides: { root: { textTransform: 'none', fontWeight: 600, borderRadius: 8 } } },
      MuiChip: { styleOverrides: { root: { fontWeight: 600 } } },
      MuiDialog: { styleOverrides: { paper: { border: '1px solid var(--line)' } } },
    },
  });
}

// ─── Status chips ──────────────────────────────────────────────────────────────

const STATUS_LABELS: Record<AttendanceStatus, string> = {
  PRESENT: 'Present',
  LATE: 'Late',
  HALF_DAY: 'Half Day',
  ABSENT: 'Absent',
};

const STATUS_HEX: Record<AttendanceStatus, string> = {
  PRESENT: '#2FB67C',
  LATE: '#E0A93B',
  HALF_DAY: '#4C8DD6',
  ABSENT: '#E4373D',
};

const REGULARIZATION_HEX: Record<string, string> = {
  PENDING: '#E0A93B', APPROVED: '#2FB67C', REJECTED: '#E4373D',
};

// NOTE: sx accepts a plain object of CSS-in-JS — alignItems/justifyContent/flexWrap/fontWeight/
// display all go here rather than as direct component props, since this installed MUI version
// (v9) dropped those shorthand props from Stack/Typography in favor of sx-only.
function softChipSx(hex: string) {
  return { color: hex, bgcolor: alpha(hex, 0.12), border: 1, borderColor: alpha(hex, 0.3) };
}

function StatusChip({ status }: { status: AttendanceStatus | null }) {
  if (!status) return <Dash />;
  const hex = STATUS_HEX[status] ?? '#9BA1AC';
  return <Chip size="small" label={STATUS_LABELS[status] ?? status} sx={softChipSx(hex)} />;
}

function RegularizationStatusChip({ status }: { status: string }) {
  const hex = REGULARIZATION_HEX[status] ?? '#9BA1AC';
  return <Chip size="small" label={status} sx={softChipSx(hex)} />;
}

function SourceTag({ source }: { source: string | null }) {
  if (!source) return <Dash />;
  return <Typography variant="body2" color="text.secondary">{source === 'REGULARIZATION' ? 'Regularized' : 'System'}</Typography>;
}

function Dash() {
  return <Typography component="span" color="text.disabled">—</Typography>;
}

function SectionHeading({ title, hint }: { title: string; hint?: string }) {
  return (
    <Box sx={{ mb: 1.5 }}>
      <Typography variant="h6">{title}</Typography>
      {hint && <Typography variant="body2" color="text.secondary" sx={{ mt: 0.3 }}>{hint}</Typography>}
    </Box>
  );
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '.07em', display: 'block', mb: 0.5 }}>
        {label}
      </Typography>
      <Typography variant="h6" sx={{ fontWeight: 600 }}>{value}</Typography>
    </Box>
  );
}

function EmptyState({ icon, title, message }: { icon: React.ReactNode; title: string; message: string }) {
  return (
    <Stack spacing={1} sx={{ py: 6, color: 'text.disabled', alignItems: 'center' }}>
      {icon}
      <Typography variant="body1" color="text.secondary">{title}</Typography>
      <Typography variant="body2" color="text.disabled">{message}</Typography>
    </Stack>
  );
}

// ─── Request Regularization Dialog ─────────────────────────────────────────────
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
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        Request Regularization
        <IconButton size="small" onClick={onClose}><CloseIcon fontSize="small" /></IconButton>
      </DialogTitle>
      <Box component="form" onSubmit={handleSubmit}>
        <DialogContent>
          <Stack spacing={2.5}>
            {error && <Alert severity="error" variant="outlined">{error}</Alert>}
            <TextField
              label="Attendance Date" type="date" required fullWidth size="small"
              value={attendanceDate} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today } }}
              onChange={e => setAttendanceDate(e.target.value)}
            />
            <TextField
              label="Corrected Check-In" type="datetime-local" fullWidth size="small"
              value={checkIn} slotProps={{ inputLabel: { shrink: true } }}
              onChange={e => setCheckIn(e.target.value)}
            />
            <TextField
              label="Corrected Check-Out" type="datetime-local" fullWidth size="small"
              value={checkOut} slotProps={{ inputLabel: { shrink: true } }}
              onChange={e => setCheckOut(e.target.value)}
            />
            <TextField
              label="Reason" required fullWidth multiline minRows={3} size="small"
              value={reason} onChange={e => setReason(e.target.value)}
              placeholder="e.g. Forgot to punch out after client meeting"
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={onClose} color="inherit">Cancel</Button>
          <Button type="submit" variant="contained" disabled={submitting} startIcon={submitting ? <CircularProgress size={14} color="inherit" /> : undefined}>
            {submitting ? 'Submitting…' : 'Submit Request'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}

// ─── Reject Dialog ──────────────────────────────────────────────────────────────
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
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        Reject — {request.employeeName}
        <IconButton size="small" onClick={onClose}><CloseIcon fontSize="small" /></IconButton>
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          {error && <Alert severity="error" variant="outlined">{error}</Alert>}
          <TextField
            label="Reason for rejection" required fullWidth multiline minRows={3} size="small"
            value={comment} onChange={e => setComment(e.target.value)}
            placeholder="Explain why this request is being rejected"
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} color="inherit">Cancel</Button>
        <Button onClick={handleReject} variant="contained" color="error" disabled={submitting} startIcon={submitting ? <CircularProgress size={14} color="inherit" /> : undefined}>
          {submitting ? 'Rejecting…' : 'Reject Request'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** Day roster used by both the Manager and HR views. */
function RosterTable({ rows, loading, emptyMessage }: {
  rows: AttendanceRecord[]; loading: boolean; emptyMessage: string;
}) {
  return (
    <Card variant="outlined">
      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}><CircularProgress size={28} /></Box>
      ) : rows.length === 0 ? (
        <EmptyState icon={<InboxIcon fontSize="large" />} title="Nothing to show" message={emptyMessage} />
      ) : (
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                {['Employee ID', 'Name', 'Check In', 'Check Out', 'Hours', 'Status', 'Source'].map((h) => (
                  <TableCell key={h} sx={{ fontWeight: 700, textTransform: 'uppercase', fontSize: 11, letterSpacing: '.06em', color: 'text.secondary', whiteSpace: 'nowrap' }}>{h}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.employeeUserId} hover>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{r.employeeCode}</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{r.fullName}</TableCell>
                  <TableCell>{formatTime(r.checkInAt) ?? <Dash />}</TableCell>
                  <TableCell>{formatTime(r.checkOutAt) ?? <Dash />}</TableCell>
                  <TableCell>{formatDuration(r.workedMinutes) ?? <Dash />}</TableCell>
                  <TableCell><StatusChip status={r.status} /></TableCell>
                  <TableCell><SourceTag source={r.source} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Card>
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

  return (
    <Stack spacing={3}>
      {/* Punch card */}
      <Card variant="outlined">
        <CardContent sx={{ p: 3 }}>
          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}><CircularProgress size={24} /></Box>
          ) : !today ? (
            <Typography color="text.secondary" variant="body2">Attendance unavailable right now.</Typography>
          ) : (
            <Stack
              direction={{ xs: 'column', sm: 'row' }} spacing={3}
              sx={{ alignItems: { xs: 'stretch', sm: 'center' }, justifyContent: 'space-between' }}
            >
              <Box sx={{ flex: 1 }}>
                <Stack direction="row" spacing={0.8} sx={{ color: 'text.secondary', mb: 1.5, alignItems: 'center' }}>
                  <AccessTimeIcon fontSize="small" />
                  <Typography variant="body2">{formatDay(today.workDate)}</Typography>
                </Stack>

                <Stack
                  direction="row" spacing={3} divider={<Divider orientation="vertical" flexItem />}
                  sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                >
                  <Stat label="Check In" value={formatTime(today.record?.checkInAt ?? null) ?? <Dash />} />
                  <Stat label="Check Out" value={formatTime(today.record?.checkOutAt ?? null) ?? <Dash />} />
                  <Stat label="Total Hours" value={(today.canCheckOut ? elapsed : formatDuration(today.record?.workedMinutes ?? null)) ?? <Dash />} />
                  {today.record?.status && <Stat label="Status" value={<StatusChip status={today.record.status} />} />}
                </Stack>

                {today.record?.status === 'LATE' && (today.record.lateByMinutes ?? 0) > 0 && (
                  <Alert severity="warning" variant="outlined" sx={{ mt: 2, py: 0 }}>
                    Checked in {formatDuration(today.record.lateByMinutes)} past the grace period.
                  </Alert>
                )}
              </Box>

              {/* The button is driven only by the server's canCheckIn / canCheckOut flags. */}
              <Box>
                {today.canCheckIn && (
                  <Button
                    onClick={() => punch('in')} disabled={submitting} variant="contained" size="large"
                    startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : <LoginIcon />}
                  >
                    {submitting ? 'Checking in…' : 'Check In'}
                  </Button>
                )}
                {today.canCheckOut && (
                  <Button
                    onClick={() => punch('out')} disabled={submitting} variant="contained" size="large" color="error"
                    startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : <LogoutIcon />}
                  >
                    {submitting ? 'Checking out…' : 'Check Out'}
                  </Button>
                )}
                {!today.canCheckIn && !today.canCheckOut && (
                  <Chip icon={<CheckCircleIcon />} label="Day complete" color="success" variant="outlined" />
                )}
              </Box>
            </Stack>
          )}
        </CardContent>
      </Card>

      {/* Own history */}
      <Box>
        <SectionHeading title="My recent attendance" hint="Last 30 days" />
        <Card variant="outlined">
          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}><CircularProgress size={28} /></Box>
          ) : history.length === 0 ? (
            <EmptyState icon={<EventBusyIcon fontSize="large" />} title="No attendance yet" message="Your punches will appear here once you check in." />
          ) : (
            <TableContainer sx={{ overflowX: 'auto' }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    {['Date', 'Check In', 'Check Out', 'Hours', 'Status', 'Source'].map((h) => (
                      <TableCell key={h} sx={{ fontWeight: 700, textTransform: 'uppercase', fontSize: 11, letterSpacing: '.06em', color: 'text.secondary', whiteSpace: 'nowrap' }}>{h}</TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {history.map((r) => (
                    <TableRow key={r.workDate} hover>
                      <TableCell sx={{ fontWeight: 600 }}>{formatDay(r.workDate)}</TableCell>
                      <TableCell>{formatTime(r.checkInAt) ?? <Dash />}</TableCell>
                      <TableCell>{formatTime(r.checkOutAt) ?? <Dash />}</TableCell>
                      <TableCell>{formatDuration(r.workedMinutes) ?? <Dash />}</TableCell>
                      <TableCell><StatusChip status={r.status} /></TableCell>
                      <TableCell><SourceTag source={r.source} /></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Card>
      </Box>
    </Stack>
  );
}

// ─── Regularization (request + my requests + pending approvals) ───────────────

function RegularizationSection({ token, canApprove, showOwnRequests }: { token: string; canApprove: boolean; showOwnRequests: boolean }) {
  const { showToast } = useToast();
  const [myRequests, setMyRequests] = useState<RegularizationRecord[]>([]);
  const [pending, setPending] = useState<RegularizationRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showRequest, setShowRequest] = useState(false);
  const [rejecting, setRejecting] = useState<RegularizationRecord | null>(null);

  const loadAll = useCallback(() => {
    const calls: Promise<unknown>[] = [];
    if (showOwnRequests) calls.push(regularizationApi.mine(token).then(setMyRequests));
    if (canApprove) calls.push(regularizationApi.pending(token).then(setPending));
    return Promise.all(calls)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load regularization requests'))
      .finally(() => setLoading(false));
  }, [token, canApprove, showOwnRequests, showToast]);

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
    <Stack spacing={3}>
      {showOwnRequests && (
        <>
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1.5 }}>
            <SectionHeading title="Attendance Regularization" hint="Request corrections for missed or incorrect punches." />
            <Button onClick={() => setShowRequest(true)} variant="contained" startIcon={<AddIcon />}>
              Request Regularization
            </Button>
          </Stack>

          {/* My Regularization Requests */}
          <Box>
            <Typography variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '.06em', mb: 1.2 }}>My Requests</Typography>
            <Card variant="outlined">
              {loading ? (
                <Box sx={{ display: 'flex', justifyContent: 'center', py: 5 }}><CircularProgress size={24} /></Box>
              ) : myRequests.length === 0 ? (
                <Box sx={{ py: 4, textAlign: 'center' }}><Typography variant="body2" color="text.secondary">No requests submitted yet.</Typography></Box>
              ) : (
                <TableContainer sx={{ overflowX: 'auto' }}>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        {['Date', 'Requested In', 'Requested Out', 'Reason', 'Status', 'Reviewer Note'].map(h => (
                          <TableCell key={h} sx={{ fontWeight: 700, textTransform: 'uppercase', fontSize: 11, letterSpacing: '.06em', color: 'text.secondary', whiteSpace: 'nowrap' }}>{h}</TableCell>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {myRequests.map(r => (
                        <TableRow key={r.id} hover>
                          <TableCell sx={{ fontWeight: 600 }}>{r.attendanceDate}</TableCell>
                          <TableCell>{fmtDateTime(r.requestedCheckIn)}</TableCell>
                          <TableCell>{fmtDateTime(r.requestedCheckOut)}</TableCell>
                          <TableCell sx={{ maxWidth: 220 }}>{r.reason}</TableCell>
                          <TableCell><RegularizationStatusChip status={r.status} /></TableCell>
                          <TableCell>{r.reviewComment ?? '—'}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </Card>
          </Box>
        </>
      )}

      {/* Pending Approvals — Manager / HR Admin / Super Admin only */}
      {canApprove && (
        <Box>
          {!showOwnRequests && (
            <SectionHeading title="Attendance Regularization" hint="Review corrections your team has requested for missed or incorrect punches." />
          )}
          <Typography variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '.06em', mb: 1.2 }}>Pending Approvals</Typography>
          <Card variant="outlined">
            {pending.length === 0 ? (
              <Box sx={{ py: 4, textAlign: 'center' }}><Typography variant="body2" color="text.secondary">No pending requests.</Typography></Box>
            ) : (
              <TableContainer sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      {['Employee', 'Date', 'Requested In', 'Requested Out', 'Reason', 'Actions'].map(h => (
                        <TableCell key={h} sx={{ fontWeight: 700, textTransform: 'uppercase', fontSize: 11, letterSpacing: '.06em', color: 'text.secondary', whiteSpace: 'nowrap' }}>{h}</TableCell>
                      ))}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {pending.map(r => (
                      <TableRow key={r.id} hover>
                        <TableCell sx={{ fontWeight: 600 }}>
                          {r.employeeName}
                          <Typography variant="caption" color="text.disabled" sx={{ display: 'block' }}>{r.employeeEmail}</Typography>
                        </TableCell>
                        <TableCell>{r.attendanceDate}</TableCell>
                        <TableCell>{fmtDateTime(r.requestedCheckIn)}</TableCell>
                        <TableCell>{fmtDateTime(r.requestedCheckOut)}</TableCell>
                        <TableCell sx={{ maxWidth: 220 }}>{r.reason}</TableCell>
                        <TableCell>
                          <Stack direction="row" spacing={1}>
                            <Button size="small" variant="outlined" color="success" onClick={() => handleApprove(r.id)}>Approve</Button>
                            <Button size="small" variant="outlined" color="error" onClick={() => setRejecting(r)}>Reject</Button>
                          </Stack>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Card>
        </Box>
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
    </Stack>
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
    <Box>
      <Stack direction="row" sx={{ alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1.5, mb: 1.5 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          {scope === 'team' ? <GroupsIcon color="action" /> : <CorporateFareIcon color="action" />}
          <SectionHeading
            title={scope === 'team' ? 'Team attendance' : 'Organization attendance'}
            hint={scope === 'team'
              ? 'Your current direct reports for the selected day.'
              : 'All active employees for the selected day.'}
          />
        </Stack>
        <TextField
          label="Date" type="date" size="small" value={date}
          slotProps={{ inputLabel: { shrink: true } }}
          onChange={(e) => setDate(e.target.value)}
        />
      </Stack>
      <RosterTable
        rows={rows}
        loading={loading}
        emptyMessage={scope === 'team'
          ? 'No direct reports are assigned to you yet.'
          : 'No employee records for this date.'}
      />
    </Box>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AttendancePage() {
  // The router has no role guard, so — like Shell — the page resolves the role itself.
  const token = useAuthStore((s) => s.token)!;
  const role = toShellRole(useAuthStore((s) => s.user?.role));
  const isEmployee = role === 'Employee';
  const canApprove = role === 'Manager' || role === 'HR Admin' || role === 'Super Admin';

  const { theme: appTheme } = useAppTheme();
  const muiTheme = useMemo(() => buildMuiTheme(appTheme === 'light' ? 'light' : 'dark'), [appTheme]);

  // Check In / Check Out is an Employee-only action — Manager/HR Admin/Super Admin get an
  // oversight-only view (team/org roster + regularization approvals), never a punch card.
  const subtitle = role === 'Manager'
    ? 'Review your team’s attendance for any day.'
    : role === 'HR Admin' || role === 'Super Admin'
      ? 'Review attendance across the organization.'
      : 'Punch in when you start your day and out when you finish.';

  return (
    <MuiThemeProvider theme={muiTheme}>
      <Box>
        <Box sx={{ mb: 3 }}>
          <Typography variant="h4">Attendance</Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mt: 0.5 }}>{subtitle}</Typography>
        </Box>

        <Paper elevation={0} sx={{ bgcolor: 'transparent' }}>
          <Stack spacing={4}>
            {isEmployee && <MyAttendance />}
            <RegularizationSection token={token} canApprove={canApprove} showOwnRequests={isEmployee} />
            {role === 'Manager' && <DayRoster scope="team" />}
            {(role === 'HR Admin' || role === 'Super Admin') && <DayRoster scope="all" />}
          </Stack>
        </Paper>
      </Box>
    </MuiThemeProvider>
  );
}
