import type { CSSProperties, ReactNode } from 'react';
import { EmployeeAvatar } from '../../components/EmployeeAvatar';
import { EmpCode } from '../../components/EmpCode';

/** Shared look for the bench screens. Theme tokens only, so light/dark and the accent setting keep working. */
export const card: CSSProperties = { background: 'linear-gradient(180deg, color-mix(in srgb, var(--brand) 4%, var(--panel)) 0%, var(--panel) 90px)', border: '1px solid var(--line)', borderRadius: 18, padding: '18px 20px', boxShadow: '0 6px 22px rgba(16, 24, 40, .06)' };
export const inputStyle: CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 12,
  padding: '10px 13px', fontSize: 13.5, color: 'var(--txt)', outline: 'none', fontFamily: 'inherit',
  width: '100%', boxSizing: 'border-box',
};
export const labelStyle: CSSProperties = { fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', display: 'block', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.08em' };
export const th: CSSProperties = { textAlign: 'left', padding: '11px 16px', fontSize: 10.5, fontWeight: 700, letterSpacing: '.07em', color: 'var(--txt-dim)', textTransform: 'uppercase', background: 'var(--raised)', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
export const td: CSSProperties = { padding: '12px 16px', fontSize: 13, color: 'var(--txt)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

export const primaryBtn = (disabled = false): CSSProperties => ({
  padding: '10px 20px', background: 'linear-gradient(135deg, var(--brand), color-mix(in srgb, var(--brand) 70%, #000))', border: 'none', borderRadius: 12, fontSize: 13, fontWeight: 700,
  boxShadow: disabled ? 'none' : '0 8px 18px color-mix(in srgb, var(--brand) 26%, transparent)',
  color: '#fff', cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.6 : 1, fontFamily: 'inherit',
});
export const ghostBtn: CSSProperties = {
  padding: '8px 15px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 11,
  fontSize: 12.5, fontWeight: 700, color: 'var(--txt)', cursor: 'pointer', fontFamily: 'inherit',
};

/** Section heading: an optional icon tile, then the title. */
export function CardTitle({ children, icon }: { children: ReactNode; icon?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 11, marginBottom: 14 }}>
      {icon && (
        <span style={{ flexShrink: 0, width: 34, height: 34, borderRadius: 11, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--brand)', background: 'color-mix(in srgb, var(--brand) 13%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 26%, transparent)' }}>{icon}</span>
      )}
      <div style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-.01em', color: 'var(--txt)' }}>{children}</div>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div style={{ padding: '30px 16px', fontSize: 13, color: 'var(--txt-dim)', textAlign: 'center' }}>{children}</div>;
}

export function ErrorBox({ message }: { message: string }) {
  if (!message) return null;
  return (
    <div role="alert" style={{ background: 'color-mix(in srgb, var(--risk) 10%, var(--panel))', border: '1px solid color-mix(in srgb, var(--risk) 28%, transparent)', borderRadius: 12, padding: '10px 13px', marginBottom: 12, color: 'var(--risk)', fontSize: 12.5, fontWeight: 600 }}>
      {message}
    </div>
  );
}

export function Pill({ children, tone }: { children: ReactNode; tone: 'warn' | 'ok' | 'muted' | 'info' }) {
  const color = { warn: 'var(--warn, #e49628)', ok: 'var(--ok)', muted: 'var(--txt-mut)', info: 'var(--info)' }[tone];
  return (
    <span style={{ display: 'inline-block', whiteSpace: 'nowrap', fontSize: 11.5, fontWeight: 700, padding: '3px 11px', borderRadius: 999, color, background: `color-mix(in srgb, ${color} 13%, var(--panel))`, border: `1px solid color-mix(in srgb, ${color} 30%, transparent)` }}>{children}</span>
  );
}

/** Avatar, name and employee ID, the same person cell the other tables use. */
export function PersonCell({ userId, name }: { userId: string; name: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 11, minWidth: 0 }}>
      <EmployeeAvatar userId={userId} name={name} size={34} fontSize={12} background="var(--brand)" />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 700, color: 'var(--txt)' }}>{name}</div>
        <EmpCode userId={userId} />
      </div>
    </div>
  );
}

/** KPI tile: label and icon on top, the number at the bottom, a coloured edge on the left. */
export function StatTile({ label, value, hint, icon, tone }: { label: string; value: string; hint?: string; icon: ReactNode; tone: string }) {
  return (
    <div className="nf-bn-tile" style={{ ['--mk' as string]: tone }}>
      <div className="nf-bn-tile-top">
        <span className="nf-bn-tile-label">{label}</span>
        <span className="nf-bn-tile-ico">{icon}</span>
      </div>
      <div className="nf-bn-tile-val">{value}</div>
      {hint && <div className="nf-bn-tile-hint">{hint}</div>}
    </div>
  );
}

export const money = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 0 });

/** Today as yyyy-mm-dd in the user's local time — toISOString() is UTC, which is "yesterday" for IST before 05:30. */
export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
