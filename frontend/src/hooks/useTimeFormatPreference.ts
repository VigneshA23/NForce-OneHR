import { useCallback, useState } from 'react';

export type TimeFormat = '12h' | '24h';

const STORAGE_KEY = 'onehr.attendance.timeFormat';

function readStored(): TimeFormat {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === '24h' ? '24h' : '12h';
  } catch {
    // localStorage unavailable (private mode, etc.) — fall back to the default silently.
    return '12h';
  }
}

/** Persisted 12h/24h display preference for the Attendance page. */
export function useTimeFormatPreference(): [TimeFormat, () => void] {
  const [format, setFormat] = useState<TimeFormat>(readStored);

  const toggle = useCallback(() => {
    setFormat((prev) => {
      const next: TimeFormat = prev === '12h' ? '24h' : '12h';
      try { localStorage.setItem(STORAGE_KEY, next); } catch { /* best effort */ }
      return next;
    });
  }, []);

  return [format, toggle];
}
