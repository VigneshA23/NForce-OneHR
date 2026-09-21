import { useEffect, useState } from 'react';
import { MessageCircleQuestion, X } from 'lucide-react';
import { fetchHealth } from '../../api/aiAssistant';
import { useAuthStore } from '../../store/authStore';
import { AssistantPanel } from './AssistantPanel';
import { launcherStyle } from './assistantStyles';

/**
 * The floating button, and the only thing `Shell.tsx` mounts.
 *
 * Renders nothing at all until the server says the assistant is both enabled and has a populated
 * index. That check is why this component exists separately from the panel: an assistant that is
 * switched off, or switched on with nothing indexed, would answer every question with "I don't
 * know", and a visible button that never helps is worse than no button. If the health call fails
 * the launcher stays hidden — the failure is silent by design, since a user who never knew the
 * feature existed has not lost anything.
 */
export function AssistantLauncher({ currentPageId }: { currentPageId?: string }) {
  const token = useAuthStore((s) => s.token);
  const [available, setAvailable] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    fetchHealth(token)
      .then((health) => { if (!cancelled) setAvailable(health.enabled && health.indexReady); })
      .catch(() => { /* stay hidden */ });
    return () => { cancelled = true; };
  }, [token]);

  if (!available) return null;

  return (
    <>
      {open && <AssistantPanel onClose={() => setOpen(false)} currentPageId={currentPageId} />}
      <button
        type="button"
        onClick={() => setOpen((wasOpen) => !wasOpen)}
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
