import { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { myPolicies, type Policy } from '../api/policies';

const SEEN_KEY_PREFIX = 'onehr.requiredPolicyGate.shown.';

/**
 * A once-per-login prompt for required policies still awaiting acknowledgment — replaces the
 * old in-page yellow banner + tab badge on "My Documents & Policies" (easy to miss entirely if
 * you never visit that page) with something surfaced the moment you sign in, wherever you land.
 *
 * Gated per login (not per email) via sessionStorage, keyed by the auth token itself rather
 * than the user's email — signing out only clears the in-memory auth state (see
 * authStore#clearAuth), it never touches sessionStorage, so a key scoped to "this person" would
 * still read as "already seen" after logging back in in the same browser tab. The token is a
 * fresh value every login, so keying on it makes a new login always start unseen without
 * needing sign-out to remember to clean anything up. Dismiss with "Remind Me Later" and it stays
 * quiet for the rest of this login. Excludes HR_ADMIN/SUPER_ADMIN, matching ComplianceBanner's
 * existing exclusion for the same roles. Fails open on a fetch error — the KPI tiles and tab on
 * the Documents & Policies page remain a fallback surface either way.
 */
export function RequiredPolicyGate() {
  const token = useAuthStore(s => s.token);
  const user = useAuthStore(s => s.user);
  const role = user?.role ?? '';
  const navigate = useNavigate();
  const location = useLocation();
  const [pending, setPending] = useState<Policy[]>([]);
  const [dismissed, setDismissed] = useState(true);

  const isHR = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const onDocPage = location.pathname === '/my-documents';

  useEffect(() => {
    if (!token || isHR) { setPending([]); return; }
    let cancelled = false;
    myPolicies(token).then(list => {
      if (cancelled) return;
      setPending(list.filter(p => p.required && p.acknowledged === false));
      let alreadySeen = false;
      try { alreadySeen = sessionStorage.getItem(SEEN_KEY_PREFIX + token) === '1'; } catch { /* assume not seen */ }
      setDismissed(alreadySeen);
    }).catch(() => { /* fails open — see doc comment above */ });
    return () => { cancelled = true; };
  }, [token, isHR]);

  if (isHR || onDocPage || dismissed || pending.length === 0) return null;

  function dismissForSession() {
    setDismissed(true);
    try { sessionStorage.setItem(SEEN_KEY_PREFIX + (token ?? ''), '1'); } catch { /* best effort */ }
  }

  function reviewNow() {
    dismissForSession();
    navigate('/my-documents?tab=policies');
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }} className="nf-modal-overlay-in">
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 460, padding: 26 }} className="nf-modal-panel-in">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
          <div style={{ width: 34, height: 34, borderRadius: '50%', background: 'rgba(234,179,8,.14)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <ShieldAlert size={17} color="#eab308" />
          </div>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>
            {pending.length === 1 ? 'A policy needs your acknowledgment' : `${pending.length} policies need your acknowledgment`}
          </h3>
        </div>
        <ul style={{ margin: '0 0 20px', padding: 0, listStyle: 'none', display: 'grid', gap: 8 }}>
          {pending.slice(0, 4).map(p => (
            <li key={p.id} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--txt)', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 7, padding: '8px 12px' }}>
              <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.title}</span>
              <span style={{ fontSize: 11, color: 'var(--txt-dim)', flexShrink: 0 }}>v{p.version}</span>
            </li>
          ))}
          {pending.length > 4 && (
            <li style={{ fontSize: 12, color: 'var(--txt-dim)', padding: '0 12px' }}>+{pending.length - 4} more</li>
          )}
        </ul>
        <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
          <button onClick={dismissForSession} style={{ padding: '9px 16px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 }}>
            Remind Me Later
          </button>
          <button onClick={reviewNow} style={{ padding: '9px 18px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
            Review & Acknowledge
          </button>
        </div>
      </div>
    </div>
  );
}
