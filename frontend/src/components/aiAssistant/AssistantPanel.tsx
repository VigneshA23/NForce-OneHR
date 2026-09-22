import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, RotateCcw, Send, Sparkles, X } from 'lucide-react';
import * as assistantApi from '../../api/aiAssistant';
import { myCompliance } from '../../api/documents';
import { useAuthStore } from '../../store/authStore';
import { useAccessibilityPrefs } from '../../lib/accessibilityPrefs';
import { resolvePageTarget } from '../../lib/ai/pageTargets';
import { AssistantEmptyState, MessageList } from './MessageList';
import {
  assistantReducer,
  canSend,
  describeSendFailure,
  initialAssistantState,
  MAX_MESSAGE_CHARS,
} from './assistantState';
import {
  composerStyle,
  errorBannerStyle,
  headerBadgeGlowStyle,
  headerBadgeRingStyle,
  headerBadgeStyle,
  headerBadgeWrapStyle,
  headerStyle,
  iconButtonStyle,
  panelBodyStyle,
  panelStyle,
  sendButtonStyle,
  textareaStyle,
  transcriptGlowStyle,
  transcriptStyle,
} from './assistantStyles';

/**
 * The assistant conversation panel.
 *
 * The panel is deliberately **non-modal**: no scrim, and the page behind it stays readable and
 * usable, because half the point of asking is to look at what you are asking about. That decision
 * settles the accessibility shape — a focus trap belongs to a dialog that owns the screen, and
 * putting one here would strand a keyboard user inside a panel the page is still live behind.
 *
 * So: `role="dialog"` with `aria-modal="false"`, focus moved to the input on open and restored to
 * whatever opened it on close, Escape to close, and the transcript as a polite live region so an
 * answer is announced rather than arriving silently. Tab leaves the panel normally, as it should.
 *
 * `hidden`/`openToken`: AssistantLauncher keeps this component mounted after its first open
 * instead of tearing it down on every close (`{open && <AssistantPanel/>}`), toggling `hidden` to
 * show/hide it instead. That is what makes the conversation survive closing the panel or
 * navigating to another page within the same tab — the `useReducer` below simply never resets.
 * `openToken` (bumped by the launcher on every open) is applied as a `key` on the inner body wrap
 * purely so `nf-assistant-panel-in` replays each time the panel reopens rather than only once, on
 * its first-ever mount — remounting that wrapper is cheap and stateless, nothing here lives in it.
 */

interface AssistantPanelProps {
  hidden: boolean;
  openToken: number;
  onClose: () => void;
  /** The nav key of the page behind the panel. A ranking hint, re-validated server-side. */
  currentPageId?: string;
  /** Unread notification count, already polled by Shell for the topbar bell — threaded through
   *  rather than polled again here, so the welcome screen's "For You" line and the topbar badge
   *  never disagree. */
  unreadCount?: number;
}

