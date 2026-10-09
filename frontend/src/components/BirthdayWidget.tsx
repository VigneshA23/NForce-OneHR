import { useEffect, useState } from 'react';
import { Cake, PartyPopper, Sparkles } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { birthdaysApi, type BirthdayEntry } from '../api/birthdays';
import { EmployeeAvatar } from './EmployeeAvatar';
import { BirthdayWishModal } from './BirthdayWishModal';

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Today" / "Tomorrow" / "In N days" — mirrors DashboardPage's own UpcomingHolidays diffLabel. */
export function birthdayCountdownLabel(daysUntil: number): string {
  if (daysUntil === 0) return 'Today';
  if (daysUntil === 1) return 'Tomorrow';
  return `In ${daysUntil} days`;
}

/**
 * Splits an already-backend-filtered (today..+7 days) list into two display tiers — Today and This
 * Week (everything from tomorrow through day 7) — `daysUntil`/`today` already carry everything
 * needed for this, so it's a pure regrouping of what the backend already returns, no new data.
 */
export function groupBirthdaysBySection(entries: BirthdayEntry[]): {
  today: BirthdayEntry[];
  thisWeek: BirthdayEntry[];
} {
  return {
    today: entries.filter(e => e.daysUntil === 0),
    thisWeek: entries.filter(e => e.daysUntil > 0),
  };
}

type Tier = 'today' | 'thisWeek';

// Section markers use themed lucide icons (colored via var(--brand)) rather than Unicode emoji —
// consistent, recolorable, and matches the icon treatment already used for the "Send Wishes"
// button and NotificationsPage's birthday entries, instead of OS-rendered emoji glyphs.
const TIER_META: Record<Tier, { label: string; icon: LucideIcon }> = {
  today: { label: 'Today', icon: Cake },
  thisWeek: { label: 'This Week', icon: Sparkles },
};

function BirthdayRow({ entry, tier, onSendWishes, isSelf }: { entry: BirthdayEntry; tier: Tier; onSendWishes?: (entry: BirthdayEntry) => void; isSelf?: boolean }) {
  const isToday = tier === 'today';
  const showSendWishes = isToday && onSendWishes && !isSelf;
  return (
    <div
      style={{
        display: 'flex', alignItems: 'center', gap: 10, padding: isToday ? '9px 10px' : '8px 0',
        borderRadius: isToday ? 9 : 0,
        // Today's row gets a soft themed glow/border so it reads as visually distinct from the
        // plain list rows below it — the rest of the tiers stay on the existing flat list styling
        // (per the brief: don't change the existing upcoming-birthday visual treatment, only add
        // distinction for today).
        background: isToday ? 'color-mix(in srgb, var(--brand) 7%, transparent)' : 'transparent',
        border: isToday ? '1px solid color-mix(in srgb, var(--brand) 22%, transparent)' : 'none',
      }}
    >
      <EmployeeAvatar userId={entry.userId} name={entry.fullName} size={34} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {entry.fullName}
        </div>
        <div style={{ fontSize: 11, color: 'var(--txt-dim)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {entry.departmentName ?? '—'} · {MONTH_NAMES[entry.birthdayMonth - 1]} {entry.birthdayDay}
        </div>
      </div>

      {showSendWishes ? (
        <button
          type="button"
          onClick={() => onSendWishes(entry)}
          style={{
            display: 'flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap',
            fontSize: 11, fontWeight: 700, padding: '5px 10px', borderRadius: 999,
            background: 'var(--brand)', color: '#fff', border: 'none', cursor: 'pointer',
          }}
        >
          <PartyPopper size={12} aria-hidden="true" /> Send Wishes
        </button>
      ) : (
        <span style={{
          fontSize: 9.5, fontWeight: 700, padding: '3px 7px', borderRadius: 10, whiteSpace: 'nowrap',
          background: 'var(--raised2)', color: 'var(--txt-dim)',
          textTransform: 'uppercase', letterSpacing: '.05em',
        }}>
          {birthdayCountdownLabel(entry.daysUntil)}
        </span>
      )}
    </div>
  );
}

function TierSection({ tier, entries, marginBottom, onSendWishes, selfName }: {
  tier: Tier; entries: BirthdayEntry[]; marginBottom: number; onSendWishes?: (entry: BirthdayEntry) => void; selfName?: string;
}) {
  if (entries.length === 0) return null;
  const meta = TIER_META[tier];
  const Icon = meta.icon;
  return (
    <div style={{ marginBottom }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 10.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>
        <Icon size={11} style={{ color: 'var(--brand)' }} aria-hidden="true" /> {meta.label}
      </div>
      {entries.map(e => (
        <BirthdayRow key={e.userId} entry={e} tier={tier} onSendWishes={onSendWishes} isSelf={!!selfName && e.fullName === selfName} />
      ))}
    </div>
  );
}

/**
 * Org-wide "Birthdays" card — self-contained (reads its own auth token, fetches its own data),
 * same pattern as AttendanceHeroBanner. Shown on every role's dashboard (ONEHR Birthday Widget
 * user story: org-wide visibility, not scoped to a manager's team or HR).
 *
 * "Today" already reads as the prominent, HR-visible section below (glow/border + Send Wishes) —
 * there is no separate summary banner on top of it, to avoid saying "today's birthday" twice.
 */
export function BirthdayWidget() {
  const token = useAuthStore(s => s.token) ?? '';
  // AuthUser carries no stable id (see store/authStore.ts), only fullName — a best-effort match to
  // hide "Send Wishes" on the viewer's own row. Purely a UI nicety: BirthdayWishService rejects a
  // self-wish server-side regardless, so a rare same-name collision fails safe, not silently.
  const selfName = useAuthStore(s => s.user?.fullName);
  const [entries, setEntries] = useState<BirthdayEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [wishTarget, setWishTarget] = useState<BirthdayEntry | null>(null);

  useEffect(() => {
    let cancelled = false;
    birthdaysApi.upcoming(token)
      .then(rows => { if (!cancelled) setEntries(rows); })
      .catch(() => { if (!cancelled) setEntries([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token]);

  const { today, thisWeek } = groupBirthdaysBySection(entries);

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
          <TierSection tier="today" entries={today} marginBottom={thisWeek.length ? 10 : 0} onSendWishes={setWishTarget} selfName={selfName} />
          <TierSection tier="thisWeek" entries={thisWeek} marginBottom={0} />
        </div>
      )}

      {wishTarget && (
        <BirthdayWishModal
          toUserId={wishTarget.userId}
          toName={wishTarget.fullName}
          department={wishTarget.departmentName}
          designation={wishTarget.designationName}
          onClose={() => setWishTarget(null)}
        />
      )}
    </div>
  );
}
