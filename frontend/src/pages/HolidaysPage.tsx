import { useEffect, useMemo, useRef, useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import { CalendarDays, ChevronLeft, ChevronRight, Pencil, Plus, Trash2, X, CalendarHeart } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { hasPermission } from '../lib/permissions';
import { holidaysApi, type HolidayRow } from '../api/holidays';
import { orgApi, type LocationRow } from '../api/org';

// Split out of LeavePage.tsx (formerly the "Company Holidays" section there) — same component
// code, API calls, filtering, and styling, just relocated onto its own route/page. See LeavePage
// for the Applied Leaves / Available Leave Balance sections that stayed behind.

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
// Holiday table only — compact, center-aligned.
const holidayThStyle: React.CSSProperties = { ...thStyle, textAlign: 'center', padding: '8px 10px' };
const holidayTdStyle: React.CSSProperties = { ...tdStyle, textAlign: 'center', padding: '8px 10px' };
// Mirrors CreateHolidayRequest's @Pattern on the backend: must contain at least
// one actual letter (rejects emoji-only, symbol-only, and digit-only input),
// otherwise letters (Unicode-aware — accented characters like "Deepāvali" are
// \p{L}), digits ("Independence Day 2026"), spaces, apostrophes, and hyphens
// ("New Year's Day", "Eid-ul-Fitr").
const HOLIDAY_NAME_PATTERN = /^(?=.*[\p{L}])[\p{L}\p{N} '-]+$/u;
const HOLIDAY_NAME_MAX_LENGTH = 100;

// Primary add-action button for this page — matches LeavePage's "Request Leave" button in
// size/weight rather than drifting independently.
const primaryButtonStyle: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

const HOLIDAY_STATUS_BADGE = (active: boolean) => (
  <span style={{
    display: 'inline-flex', alignItems: 'center', gap: 4,
    padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    background: active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
    color: active ? 'var(--ok)' : 'var(--txt-dim)',
  }}>
    {active ? 'Active' : 'Inactive'}
  </span>
);

function formatHolidayDate(iso: string) {
  const d = new Date(iso + 'T00:00:00');
  return d.toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric' });
}

function daysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function toISODate(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

// Purely presentational — computed from the same `holidays` array already loaded by the page
// below via the existing holidaysApi calls; no new data/API is introduced.
function UpcomingHolidayCard({ holiday, daysAway }: { holiday: HolidayRow; daysAway: number }) {
  const d = new Date(holiday.holidayDate + 'T00:00:00');
  return (
    <div className="nf-section-enter nf-leave-balance-card" style={{
      background: 'linear-gradient(160deg, var(--panel) 0%, color-mix(in srgb, var(--brand) 5%, var(--panel)) 100%)',
      border: '1px solid var(--line)', borderTop: '2px solid var(--brand)', borderRadius: 10, padding: '14px 16px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{ width: 52, height: 52, borderRadius: 10, flexShrink: 0, background: 'color-mix(in srgb, var(--brand) 14%, var(--raised))', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ fontSize: 9.5, fontWeight: 700, color: 'var(--brand)', textTransform: 'uppercase' }}>{d.toLocaleDateString(undefined, { month: 'short' })}</span>
        <span style={{ fontSize: 18, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', lineHeight: 1 }}>{d.getDate()}</span>
      </div>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={holiday.holidayName}>{holiday.holidayName}</div>
        <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 2 }}>{d.toLocaleDateString(undefined, { weekday: 'long' })}</div>
        <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3 }}>{daysAway === 0 ? 'Today' : daysAway === 1 ? 'Tomorrow' : `In ${daysAway} days`}</div>
      </div>
    </div>
  );
}

export function HolidayMonthCalendar({ holidays }: { holidays: HolidayRow[] }) {
  const today = new Date();
  const [viewDate, setViewDate] = useState(new Date(today.getFullYear(), today.getMonth(), 1));

  const year = viewDate.getFullYear();
  const month = viewDate.getMonth();
  const totalDays = daysInMonth(year, month);
  const firstWeekday = new Date(year, month, 1).getDay();
  const todayIso = toISODate(today.getFullYear(), today.getMonth(), today.getDate());
  // Group by date: two or more holidays can fall on the same date (e.g. "All Locations"),
  // and a single-value Map would silently drop all but the last.
  const holidaysByDate = new Map<string, HolidayRow[]>();
  for (const h of holidays) {
    const list = holidaysByDate.get(h.holidayDate);
    if (list) list.push(h);
    else holidaysByDate.set(h.holidayDate, [h]);
  }

  const cells: Array<{ day: number; iso: string } | null> = [];
  for (let i = 0; i < firstWeekday; i++) cells.push(null);
  for (let d = 1; d <= totalDays; d++) cells.push({ day: d, iso: toISODate(year, month, d) });
  while (cells.length % 7 !== 0) cells.push(null);

  const navBtn: React.CSSProperties = {
    background: 'color-mix(in srgb, var(--brand) 10%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 30%, var(--line2))',
    borderRadius: 8, width: 28, height: 28, display: 'flex', alignItems: 'center', justifyContent: 'center',
    cursor: 'pointer', color: 'var(--brand)',
  };

  return (
    <div className="nf-section-enter" style={{
      background: 'linear-gradient(180deg, color-mix(in srgb, var(--brand) 6%, var(--panel)) 0%, var(--panel) 120px)',
      border: '1px solid var(--line)', borderRadius: 14, padding: 14, boxShadow: '0 6px 22px rgba(0,0,0,.14)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 12 }}>
        <button onClick={() => setViewDate(new Date(year, month - 1, 1))} aria-label="Previous month" style={navBtn}>
          <ChevronLeft size={15} />
        </button>
        <div style={{ textAlign: 'center', minWidth: 0 }}>
          <div style={{ fontFamily: 'Inter, sans-serif', fontWeight: 800, fontSize: 17, color: 'var(--txt)', letterSpacing: '-.01em', lineHeight: 1.1 }}>
            {viewDate.toLocaleDateString(undefined, { month: 'long' })}
            <span style={{ color: 'var(--brand)', marginLeft: 6 }}>{year}</span>
          </div>
        </div>
        <button onClick={() => setViewDate(new Date(year, month + 1, 1))} aria-label="Next month" style={navBtn}>
          <ChevronRight size={15} />
        </button>
      </div>

      {/* minmax(0, 1fr) hard-caps every track so long names truncate instead of widening columns. */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 4, marginBottom: 5 }}>
        {WEEKDAY_LABELS.map((d, i) => (
          <div key={d} style={{
            textAlign: 'center', fontSize: 10, fontWeight: 800, textTransform: 'uppercase', letterSpacing: '.05em', padding: '5px 0',
            borderRadius: 6, color: i === 0 || i === 6 ? 'var(--brand)' : 'var(--txt-mut)',
            background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))',
          }}>
            {d}
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 4 }}>
        {cells.map((c, i) => {
          if (!c) return <div key={i} />;
          const dayHolidays = holidaysByDate.get(c.iso) ?? [];
          const hasHoliday = dayHolidays.length > 0;
          const visible = dayHolidays.slice(0, 2);
          const extraCount = dayHolidays.length - visible.length;
          const isToday = c.iso === todayIso;
          const wd = (firstWeekday + c.day - 1) % 7;
          const weekend = wd === 0 || wd === 6;
          return (
            <div
              key={i}
              title={dayHolidays.map(h => h.holidayName).join(', ') || undefined}
              style={{
                // Fixed height: the cell never resizes with content; extras are summarised as "+N more".
                height: 74, minWidth: 0, width: '100%', overflow: 'hidden', boxSizing: 'border-box',
                borderRadius: 10, padding: '5px 5px 4px', position: 'relative',
                background: hasHoliday
                  ? 'linear-gradient(160deg, color-mix(in srgb, var(--brand) 30%, var(--panel)) 0%, color-mix(in srgb, var(--brand) 10%, var(--panel)) 100%)'
                  : weekend
                    ? 'color-mix(in srgb, var(--brand) 4%, var(--raised))'
                    : 'linear-gradient(160deg, var(--raised) 0%, var(--panel) 100%)',
                border: isToday ? '2px solid var(--brand)' : hasHoliday ? '1px solid color-mix(in srgb, var(--brand) 65%, transparent)' : '1px solid var(--line)',
                boxShadow: isToday ? '0 0 0 3px color-mix(in srgb, var(--brand) 20%, transparent)' : hasHoliday ? '0 2px 8px color-mix(in srgb, var(--brand) 22%, transparent)' : 'none',
                display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 3,
              }}
            >
              <span style={{
                fontSize: 11.5, fontWeight: isToday || hasHoliday ? 800 : 600, lineHeight: 1,
                color: isToday ? '#fff' : hasHoliday ? 'var(--brand)' : weekend ? 'var(--txt-mut)' : 'var(--txt)',
                background: isToday ? 'var(--brand)' : 'transparent',
                borderRadius: 999, minWidth: 18, height: 18, padding: '0 4px', boxSizing: 'border-box',
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              }}>
                {c.day}
              </span>
              {visible.map(h => (
                <span key={h.id} style={{
                  fontSize: 9, fontWeight: 700, color: '#fff', background: 'var(--brand)',
                  borderRadius: 5, padding: '2px 5px', lineHeight: 1.4,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  maxWidth: '100%', width: '100%', boxSizing: 'border-box', flexShrink: 0,
                }}>
                  {h.holidayName}
                </span>
              ))}
              {extraCount > 0 && (
                <span style={{ fontSize: 8.5, fontWeight: 800, color: 'var(--brand)', lineHeight: 1.2, flexShrink: 0 }}>
                  +{extraCount} more
                </span>
              )}
            </div>
          );
        })}
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, marginTop: 12, paddingTop: 10, borderTop: '1px dashed var(--line2)', fontSize: 10.5, fontWeight: 600, color: 'var(--txt-mut)' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={{ width: 10, height: 10, borderRadius: 3, background: 'var(--brand)' }} /> Holiday
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={{ width: 10, height: 10, borderRadius: 999, background: 'var(--brand)' }} /> Today
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={{ width: 10, height: 10, borderRadius: 3, background: 'color-mix(in srgb, var(--brand) 14%, var(--raised))', border: '1px solid var(--line2)' }} /> Weekend
        </span>
      </div>
    </div>
  );
}

function AddHolidayModal({ token, editing, onClose, onCreated }: { token: string; editing?: HolidayRow; onClose: () => void; onCreated: (locationId: string) => void }) {
  const [holidayName, setHolidayName] = useState(editing?.holidayName ?? '');
  const [holidayDate, setHolidayDate] = useState(editing?.holidayDate ?? '');
  const [locationId, setLocationId] = useState(editing?.locationId ?? '');
  const [locations, setLocations] = useState<LocationRow[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const firstRef = useRef<HTMLInputElement>(null);
  const todayIso = new Date().toISOString().slice(0, 10);

  useEffect(() => {
    firstRef.current?.focus();
    orgApi.listLocations(token).then(setLocations).catch(() => {});
  }, [token]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const name = holidayName.trim();
    if (!name) { setError('Holiday name is required'); return; }
    if (name.length > HOLIDAY_NAME_MAX_LENGTH) { setError(`Holiday name must be ${HOLIDAY_NAME_MAX_LENGTH} characters or fewer`); return; }
    if (!HOLIDAY_NAME_PATTERN.test(name)) {
      setError('Holiday name must contain at least one letter, and only letters, numbers, spaces, apostrophes, or hyphens');
      return;
    }
    if (!holidayDate) { setError('Date is required'); return; }
    if (!editing && holidayDate < todayIso) { setError('Holiday date cannot be in the past'); return; }
    if (!locationId) { setError('Location is required'); return; }

    setLoading(true);
    try {
      if (editing) {
        await holidaysApi.updateHoliday(token, editing.id, { holidayName: name, holidayDate, locationId });
      } else {
        await holidaysApi.createHoliday(token, { holidayName: name, holidayDate, locationId });
      }
      onCreated(locationId);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle} onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={modalStyle}>
        <ModalHeader title={editing ? 'Edit Holiday' : 'Add Holiday'} onClose={onClose} />
        {error && <div style={{ margin: '16px 20px 0', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
        <form onSubmit={submit} style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Field label="Holiday Name *">
            <input ref={firstRef} maxLength={HOLIDAY_NAME_MAX_LENGTH} style={inputStyle} value={holidayName} onChange={e => setHolidayName(e.target.value)} placeholder="e.g. Diwali" />
          </Field>
          <Field label="Date *">
            <input type="date" min={editing ? undefined : todayIso} style={inputStyle} value={holidayDate} onChange={e => setHolidayDate(e.target.value)} />
          </Field>
          <Field label="Location *">
            <select style={inputStyle} value={locationId} onChange={e => setLocationId(e.target.value)}>
              <option value="">Select a location…</option>
              {/* Active-only for a NEW selection — a deactivated (not deleted) location shouldn't
                  be pickable going forward; `locationId` keeps this holiday's existing location
                  visible/selected if it was assigned before that location was deactivated. */}
              {locations.filter(l => l.active !== false || l.id === locationId).map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          </Field>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={loading} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>{loading ? 'Saving…' : 'Save'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function HolidaysPage() {
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  // Each gate is the permission HolidayController requires for that call — all four seeded to
  // exactly HR_ADMIN + SUPER_ADMIN (V225), the roles this page used to check.
  const isAdmin = hasPermission(permissions, 'holidays', 'VIEW', 'ALL');
  const canCreate = hasPermission(permissions, 'holidays', 'CREATE', 'ALL');
  const canEdit = hasPermission(permissions, 'holidays', 'EDIT', 'ALL');
  const canDelete = hasPermission(permissions, 'holidays', 'DELETE', 'ALL');
  const [holidays, setHolidays] = useState<HolidayRow[]>([]);
  const [holidayError, setHolidayError] = useState('');
  const [showAddHoliday, setShowAddHoliday] = useState(false);
  const [editingHoliday, setEditingHoliday] = useState<HolidayRow | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [adminLocations, setAdminLocations] = useState<LocationRow[]>([]);
  const [locationFilter, setLocationFilter] = useState(''); // admin only; '' = All Locations

  useEffect(() => {
    if (isAdmin && token) orgApi.listLocations(token).then(setAdminLocations).catch(() => {});
  }, [isAdmin, token]);

  // HR Admin/Super Admin manage holidays across locations, not just their own —
  // "my-location" would silently hide anything they create for a location that
  // isn't their own (or return nothing at all if they have no location set).
  async function fetchHolidays(overrideLocationId?: string) {
    const locId = overrideLocationId !== undefined ? overrideLocationId : locationFilter;
    setHolidayError('');
    try {
      const rows = isAdmin
        ? (locId ? await holidaysApi.listByLocation(token, locId) : await holidaysApi.listAll(token))
        : await holidaysApi.listForMyLocation(token);
      setHolidays(rows);
    } catch (e) {
      setHolidayError(e instanceof Error ? e.message : 'Failed to load holidays');
    }
  }

  useEffect(() => { if (token) fetchHolidays(); }, [token, isAdmin]);

  // An employee's tab may already be open when HR Admin edits/deletes a
  // holiday for their location — there's no push/websocket in this app, so
  // without this the tab would only pick up the change on its next full
  // navigation. Refetch when the tab regains focus/visibility instead of
  // requiring a manual reload.
  useEffect(() => {
    if (!token) return;
    function onFocus() { fetchHolidays(); }
    function onVisibility() { if (document.visibilityState === 'visible') fetchHolidays(); }
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, isAdmin, locationFilter]);

  async function handleDeleteHoliday(h: HolidayRow) {
    if (!window.confirm(`Delete "${h.holidayName}"? This can't be undone from here.`)) return;
    setDeletingId(h.id);
    try {
      await holidaysApi.deleteHoliday(token, h.id);
      await fetchHolidays();
    } catch (e) {
      setHolidayError(e instanceof Error ? e.message : 'Failed to delete holiday');
    } finally {
      setDeletingId(null);
    }
  }

  const holidayScopeLabel = isAdmin
    ? (locationFilter ? adminLocations.find(l => l.id === locationFilter)?.name : 'All Locations')
    : holidays[0]?.locationName;

  // Upcoming Holidays — purely presentational, derived from `holidays` (already loaded above
  // via the existing fetchHolidays()/holidaysApi calls); no new API request.
  const upcomingHolidays = useMemo(() => {
    const todayIso = new Date().toISOString().slice(0, 10);
    const todayMs = new Date(todayIso + 'T00:00:00').getTime();
    return holidays
      .filter(h => h.active && h.holidayDate >= todayIso)
      .sort((a, b) => a.holidayDate.localeCompare(b.holidayDate))
      .slice(0, 4)
      .map(h => ({ holiday: h, daysAway: Math.round((new Date(h.holidayDate + 'T00:00:00').getTime() - todayMs) / 86400000) }));
  }, [holidays]);

  return (
    <div>
      <div className="nf-section-enter nf-leave-header nf-page-hdr" style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 22,
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '18px 22px',
        backgroundImage: 'linear-gradient(120deg, color-mix(in srgb, var(--brand) 6%, var(--panel)) 0%, var(--panel) 55%)',
        flexWrap: 'wrap',
      }}>
        <PageTitleBlock icon={CalendarHeart}>
            <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>
              Company Holidays{holidayScopeLabel ? ` - ${holidayScopeLabel}` : ''}
            </h1>
            <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 3 }}>
              {isAdmin ? 'Holidays across the company, by location.' : 'Holidays for your work location.'}
            </p>
        </PageTitleBlock>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          {isAdmin && (
            <select
              value={locationFilter}
              onChange={e => { const v = e.target.value; setLocationFilter(v); fetchHolidays(v); }}
              style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)' }}
            >
              <option value="">All Locations</option>
              {adminLocations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          )}
          {canCreate && (
            <button onClick={() => setShowAddHoliday(true)} style={{ ...primaryButtonStyle, boxShadow: '0 2px 10px rgba(177,17,22,.25)' }}>
              <Plus size={14} /> Add Holiday
            </button>
          )}
        </div>
      </div>

      {holidayError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13, marginBottom: 14 }}>
          {holidayError}
        </div>
      )}

      {upcomingHolidays.length > 0 && (
        <div style={{ marginBottom: 20 }}>
          <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 10 }}>Upcoming Holidays</div>
          <div className="nf-autofit-mobile-safe" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
            {upcomingHolidays.map(u => <UpcomingHolidayCard key={u.holiday.id} holiday={u.holiday} daysAway={u.daysAway} />)}
          </div>
        </div>
      )}

      <div className={holidays.length > 0 ? 'nf-grid-side-collapse' : undefined} style={holidays.length > 0 ? { display: 'grid', gridTemplateColumns: 'minmax(310px, 380px) 1fr', gap: 20, alignItems: 'start' } : undefined}>
        {holidays.length > 0 && <HolidayMonthCalendar holidays={holidays} />}

        <div className="nf-section-enter" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
          {holidays.length === 0 ? (
            <div style={{ padding: 48, textAlign: 'center' }}>
              <CalendarDays size={28} aria-hidden="true" style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 10px' }} />
              <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>
                {isAdmin ? 'No holidays have been added yet.' : 'No holidays have been added for your location yet.'}
              </div>
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
                <colgroup>
                  {isAdmin ? (
                    <>
                      <col style={{ width: '28%' }} />
                      <col style={{ width: '22%' }} />
                      <col style={{ width: '20%' }} />
                      <col style={{ width: '15%' }} />
                      <col style={{ width: '15%' }} />
                    </>
                  ) : (
                    <>
                      <col style={{ width: '45%' }} />
                      <col style={{ width: '35%' }} />
                      <col style={{ width: '20%' }} />
                    </>
                  )}
                </colgroup>
                <thead>
                  <tr>
                    {(isAdmin ? ['Holiday Name', 'Date', 'Location', 'Status', 'Actions'] : ['Holiday Name', 'Date', 'Status']).map(h => <th key={h} style={holidayThStyle}>{h}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {holidays.map(h => (
                    <tr key={h.id} className="nf-leave-row">
                      <td style={{ ...holidayTdStyle, color: 'var(--txt)', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.holidayName}>{h.holidayName}</td>
                      <td style={holidayTdStyle}>{formatHolidayDate(h.holidayDate)}</td>
                      {isAdmin && <td style={{ ...holidayTdStyle, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.locationName}>{h.locationName}</td>}
                      <td style={holidayTdStyle}>{HOLIDAY_STATUS_BADGE(h.active)}</td>
                      {isAdmin && (
                        <td style={holidayTdStyle}>
                          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
                            {canEdit && <button
                              onClick={() => setEditingHoliday(h)}
                              aria-label={`Edit ${h.holidayName}`}
                              style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: 'var(--txt-mut)' }}
                            >
                              <Pencil size={12} />
                            </button>}
                            {canDelete && <button
                              onClick={() => handleDeleteHoliday(h)}
                              disabled={deletingId === h.id}
                              aria-label={`Delete ${h.holidayName}`}
                              style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: deletingId === h.id ? 'not-allowed' : 'pointer', color: 'var(--risk)', opacity: deletingId === h.id ? 0.5 : 1 }}
                            >
                              <Trash2 size={12} />
                            </button>}
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {(showAddHoliday || editingHoliday) && (
        <AddHolidayModal
          token={token}
          editing={editingHoliday ?? undefined}
          onClose={() => { setShowAddHoliday(false); setEditingHoliday(null); }}
          onCreated={(locId) => { setLocationFilter(locId); fetchHolidays(locId); }}
        />
      )}
    </div>
  );
}