export function AssistantPanel({ hidden, openToken, onClose, currentPageId, unreadCount = 0 }: AssistantPanelProps) {
  const token = useAuthStore((s) => s.token);
  const role = useAuthStore((s) => s.user?.role);
  const fullName = useAuthStore((s) => s.user?.fullName);
  const navigate = useNavigate();
  const { reduceAnimations } = useAccessibilityPrefs();

  const [state, dispatch] = useReducer(assistantReducer, initialAssistantState);
  const [draft, setDraft] = useState('');
  // Fetched once per mount (the panel itself is lazily mounted on first open and then stays
  // mounted — see the doc comment above) purely to surface on the welcome screen; failures are
  // silent since "For You" is a nice-to-have summary, not something worth an error banner over.
  const [pendingPolicies, setPendingPolicies] = useState(0);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    myCompliance(token).then((s) => { if (!cancelled) setPendingPolicies(s.pendingPolicies); }).catch(() => {});
    return () => { cancelled = true; };
  }, [token]);

  const transcriptRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  // Captured whenever the panel is shown so focus goes back to whatever opened it, which is the
  // launcher in practice but should not be assumed.
  const opener = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (hidden) {
      opener.current?.focus?.();
      return;
    }
    opener.current = document.activeElement as HTMLElement | null;
    inputRef.current?.focus();
  }, [hidden]);

  useEffect(() => {
    const node = transcriptRef.current;
    if (!node) return;
    node.scrollTo({ top: node.scrollHeight, behavior: reduceAnimations ? 'auto' : 'smooth' });
  }, [state.messages, reduceAnimations]);

  const onKeyDown = useCallback((event: React.KeyboardEvent) => {
    if (event.key !== 'Escape') return;
    // Stopped here so Escape closes the assistant and nothing else - without this it would also
    // reach a dialog or dropdown the user happens to have open behind the panel.
    event.stopPropagation();
    onClose();
  }, [onClose]);

  const send = useCallback(async (text: string) => {
    if (!token) return;
    const trimmed = text.trim();
    if (!canSend(trimmed, state.sending)) return;

    // Ids are generated here, not in the reducer, so the reducer stays a pure function of its
    // inputs and its transitions can be asserted.
    const id = `q-${Date.now()}`;
    const pendingId = `a-${Date.now()}`;
    dispatch({ type: 'ASK', id, pendingId, text: trimmed });
    setDraft('');
    // The textarea auto-grows via inline height (see onChange below); clearing the draft alone
    // doesn't shrink it back, since that's a DOM-level style this component set imperatively.
    if (inputRef.current) inputRef.current.style.height = 'auto';

    try {
      const response = await assistantApi.sendMessage(token, {
        message: trimmed,
        conversationId: state.conversationId,
        currentPageId,
      });
      dispatch({ type: 'ANSWER', pendingId, response });
    } catch (e) {
      // Deliberately not a toast. The failure belongs next to the question that caused it, and a
      // toast over a panel the user is already looking at is noise. describeSendFailure gives the
      // rate-limited case its specific usage-limit wording with a countdown; every other failure
      // keeps its existing generic message. No automatic retry is scheduled either way.
      dispatch({ type: 'FAIL', pendingId, message: describeSendFailure(e) });
    }
  }, [token, state.sending, state.conversationId, currentPageId]);

  const clear = useCallback(async () => {
    dispatch({ type: 'CLEAR' });
    if (token && state.conversationId) {
      // Best effort: the panel is already empty either way, and failing to clear the server copy
      // is not something the user can act on.
      try { await assistantApi.clearConversation(token, state.conversationId); } catch { /* ignore */ }
    }
    if (inputRef.current) inputRef.current.style.height = 'auto';
    inputRef.current?.focus();
  }, [token, state.conversationId]);

  const rate = useCallback((messageId: string, rating: 'UP' | 'DOWN') => {
    dispatch({ type: 'RATE', id: messageId, rating });
    if (token && state.conversationId) {
      void assistantApi.sendFeedback(token, state.conversationId, rating);
    }
  }, [token, state.conversationId]);

  const goTo = useCallback((pageId: string) => {
    const target = resolvePageTarget(pageId, role);
    if (!target) return;
    onClose();
    navigate(target.route);
  }, [role, navigate, onClose]);

  const resolveLabel = useCallback(
    (pageId: string) => resolvePageTarget(pageId, role)?.label ?? null,
    [role],
  );

  const tooLong = draft.trim().length > MAX_MESSAGE_CHARS;

  return (
    <div
      role="dialog"
      aria-modal="false"
      aria-label="OneHR assistant"
      onKeyDown={onKeyDown}
      className="nf-assistant-panel"
      style={{ ...panelStyle, display: hidden ? 'none' : panelStyle.display }}
    >
      {/* Keyed by openToken so this wrapper (stateless — everything that matters lives in the
          hooks above, outside it) remounts on every open, replaying the panel's entrance
          animation each time rather than only on the very first mount ever. */}
      <div key={openToken} style={panelBodyStyle}>
      <div style={headerStyle} className="nf-ai-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <div style={headerBadgeWrapStyle} aria-hidden="true">
            <div style={headerBadgeGlowStyle} className="nf-ai-badge-glow" />
            {/* Speeds way up while a question is in flight — the ring goes from calm ambient
                motion to visibly "working," the same idea as a browser tab's loading spinner. */}
            <div style={{ ...headerBadgeRingStyle, animationDuration: state.sending ? '1.1s' : '7s' }} />
            <div style={headerBadgeStyle} className="nf-ai-badge"><Sparkles size={13} /></div>
          </div>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>
            OneHR Assistant
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          {state.messages.length > 0 && (
            <button type="button" onClick={clear} aria-label="Start over" style={iconButtonStyle}>
              <RotateCcw size={14} />
            </button>
          )}
          <button type="button" onClick={onClose} aria-label="Close assistant" style={iconButtonStyle}>
            <X size={16} />
          </button>
        </div>
      </div>

      <div
        ref={transcriptRef}
        style={transcriptStyle}
        aria-live="polite"
        aria-busy={state.sending}
      >
        <div style={transcriptGlowStyle} className="nf-ai-bg" aria-hidden="true" />
        {state.messages.length === 0
          ? (
            <AssistantEmptyState
              onPick={(question) => void send(question)}
              onNavigate={goTo}
              resolveLabel={resolveLabel}
              userName={fullName}
              unreadCount={unreadCount}
              pendingPolicies={pendingPolicies}
            />
          )
          : (
            <MessageList
              messages={state.messages}
              ratings={state.ratings}
              onNavigate={goTo}
              onRate={rate}
              resolveLabel={resolveLabel}
            />
          )}
      </div>

      {state.error && (
        <div style={errorBannerStyle} role="status">{state.error}</div>
      )}

      <form
        style={composerStyle}
        onSubmit={(event) => { event.preventDefault(); void send(draft); }}
      >
        <textarea
          ref={inputRef}
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            // Auto-grow: reset to the CSS-defined single-row height first, then measure — without
            // the reset, scrollHeight only ever grows, since a taller textarea never shrinks back
            // for itself when text is deleted.
            const el = event.target;
            el.style.height = 'auto';
            el.style.height = `${Math.min(el.scrollHeight, 120)}px`;
          }}
          onKeyDown={(event) => {
            // Enter sends, Shift+Enter breaks the line - the convention every chat surface uses,
            // and the reason this is a textarea rather than an input.
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              void send(draft);
            }
          }}
          rows={1}
          placeholder="Ask about OneHR…"
          aria-label="Your question"
          style={{
            ...textareaStyle,
            borderColor: tooLong ? 'rgba(239,68,68,.5)' : 'var(--line2)',
          }}
          onFocus={(event) => {
            if (tooLong) return;
            const el = event.currentTarget;
            el.style.borderColor = 'color-mix(in srgb, var(--brand) 45%, transparent)';
            el.style.boxShadow = '0 0 0 3px color-mix(in srgb, var(--brand) 14%, transparent)';
          }}
          onBlur={(event) => {
            const el = event.currentTarget;
            el.style.borderColor = tooLong ? 'rgba(239,68,68,.5)' : 'var(--line2)';
            el.style.boxShadow = 'none';
          }}
        />
        <button
          type="submit"
          disabled={!canSend(draft, state.sending)}
          aria-label="Send"
          style={{
            ...sendButtonStyle,
            opacity: canSend(draft, state.sending) ? 1 : 0.45,
            cursor: canSend(draft, state.sending) ? 'pointer' : 'default',
          }}
          onMouseEnter={(e) => { if (canSend(draft, state.sending)) (e.currentTarget as HTMLButtonElement).style.transform = 'scale(1.06)'; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.transform = 'none'; }}
          onMouseDown={(e) => { if (canSend(draft, state.sending)) (e.currentTarget as HTMLButtonElement).style.transform = 'scale(0.92)'; }}
          onMouseUp={(e) => { if (canSend(draft, state.sending)) (e.currentTarget as HTMLButtonElement).style.transform = 'scale(1.06)'; }}
        >
          {state.sending
            ? <Loader2 size={15} style={{ animation: 'nf-assistant-ring-spin 0.8s linear infinite' }} />
            : <Send size={15} />}
        </button>
      </form>

      {tooLong && (
        <div style={{ ...errorBannerStyle, borderTop: 'none' }}>
          {draft.trim().length} of {MAX_MESSAGE_CHARS} characters — shorten your question.
        </div>
      )}
      </div>
    </div>
  );
}
