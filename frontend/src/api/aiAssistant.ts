import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/ai-assistant`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

// Mirrors AssistantResponseType. The assistant always returns one of these — a failure on the
// server side comes back as UNKNOWN with HTTP 200, not as an error status, so the UI has one
// rendering path rather than two.
export type ResponseType =
  | 'HOW_TO'
  | 'EXPLANATION'
  | 'NAVIGATION'
  | 'TROUBLESHOOTING'
  | 'PERMISSION'
  | 'UNKNOWN';

export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';

/**
 * A page the assistant offers to open.
 *
 * Note what is absent: a URL. The server only ever returns a `pageId` it has already checked the
 * signed-in user can reach, and the route is resolved on this side from `nav.config.ts` — see
 * `lib/ai/pageTargets.ts`. A model-supplied URL would be an open redirect waiting to happen.
 */
export interface NavigationAction {
  pageId: string;
  label: string;
}

export interface RelatedItem {
  label: string;
  knowledgeId?: string | null;
}

export interface AssistantResponse {
  type: ResponseType;
  answer: string;
  steps: string[];
  navigation: NavigationAction | null;
  related: RelatedItem[];
  confidence: Confidence;
  conversationId: string;
}

/** One stored turn, as returned when replaying a conversation. */
export interface AssistantMessage {
  sender: 'USER' | 'ASSISTANT';
  content: string;
  responseType: ResponseType | null;
  createdAt: string;
}

export interface AssistantHealth {
  enabled: boolean;
  indexReady: boolean;
  indexedChunks: number;
  lastIndexedAt: string | null;
  knowledgeSources: string[];
  llmProvider: string;
  embeddingProvider: string;
  embeddingDimensions: number;
}

export interface ChatRequest {
  message: string;
  conversationId?: string | null;
  /**
   * The nav key of the page the user is on. A hint for ranking only — the server re-validates it
   * against its own registry and drops it if this user could not reach that page, so sending a
   * stale or wrong value degrades the answer at worst and can never widen what is retrieved.
   */
  currentPageId?: string | null;
}

export async function sendMessage(token: string, request: ChatRequest): Promise<AssistantResponse> {
  const res = await fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handle<AssistantResponse>(res);
}

export async function fetchConversation(token: string, conversationId: string): Promise<AssistantMessage[]> {
  const res = await fetch(`${BASE}/conversations/${conversationId}`, { headers: authHeaders(token) });
  return handle<AssistantMessage[]>(res);
}

export async function clearConversation(token: string, conversationId: string): Promise<void> {
  const res = await fetch(`${BASE}/conversations/${conversationId}/clear`, {
    method: 'POST',
    headers: authHeaders(token),
  });
  if (!res.ok) await handle(res);
}

/**
 * Thumbs up or down on the last answer.
 *
 * Deliberately swallows its own failure. Feedback is a courtesy the user pays us, not a
 * transaction: showing them an error because we could not record their opinion of an answer they
 * already have would be worse than losing the signal.
 */
export async function sendFeedback(
  token: string,
  conversationId: string,
  rating: 'UP' | 'DOWN',
  comment?: string,
): Promise<void> {
  try {
    await fetch(`${BASE}/feedback`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify({ conversationId, rating, comment }),
    });
  } catch { /* best effort */ }
}

/**
 * Whether the assistant is usable at all.
 *
 * Any authenticated user can call this — it is what decides whether the launcher renders, so
 * gating it behind a role would make every employee's first interaction a 403.
 */
export async function fetchHealth(token: string): Promise<AssistantHealth> {
  const res = await fetch(`${BASE}/health`, { headers: authHeaders(token) });
  return handle<AssistantHealth>(res);
}
