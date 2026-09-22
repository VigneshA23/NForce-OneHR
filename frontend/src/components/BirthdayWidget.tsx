import { useEffect, useState } from 'react';
import { Cake } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { birthdaysApi, type BirthdayEntry } from '../api/birthdays';
import { EmployeeAvatar } from './EmployeeAvatar';

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Today" / "Tomorrow" / "In N days" — mirrors DashboardPage's own UpcomingHolidays diffLabel. */
export function birthdayCountdownLabel(daysUntil: number): string {
  if (daysUntil === 0) return 'Today';
  if (daysUntil === 1) return 'Tomorrow';
  return `In ${daysUntil} days`;
}

/** Splits an already-backend-filtered (today..+7 days) list into the two display sections. */
export function groupBirthdaysBySection(entries: BirthdayEntry[]): { today: BirthdayEntry[]; upcoming: BirthdayEntry[] } {
  return {
    today: entries.filter(e => e.today),
    upcoming: entries.filter(e => !e.today),
  };
}

function BirthdayRow({ entry }: { entry: BirthdayEntry }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0' }}>
      <EmployeeAvatar userId={entry.userId} name={entry.fullName} size={34} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {entry.fullName}
        </div>
        <div style={{ fontSize: 11, color: 'var(--txt-dim)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {entry.departmentName ?? '—'} · {MONTH_NAMES[entry.birthdayMonth - 1]} {entry.birthdayDay}
        </div>
      </div>
      <span style={{
        fontSize: 9.5, fontWeight: 700, padding: '3px 7px', borderRadius: 10, whiteSpace: 'nowrap',
        background: entry.today ? 'color-mix(in srgb, var(--brand) 12%, transparent)' : 'var(--raised2)',
        color: entry.today ? 'var(--brand)' : 'var(--txt-dim)',
        textTransform: 'uppercase', letterSpacing: '.05em',
      }}>
        {birthdayCountdownLabel(entry.daysUntil)}
      </span>
    </div>
  );
}

/**
 * Org-wide "Birthdays" card — self-contained (reads its own auth token, fetches its own data),
 * same pattern as AttendanceHeroBanner. Shown on every role's dashboard (ONEHR Birthday Widget
 * user story: org-wide visibility, not scoped to a manager's team or HR).
 */
export function BirthdayWidget() {
  const token = useAuthStore(s => s.token) ?? '';
  const [entries, setEntries] = useState<BirthdayEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    birthdaysApi.upcoming(token)
      .then(rows => { if (!cancelled) setEntries(rows); })
      .catch(() => { if (!cancelled) setEntries([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token]);

  const { today, upcoming } = groupBirthdaysBySection(entries);

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 22px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <Cake size={15} style={{ color: 'var(--brand)' }} />
        <span style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          Birthdays
        </span>
      </div>

      {loading ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '8px 0' }}>Loading…</div>
      ) : entries.length === 0 ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', padding: '8px 0' }}>
          No birthdays today or in the next 7 days.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {today.length > 0 && (
            <div style={{ marginBottom: upcoming.length > 0 ? 6 : 0 }}>
              <div style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 2 }}>
                Today
              </div>
              {today.map(e => <BirthdayRow key={e.userId} entry={e} />)}
            </div>
          )}
          {upcoming.length > 0 && (
            <div>
              <div style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 2 }}>
                Upcoming
              </div>
              {upcoming.map(e => <BirthdayRow key={e.userId} entry={e} />)}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
