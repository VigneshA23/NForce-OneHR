import { AssistantRateLimitedError, formatRetryEstimate } from '../../api/aiAssistant';
import type {
  AssistantMessage,
  AssistantResponse,
  Confidence,
  NavigationAction,
  RelatedItem,
  ResponseType,
} from '../../api/aiAssistant';

/**
 * The assistant panel's state, as a pure reducer.
 *
 * Kept out of the component on purpose. `vitest.config.ts` runs in the node environment and only
 * globs `src/**\/*.test.ts`, so a component cannot be rendered in a test here at all — extracting
 * the logic is the difference between this being tested and not, and it follows what
 * `AttendancePage.test.ts` already does with its own page logic.
 *
 * Every action carries the ids and values it needs, so the reducer never reads a clock or a random
 * source. That is what makes a transition assertable rather than merely runnable.
 */

export interface AssistantMessageView {
  id: string;
  sender: 'USER' | 'ASSISTANT';
  content: string;
  responseType?: ResponseType | null;
  steps?: string[];
  navigation?: NavigationAction | null;
  related?: RelatedItem[];
  confidence?: Confidence;
  /** An assistant turn still in flight — rendered as the typing indicator. */
  pending?: boolean;
  /** The request itself failed (network, 500). Distinct from an UNKNOWN answer, which succeeded. */
  failed?: boolean;
}

export interface AssistantState {
  messages: AssistantMessageView[];
  conversationId: string | null;
  sending: boolean;
  /** Transport-level problem worth surfacing above the composer. */
  error: string | null;
}

/**
 * Mirrors `app.ai.limits.max-message-chars`.
 *
 * Client-side so the user sees the limit while typing instead of after sending; the server
 * enforces it regardless, and rejects an over-long message before it costs an embedding call.
 */
export const MAX_MESSAGE_CHARS = 1000;

export const initialAssistantState: AssistantState = {
  messages: [],
  conversationId: null,
  sending: false,
  error: null,
};

export type AssistantAction =
  /** The user sent `text`; `pendingId` is the placeholder the answer will replace. */
  | { type: 'ASK'; id: string; pendingId: string; text: string }
  | { type: 'ANSWER'; pendingId: string; response: AssistantResponse }
  | { type: 'FAIL'; pendingId: string; message: string }
  | { type: 'RESTORE'; conversationId: string; messages: AssistantMessage[] }
  | { type: 'DISMISS_ERROR' }
  | { type: 'CLEAR' };

export function assistantReducer(state: AssistantState, action: AssistantAction): AssistantState {
  switch (action.type) {
    case 'ASK':
      return {
        ...state,
        sending: true,
        // Cleared here rather than on the next success: leaving a stale failure banner above a
        // question that is currently in flight reads as though the new question failed too.
        error: null,
        messages: [
          ...state.messages,
          { id: action.id, sender: 'USER', content: action.text },
          { id: action.pendingId, sender: 'ASSISTANT', content: '', pending: true },
        ],
      };

    case 'ANSWER':
      return {
        ...state,
        sending: false,
        // The server decides the conversation id, including on the first turn, so it is adopted
        // from the response rather than generated here.
        conversationId: action.response.conversationId ?? state.conversationId,
        messages: state.messages.map((message) =>
          message.id === action.pendingId
            ? {
                id: message.id,
                sender: 'ASSISTANT',
                content: action.response.answer,
                responseType: action.response.type,
                steps: action.response.steps ?? [],
                navigation: action.response.navigation ?? null,
                related: action.response.related ?? [],
                confidence: action.response.confidence,
              }
            : message,
        ),
      };

    case 'FAIL':
      return {
        ...state,
        sending: false,
        error: action.message,
        // The placeholder becomes the failure rather than disappearing, so the user's own question
        // is not left sitting there looking answered.
        messages: state.messages.map((message) =>
          message.id === action.pendingId
            ? { ...message, pending: false, failed: true, content: action.message }
            : message,
        ),
      };

    case 'RESTORE':
      return {
        ...state,
        conversationId: action.conversationId,
        sending: false,
        error: null,
        // Replayed turns carry no steps or navigation - only the prose was stored - so an offer to
        // open a page is deliberately not resurrected. Re-offering a destination without the
        // answer that justified it would be worse than not offering it.
        messages: action.messages.map((message, index) => ({
          id: `${action.conversationId}:${index}`,
          sender: message.sender,
          content: message.content,
          responseType: message.responseType,
        })),
      };

    case 'DISMISS_ERROR':
      return { ...state, error: null };

    case 'CLEAR':
      // The conversation id survives, matching the server's clear endpoint, which empties the
      // conversation but keeps it. An open tab therefore stays coherent instead of silently
      // starting a second conversation on the next message.
      return { ...initialAssistantState, conversationId: state.conversationId };

    default:
      return state;
  }
}

/** Whether a message can be submitted at all. Mirrors the server's own guards. */
export function canSend(draft: string, sending: boolean, maxChars = MAX_MESSAGE_CHARS): boolean {
  const trimmed = draft.trim();
  return !sending && trimmed.length > 0 && trimmed.length <= maxChars;
}

/**
 * The message shown for a failed send, given whatever `sendMessage` rejected with.
 *
 * A pure function of the caught value, so this - and specifically the rate-limited wording - is
 * testable here rather than only inside `AssistantPanel.tsx`, which `vitest.config.ts` cannot
 * render at all (node environment, `*.test.ts` only). No automatic retry is scheduled anywhere
 * near this: it only ever produces text, never a timer.
 */
export function describeSendFailure(e: unknown): string {
  if (e instanceof AssistantRateLimitedError) {
    return `${e.message} ${formatRetryEstimate(e.retryAt)}`;
  }
  return e instanceof Error ? e.message : 'The assistant could not be reached.';
}
