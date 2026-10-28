import { EmployeeAvatar } from './EmployeeAvatar';
import { useEmployeeCodes } from '../lib/useEmployeeCodes';

/**
 * One way to show an employee in a table row, used by People Directory, User Management and Employee Master:
 * the avatar (their profile photo when they have one, otherwise a coloured initials disc, via EmployeeAvatar)
 * with the name on the first line and the Employee ID directly under it. Display only; it renders whatever
 * it is given and never decides who may see what.
 */

// Soft initials-disc tints, picked deterministically per employee so the same person always gets the same
// colour. Translucent backgrounds read correctly on both dark and light themes.
const AVATAR_TINTS: [string, string][] = [
  ['rgba(217,72,95,.14)', '#D9485F'],
  ['rgba(59,111,216,.14)', '#3B6FD8'],
  ['rgba(124,92,214,.15)', '#7C5CD6'],
  ['rgba(31,157,107,.14)', '#1F9D6B'],
  ['rgba(217,119,43,.15)', '#D9772B'],
  ['rgba(27,146,166,.14)', '#1B92A6'],
];

function avatarTint(key: string): [string, string] {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0;
  return AVATAR_TINTS[Math.abs(h) % AVATAR_TINTS.length];
}

export function PersonAvatar({ userId, name, size = 38, photoDataUrl }: { userId: string; name: string; size?: number; photoDataUrl?: string | null }) {
  const [background, color] = avatarTint(userId || name);
  return (
    <EmployeeAvatar
      userId={userId}
      name={name}
      photoDataUrl={photoDataUrl}
      size={size}
      fontSize={size * 0.36}
      background={background}
      color={color}
      style={{ fontFamily: 'Inter, sans-serif', letterSpacing: '.02em' }}
    />
  );
}

export function PersonCell({ userId, name, code, extra, photoDataUrl }: {
  userId: string;
  name: string;
  /** Employee ID, shown under the name. */
  code?: string | null;
  /** Optional third line (e.g. e-mail) for tables that used to show it under the name. */
  extra?: string | null;
  /** Preview/testing only: a photo to show instead of fetching the employee's profile photo. */
  photoDataUrl?: string | null;
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 200 }}>
      <PersonAvatar userId={userId} name={name} photoDataUrl={photoDataUrl} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 600, fontSize: 13.5, color: 'var(--txt)' }}>{name}</div>
        {code ? <div style={{ marginTop: 2, fontSize: 11.5, color: 'var(--txt-dim)', letterSpacing: '.01em' }}>{code}</div> : null}
        {extra ? <div style={{ marginTop: 1, fontSize: 11.5, color: 'var(--txt-dim)' }}>{extra}</div> : null}
      </div>
    </div>
  );
}

/** PersonCell for records that carry only a user id (requests, tickets, claims…): the Employee ID is looked up
 *  the same way EmpCode does it, so those tables show the same avatar / name / ID cell as the directories. */
export function PersonCellById({ userId, name, code, extra }: { userId: string; name: string; code?: string | null; extra?: string | null }) {
  const codeFor = useEmployeeCodes();
  return <PersonCell userId={userId} name={name} code={code ?? codeFor(userId)} extra={extra} />;
}
