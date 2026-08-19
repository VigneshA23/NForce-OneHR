import { createContext, useContext, useMemo } from 'react';
import { useTimeFormatPreference, type TimeFormat } from '../hooks/useTimeFormatPreference';

interface TimeFormatContextValue {
  format: TimeFormat;
  toggle: () => void;
  /** Formats a zone-less server timestamp ("...THH:mm...") per the current preference. */
  formatTime: (iso: string | null) => string | null;
  formatDuration: (minutes: number | null) => string | null;
}

const TimeFormatContext = createContext<TimeFormatContextValue | null>(null);

// Backend LocalDateTime strings are naive wall-clock digits already in the record's own
// resolved zone (browser-reported at Check-In/Web Clock-In, see AttendanceService.resolveZone)
// — there is nothing left to convert. Parsing with 'Z' and formatting with timeZone: 'UTC' reads
// those digits back out verbatim, regardless of the *viewer's* own browser timezone. Previously
// this appended '+05:30' (assumed IST) and let toLocaleTimeString re-project into the viewer's
// local zone — for any employee whose resolved zone isn't IST, or any viewer whose browser isn't
// IST either, that mislabeled the digits and then shifted them again.
function formatTimeAs(format: TimeFormat, iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso + 'Z');
  if (isNaN(d.getTime())) return null;
  if (format === '24h') {
    return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC' });
  }
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'UTC' });
}

// Duration is format-independent (always "Xh Ym") — kept here only so callers have a single
// import for both, and so a future format that changes duration display has one place to change.
function formatDurationMinutes(minutes: number | null): string | null {
  if (minutes == null) return null;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** Attendance-page-scoped 12h/24h preference — see AttendancePage's usage for the call-site list. */
export function TimeFormatProvider({ children }: { children: React.ReactNode }) {
  const [format, toggle] = useTimeFormatPreference();

  const value = useMemo<TimeFormatContextValue>(() => ({
    format,
    toggle,
    formatTime: (iso) => formatTimeAs(format, iso),
    formatDuration: formatDurationMinutes,
  }), [format, toggle]);

  return <TimeFormatContext.Provider value={value}>{children}</TimeFormatContext.Provider>;
}

export function useTimeFormat(): TimeFormatContextValue {
  const ctx = useContext(TimeFormatContext);
  if (!ctx) throw new Error('useTimeFormat must be used inside TimeFormatProvider');
  return ctx;
}
