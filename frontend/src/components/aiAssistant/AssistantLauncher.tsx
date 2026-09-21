import { useEffect, useState } from 'react';
import { MessageCircleQuestion, X } from 'lucide-react';
import { fetchHealth } from '../../api/aiAssistant';
import { useAuthStore } from '../../store/authStore';
import { AssistantPanel } from './AssistantPanel';
import { inviteBubbleStyle, launcherGlowStyle, launcherStyle } from './assistantStyles';

const INVITE_SEEN_KEY = 'onehr.assistant.inviteSeen';

/**
 * The floating button, and the only thing `Shell.tsx` mounts.
 *
 * Renders nothing at all until the server says the assistant is both enabled and has a populated
 * index. That check is why this component exists separately from the panel: an assistant that is
 * switched off, or switched on with nothing indexed, would answer every question with "I don't
 * know", and a visible button that never helps is worse than no button. If the health call fails
 * the launcher stays hidden — the failure is silent by design, since a user who never knew the
 * feature existed has not lost anything.
 *
 * `AssistantPanel` is lazily mounted on the *first* open (`hasOpenedOnce`) — most sessions never
 * touch the assistant at all, and there's no reason to pay for its state/effects before that — but
 * once mounted it stays mounted, and `open` only toggles its `hidden` prop rather than unmounting
 * it. That one change is what lets a conversation survive closing the panel or navigating to
 * another page in the same tab, since `AssistantLauncher` itself is a permanent fixture of
 * `Shell.tsx`, outside the routed `<Outlet/>`, and the panel's `useReducer` state now lives exactly
 * as long as it does. `openToken` bumps on every open so the panel can replay its entrance
 * animation each time, not just once.
 */
export function AssistantLauncher({ currentPageId }: { currentPageId?: string }) {
  const token = useAuthStore((s) => s.token);
  const [available, setAvailable] = useState(false);
  const [open, setOpen] = useState(false);
  const [hasOpenedOnce, setHasOpenedOnce] = useState(false);
  const [openToken, setOpenToken] = useState(0);
  const [showInvite, setShowInvite] = useState(false);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    fetchHealth(token)
      .then((health) => { if (!cancelled) setAvailable(health.enabled && health.indexReady); })
      .catch(() => { /* stay hidden */ });
    return () => { cancelled = true; };
  }, [token]);

  // Invites the assistant once per browser session (sessionStorage, not localStorage — it's
  // welcome to nudge again next time someone signs in), and only after a short delay so it never
  // competes with everything else settling in on page load. Purely a discoverability nudge, so
  // any storage failure (private window, blocked storage) just means it invites every time
  // instead of once — never something worth guarding harder than a try/catch.
  useEffect(() => {
    if (!available) return;
    let alreadySeen = false;
    try { alreadySeen = sessionStorage.getItem(INVITE_SEEN_KEY) === '1'; } catch { /* assume not seen */ }
    if (alreadySeen) return;
    const showTimer = setTimeout(() => setShowInvite(true), 1400);
    const hideTimer = setTimeout(() => setShowInvite(false), 8000);
    return () => { clearTimeout(showTimer); clearTimeout(hideTimer); };
  }, [available]);

  if (!available) return null;

  function toggle() {
    setShowInvite(false);
    try { sessionStorage.setItem(INVITE_SEEN_KEY, '1'); } catch { /* best effort */ }
    setOpen((wasOpen) => {
      const willOpen = !wasOpen;
      if (willOpen) {
        setHasOpenedOnce(true);
        setOpenToken((t) => t + 1);
      }
      return willOpen;
    });
  }

  return (
    <>
      {hasOpenedOnce && (
        <AssistantPanel hidden={!open} openToken={openToken} onClose={() => setOpen(false)} currentPageId={currentPageId} />
      )}
      {showInvite && !open && (
        <div style={inviteBubbleStyle} role="status">
          New here: ask me anything about OneHR — leave, attendance, expenses, and more.
        </div>
      )}
      <div aria-hidden="true" className="nf-ai-launcher-glow" style={launcherGlowStyle} />
      <button
        type="button"
        onClick={toggle}
        onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.transform = 'scale(1.06)'; }}
        onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.transform = 'none'; }}
        aria-label={open ? 'Close the OneHR assistant' : 'Open the OneHR assistant'}
        aria-expanded={open}
        className="nf-assistant-launcher"
        style={launcherStyle}
      >
        {open ? <X size={20} /> : <MessageCircleQuestion size={21} />}
      </button>
    </>
  );
}
