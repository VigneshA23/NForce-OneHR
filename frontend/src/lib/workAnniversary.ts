// Pure date logic for the work-anniversary celebration overlay, kept separate from the React
// component so it's trivially testable and reusable without a browser.

const STORAGE_KEY = 'onehr.workAnniversaryShown';

/**
 * Years completed if `today` is the anniversary of `joiningDateIso`, else null.
 * Returns null (never throws) for a missing/unparseable date, or for the join date itself
 * (0 years — not yet an anniversary). Treats Feb 29 joiners as anniversary-ing on Feb 28 in a
 * non-leap year, so they're never skipped for three years out of four.
 */
export function getAnniversaryYears(joiningDateIso: string | null | undefined, today: Date): number | null {
  if (!joiningDateIso) return null;
  const joined = new Date(joiningDateIso + 'T00:00:00');
  if (isNaN(joined.getTime())) return null;

  const joinedMonth = joined.getMonth();
  const joinedDay = joined.getDate();
  const todayMonth = today.getMonth();
  const todayDay = today.getDate();

  const isLeapDayJoin = joinedMonth === 1 && joinedDay === 29;
  const todayIsFeb28InNonLeapYear =
    todayMonth === 1 && todayDay === 28 && !isLeapYear(today.getFullYear());

  const matches = (todayMonth === joinedMonth && todayDay === joinedDay)
    || (isLeapDayJoin && todayIsFeb28InNonLeapYear);
  if (!matches) return null;

  const years = today.getFullYear() - joined.getFullYear();
  return years > 0 ? years : null;
}

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

/** Takes the store as a parameter (rather than reaching for `window.localStorage` directly) so
 * this is exercisable from a plain-Node test — same pattern as themePreference.ts's
 * readStoredTheme. Real call sites just pass `window.localStorage`. */
type Store = Pick<Storage, 'getItem' | 'setItem'>;

/** All persisted "last shown" state, keyed by user email (userId isn't available in the auth
 * store — see AuthUser — and email is already the stable per-account identifier used elsewhere,
 * e.g. the profile-sync effect in Shell.tsx). */
function readShownMap(storage: Store): Record<string, number> {
  try {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

export function hasShownAnniversaryThisYear(storage: Store, email: string, year: number): boolean {
  return readShownMap(storage)[email] === year;
}

export function markAnniversaryShown(storage: Store, email: string, year: number): void {
  try {
    const map = readShownMap(storage);
    map[email] = year;
    storage.setItem(STORAGE_KEY, JSON.stringify(map));
  } catch {
    // best effort — worst case the celebration reappears next load, not a functional break
  }
}
