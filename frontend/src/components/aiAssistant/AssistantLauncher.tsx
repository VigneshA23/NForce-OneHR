import { useEffect, useRef, useState } from 'react';
import { MessageCircleQuestion } from 'lucide-react';
import { fetchHealth } from '../../api/aiAssistant';
import { useAuthStore } from '../../store/authStore';
import { AssistantPanel } from './AssistantPanel';
import { launcherStyle } from './assistantStyles';

/** Matches launcherStyle's width/height — needed to keep the button on-screen while dragging. */
const LAUNCHER_SIZE = 50;
/** A drag has to move at least this far before a pointerup is treated as a drag, not a tap. */
const DRAG_THRESHOLD_PX = 6;
const POSITION_STORAGE_KEY = 'onehr-assistant-launcher-position';

interface Position {
  x: number;
  y: number;
}

function clampToViewport(position: Position): Position {
  const margin = 4;
  const maxX = Math.max(margin, window.innerWidth - LAUNCHER_SIZE - margin);
  const maxY = Math.max(margin, window.innerHeight - LAUNCHER_SIZE - margin);
  return {
    x: Math.min(Math.max(position.x, margin), maxX),
    y: Math.min(Math.max(position.y, margin), maxY),
  };
}

/** Per-viewer convenience only — never read back by anything else, so a storage failure is silent. */
function loadStoredPosition(): Position | null {
  try {
    const raw = localStorage.getItem(POSITION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<Position>;
    if (typeof parsed.x !== 'number' || typeof parsed.y !== 'number') return null;
    return clampToViewport(parsed as Position);
  } catch {
    return null;
  }
}

function storePosition(position: Position) {
  try {
    localStorage.setItem(POSITION_STORAGE_KEY, JSON.stringify(position));
  } catch {
    /* per-viewer convenience only */
  }
}

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
 * Draggable to anywhere on screen via pointer events (unifies mouse and touch, no new dependency).
 * A drag is distinguished from a tap by total displacement, not by a separate mode — `movedRef`
 * only flips once the pointer has actually travelled `DRAG_THRESHOLD_PX`, so a normal click or a
 * touch's inevitable few pixels of jitter still opens the panel instead of being swallowed. The
 * dropped position is clamped to the viewport (it can never be dragged off-screen, including after
 * a resize/rotation) and remembered per browser via `localStorage` — reload keeps it, another
 * device does not, exactly the "per-viewer convenience" this app already uses `localStorage` for
 * elsewhere (theme preference, recent searches).
 */
export function AssistantLauncher({ currentPageId }: { currentPageId?: string }) {
  const token = useAuthStore((s) => s.token);
  const [available, setAvailable] = useState(false);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<Position | null>(() => loadStoredPosition());

  const draggingRef = useRef(false);
  const movedRef = useRef(false);
  const pointerOffsetRef = useRef<Position>({ x: 0, y: 0 });
  const dragStartRef = useRef<Position>({ x: 0, y: 0 });
  /** Mirrors `position` synchronously, so pointerup can persist the drop point without a
   * setState-as-getter round trip (state updates from pointermove are not guaranteed to have
   * flushed by the time the very next pointerup fires). */
  const latestPositionRef = useRef<Position | null>(position);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    fetchHealth(token)
      .then((health) => { if (!cancelled) setAvailable(health.enabled && health.indexReady); })
      .catch(() => { /* stay hidden */ });
    return () => { cancelled = true; };
  }, [token]);

  // A dropped position can end up off-screen after the window resizes or the phone rotates -
  // re-clamp rather than let the button become unreachable.
  useEffect(() => {
    if (!position) return;
    function onResize() {
      setPosition((current) => (current ? clampToViewport(current) : current));
    }
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [position]);

  function onPointerDown(event: React.PointerEvent<HTMLButtonElement>) {
    const rect = event.currentTarget.getBoundingClientRect();
    pointerOffsetRef.current = { x: event.clientX - rect.left, y: event.clientY - rect.top };
    dragStartRef.current = { x: event.clientX, y: event.clientY };
    draggingRef.current = true;
    movedRef.current = false;
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function onPointerMove(event: React.PointerEvent<HTMLButtonElement>) {
    if (!draggingRef.current) return;
    if (!movedRef.current) {
      const dx = event.clientX - dragStartRef.current.x;
      const dy = event.clientY - dragStartRef.current.y;
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD_PX) return;
      movedRef.current = true;
    }
    const next = clampToViewport({
      x: event.clientX - pointerOffsetRef.current.x,
      y: event.clientY - pointerOffsetRef.current.y,
    });
    latestPositionRef.current = next;
    setPosition(next);
  }

  function onPointerUp() {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    if (movedRef.current && latestPositionRef.current) {
      storePosition(latestPositionRef.current);
    }
  }

  function onClick() {
    // A drag just ending fires a click too - swallow that one so dropping the button never also
    // toggles the panel open/closed.
    if (movedRef.current) {
      movedRef.current = false;
      return;
    }
    setOpen((wasOpen) => !wasOpen);
  }

  if (!available) return null;

  const style = position
    ? { ...launcherStyle, left: position.x, top: position.y, right: 'auto', bottom: 'auto' }
    : launcherStyle;

  return (
    <>
      {open && <AssistantPanel onClose={() => setOpen(false)} currentPageId={currentPageId} />}
      {/* Hidden entirely while the panel is open, not just swapped to an X - the panel has its own
          close button, and a second, redundant close control floating over an open panel that may
          itself have been dragged elsewhere on screen is more confusing than helpful. */}
      {!open && (
        <button
          type="button"
          onClick={onClick}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
          aria-label="Open NORA (draggable — press and drag to move it)"
          aria-expanded={false}
          className="nf-assistant-launcher"
          style={{ ...style, touchAction: 'none' }}
        >
          <MessageCircleQuestion size={21} />
        </button>
      )}
    </>
  );
}
