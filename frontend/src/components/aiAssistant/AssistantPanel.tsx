import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { RotateCcw, Send, X } from 'lucide-react';
import * as assistantApi from '../../api/aiAssistant';
import { useAuthStore } from '../../store/authStore';
import { resolvePageTarget } from '../../lib/ai/pageTargets';
import { AssistantEmptyState, MessageList } from './MessageList';
import {
  assistantReducer,
  canSend,
  initialAssistantState,
  MAX_MESSAGE_CHARS,
} from './assistantState';
import {
  composerStyle,
  errorBannerStyle,
  headerStyle,
  iconButtonStyle,
  panelStyle,
  sendButtonStyle,
  textareaStyle,
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
 */

interface AssistantPanelProps {
  onClose: () => void;
  /** The nav key of the page behind the panel. A ranking hint, re-validated server-side. */
  currentPageId?: string;
}

export function AssistantPanel({ onClose, currentPageId }: AssistantPanelProps) {
  const token = useAuthStore((s) => s.token);
  const role = useAuthStore((s) => s.user?.role);
  const navigate = useNavigate();

  const [state, dispatch] = useReducer(assistantReducer, initialAssistantState);
  const [draft, setDraft] = useState('');

  const transcriptRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  // Captured on mount so focus goes back to whatever opened the panel, which is the launcher in
  // practice but should not be assumed.
  const opener = useRef<HTMLElement | null>(null);

  useEffect(() => {
    opener.current = document.activeElement as HTMLElement | null;
    inputRef.current?.focus();
    return () => opener.current?.focus?.();
  }, []);

  useEffect(() => {
    const node = transcriptRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [state.messages]);

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

    try {
      const response = await assistantApi.sendMessage(token, {
        message: trimmed,
        conversationId: state.conversationId,
        currentPageId,
      });
      dispatch({ type: 'ANSWER', pendingId, response });
    } catch (e) {
      // Deliberately not a toast. The failure belongs next to the question that caused it, and a
      // toast over a panel the user is already looking at is noise.
      dispatch({
        type: 'FAIL',
        pendingId,
        message: e instanceof Error ? e.message : 'The assistant could not be reached.',
      });
    }
  }, [token, state.sending, state.conversationId, currentPageId]);

  const clear = useCallback(async () => {
    dispatch({ type: 'CLEAR' });
    if (token && state.conversationId) {
      // Best effort: the panel is already empty either way, and failing to clear the server copy
      // is not something the user can act on.
      try { await assistantApi.clearConversation(token, state.conversationId); } catch { /* ignore */ }
    }
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
      style={panelStyle}
    >
      <div style={headerStyle}>
        <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>
          OneHR Assistant
        </span>
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
        {state.messages.length === 0
          ? <AssistantEmptyState onPick={(question) => void send(question)} />
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
          onChange={(event) => setDraft(event.target.value)}
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
        >
          <Send size={15} />
        </button>
      </form>

      {tooLong && (
        <div style={{ ...errorBannerStyle, borderTop: 'none' }}>
          {draft.trim().length} of {MAX_MESSAGE_CHARS} characters — shorten your question.
        </div>
      )}
    </div>
  );
}
