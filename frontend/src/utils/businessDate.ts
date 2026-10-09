// The org's business timezone — mirrors the backend's authoritative "what business day is it"
// clock (see AttendanceProperties.zone's own Javadoc on the backend; defaults to Asia/Kolkata,
// see backend/src/main/resources/application.yml's ATTENDANCE_ZONE). Used specifically for
// Effective From date pickers (Add User, Add Employee, Bulk-Edit Team Assignment) so a past/
// future boundary decision made here never disagrees with the backend's own validation of the
// exact same field — unlike the plain UTC-derived `todayIsoDate()` helpers used elsewhere on
// these pages for unrelated date ranges/filters, which this deliberately does not replace.
const BUSINESS_TIMEZONE = 'Asia/Kolkata';

/**
 * Today's date as "YYYY-MM-DD" in the org's business timezone — via `Intl`'s own IANA timezone
 * database (never a hardcoded UTC+5:30 offset), so this stays correct even if the business zone
 * is ever redeployed to a different IANA zone.
 */
export function businessTodayIsoDate(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: BUSINESS_TIMEZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date());
}
