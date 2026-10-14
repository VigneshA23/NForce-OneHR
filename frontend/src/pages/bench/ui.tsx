import type { CSSProperties, ReactNode } from 'react';

/** Shared look for the bench screens — the same panel/input tokens ProjectAllocationsPage uses. */
export const card: CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '14px 16px' };
export const inputStyle: CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)', outline: 'none', fontFamily: 'inherit',
  width: '100%', boxSizing: 'border-box',
};
export const labelStyle: CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };
export const th: CSSProperties = { textAlign: 'left', padding: '6px 8px', fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' };
export const td: CSSProperties = { padding: '7px 8px', fontSize: 12.5, color: 'var(--txt)', borderBottom: '1px solid var(--line)' };

export const primaryBtn = (disabled = false): CSSProperties => ({
  padding: '8px 14px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 13, fontWeight: 600,
  color: '#fff', cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.7 : 1,
});
export const ghostBtn: CSSProperties = {
  padding: '5px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  fontSize: 12, color: 'var(--txt)', cursor: 'pointer',
};

export function CardTitle({ children }: { children: ReactNode }) {
  return <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>{children}</div>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div style={{ padding: 16, fontSize: 12.5, color: 'var(--txt-dim)', textAlign: 'center' }}>{children}</div>;
}

export function ErrorBox({ message }: { message: string }) {
  if (!message) return null;
  return (
    <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 10px', marginBottom: 12, color: 'var(--risk)', fontSize: 12 }}>
      {message}
    </div>
  );
}

export function Pill({ children, tone }: { children: ReactNode; tone: 'warn' | 'ok' | 'muted' }) {
  const colors = {
    warn: ['rgba(228,150,40,.15)', 'var(--warn, #e49628)'],
    ok: ['rgba(47,182,124,.15)', 'var(--ok)'],
    muted: ['var(--raised)', 'var(--txt-mut)'],
  }[tone];
  return <span style={{ fontSize: 10.5, fontWeight: 700, padding: '2px 8px', borderRadius: 20, background: colors[0], color: colors[1] }}>{children}</span>;
}

export const money = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 0 });

/** Today as yyyy-mm-dd in the user's local time — toISOString() is UTC, which is "yesterday" for IST before 05:30. */
export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
