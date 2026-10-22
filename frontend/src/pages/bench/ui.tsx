import type { CSSProperties, ReactNode } from 'react';

/** Shared look for the bench screens — the same panel/input tokens ProjectAllocationsPage uses. */
export const card: CSSProperties = { background: 'linear-gradient(180deg, color-mix(in srgb, var(--brand) 4%, var(--panel)) 0%, var(--panel) 90px)', border: '1px solid var(--line)', borderRadius: 14, padding: '16px 18px', boxShadow: '0 6px 20px rgba(16, 24, 40, .07)' };
export const inputStyle: CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 10,
  padding: '10px 12px', fontSize: 13.5, color: 'var(--txt)', outline: 'none', fontFamily: 'inherit',
  width: '100%', boxSizing: 'border-box',
};
export const labelStyle: CSSProperties = { fontSize: 11, fontWeight: 800, color: 'var(--txt-mut)', display: 'block', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.07em' };
export const th: CSSProperties = { textAlign: 'left', padding: '6px 8px', fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' };
export const td: CSSProperties = { padding: '7px 8px', fontSize: 12.5, color: 'var(--txt)', borderBottom: '1px solid var(--line)' };

export const primaryBtn = (disabled = false): CSSProperties => ({
  padding: '9px 18px', background: 'linear-gradient(135deg, var(--brand), color-mix(in srgb, var(--brand) 70%, #000))', border: 'none', borderRadius: 10, fontSize: 13, fontWeight: 700,
  boxShadow: disabled ? 'none' : '0 6px 14px color-mix(in srgb, var(--brand) 28%, transparent)',
  color: '#fff', cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.7 : 1,
});
export const ghostBtn: CSSProperties = {
  padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 10,
  fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', cursor: 'pointer',
};

export function CardTitle({ children }: { children: ReactNode }) {
  return <div style={{ fontSize: 15, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)', marginBottom: 12 }}>{children}</div>;
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
  return <span style={{ fontSize: 11, fontWeight: 700, padding: '3px 10px', borderRadius: 20, background: colors[0], color: colors[1], border: `1px solid color-mix(in srgb, ${colors[1]} 26%, transparent)` }}>{children}</span>;
}

export const money = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 0 });

/** Today as yyyy-mm-dd in the user's local time — toISOString() is UTC, which is "yesterday" for IST before 05:30. */
export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
