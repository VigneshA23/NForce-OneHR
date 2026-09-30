// Pure date logic for the birthday celebration overlay — mirrors workAnniversary.ts's structure
// (separate from the React component so it's trivially testable and reusable without a browser),
// but gated per CALENDAR DAY rather than per year: a birthday recurs every year on the same
// month/day, so "have we shown it" has to reset the next day, not the next year.

const STORAGE_KEY = 'onehr.birthdayShown';

/** True if `today` is the same month/day as `dateOfBirthIso`. Never throws for a missing/
 * unparseable date. Treats a Feb 29 birthday as falling on Feb 28 in a non-leap year, same
 * convention as workAnniversary.ts's getAnniversaryYears (and the backend's own
 * EmployeeService#nextBirthdayOccurrence/clampedDate, which this mirrors). */
export function isBirthdayToday(dateOfBirthIso: string | null | undefined, today: Date): boolean {
  if (!dateOfBirthIso) return false;
  const dob = new Date(dateOfBirthIso + 'T00:00:00');
  if (isNaN(dob.getTime())) return false;

  const dobMonth = dob.getMonth();
  const dobDay = dob.getDate();
  const todayMonth = today.getMonth();
  const todayDay = today.getDate();

  const isLeapDayBirthday = dobMonth === 1 && dobDay === 29;
  const todayIsFeb28InNonLeapYear =
    todayMonth === 1 && todayDay === 28 && !isLeapYear(today.getFullYear());

  return (todayMonth === dobMonth && todayDay === dobDay)
    || (isLeapDayBirthday && todayIsFeb28InNonLeapYear);
}

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

/** `YYYY-MM-DD` in the viewer's local time — the "have we shown it today" boundary. Local time
 * (not UTC) matches how a person actually experiences "today". */
export function localDateKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** Takes the store as a parameter rather than reaching for `window.localStorage` directly — same
 * pattern as workAnniversary.ts/themePreference.ts, so this is exercisable from a plain-Node test. */
type Store = Pick<Storage, 'getItem' | 'setItem'>;

/** All persisted "last shown" state, keyed by user email — same reasoning as
 * workAnniversary.ts's readShownMap (userId isn't available in the auth store). */
function readShownMap(storage: Store): Record<string, string> {
  try {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

export function hasShownBirthdayToday(storage: Store, email: string, dayKey: string): boolean {
  return readShownMap(storage)[email] === dayKey;
}

export function markBirthdayShown(storage: Store, email: string, dayKey: string): void {
  try {
    const map = readShownMap(storage);
    map[email] = dayKey;
    storage.setItem(STORAGE_KEY, JSON.stringify(map));
  } catch {
    // best effort — worst case the celebration reappears next load, not a functional break
  }
}
