import { useEffect, useState } from 'react';
import { API_ORIGIN } from '../api/config';
import { useAuthStore } from '../store/authStore';

const BASE = `${API_ORIGIN}/api`;

// Module-level cache shared by every avatar rendered anywhere on the page: once an employee's
// photo (or the fact that they have none) is resolved, every other avatar for that same userId —
// a directory row, an org chart node, a team roster card, a search result — reuses it instead of
// re-fetching. Blob URLs are intentionally never revoked: they're cheap, capped at one per
// distinct employee photo shown this session, and revoking on unmount would defeat the point of
// sharing them across many simultaneous renderers of the same person.
const photoCache = new Map<string, string | null>();
const inFlight = new Map<string, Promise<string | null>>();

function fetchPhoto(userId: string, token: string): Promise<string | null> {
  if (photoCache.has(userId)) return Promise.resolve(photoCache.get(userId) ?? null);
  const existing = inFlight.get(userId);
  if (existing) return existing;

  const promise = fetch(`${BASE}/employees/${userId}/photo`, {
    headers: { Authorization: `Bearer ${token}` },
  })
    .then((res) => (res.ok ? res.blob() : null))
    .then((blob) => (blob ? URL.createObjectURL(blob) : null))
    .catch(() => null)
    .then((url) => {
      photoCache.set(userId, url);
      inFlight.delete(userId);
      return url;
    });

  inFlight.set(userId, promise);
  return promise;
}

/** Drop a cached photo (e.g. right after the owner uploads/removes one) so the rest of this session's avatars for that person pick up the change instead of showing a stale cached result. */
export function invalidateEmployeePhoto(userId: string) {
  photoCache.delete(userId);
}

export function getInitials(nameOrEmail?: string | null): string {
  if (!nameOrEmail) return 'U';
  if (nameOrEmail.includes('@')) return nameOrEmail.slice(0, 2).toUpperCase();
  const parts = nameOrEmail.trim().split(/\s+/);
  if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  return nameOrEmail.slice(0, 2).toUpperCase();
}

/**
 * Circular avatar for any employee, used app-wide — directory, org chart, team lists, search
 * results, approvals, onboarding, the topbar/sidebar — not just the signed-in user's own
 * profile. Shows the employee's actual uploaded photo when one exists (fetched once per userId
 * and cached for the rest of the session across every place that person's avatar appears);
 * falls back to an initials disc immediately while resolving and permanently when there's no
 * photo on file.
 *
 * Pass `photoDataUrl` directly (skipping the network fetch entirely) when the caller already
 * has it in hand — Shell's own-profile avatars get it for free from the auth store.
 */
export function EmployeeAvatar({
  userId, name, photoDataUrl, size = 32, fontSize, background, color, border, style,
}: {
  userId?: string | null;
  name?: string | null;
  photoDataUrl?: string | null;
  size?: number;
  fontSize?: number;
  background?: string;
  color?: string;
  border?: string;
  style?: React.CSSProperties;
}) {
  const token = useAuthStore((s) => s.token) ?? '';
  const directPhoto = photoDataUrl !== undefined;
  const [resolvedUrl, setResolvedUrl] = useState<string | null>(
    !directPhoto && userId ? photoCache.get(userId) ?? null : null,
  );

  useEffect(() => {
    if (directPhoto || !userId || !token) return;
    let cancelled = false;
    fetchPhoto(userId, token).then((url) => { if (!cancelled) setResolvedUrl(url); });
    return () => { cancelled = true; };
  }, [userId, token, directPhoto]);

  const src = directPhoto ? (photoDataUrl ?? null) : resolvedUrl;
  const initials = getInitials(name);

  return (
    <div
      style={{
        width: size, height: size, borderRadius: '50%',
        background: src ? `url(${src}) center/cover no-repeat` : (background ?? '#B11116'),
        display: 'grid', placeItems: 'center', color: color ?? '#fff',
        fontSize: fontSize ?? Math.max(10, Math.round(size * 0.4)), fontWeight: 700,
        flexShrink: 0, boxSizing: 'border-box',
        ...(border ? { border } : {}),
        ...style,
      }}
    >
      {!src && initials}
    </div>
  );
}
