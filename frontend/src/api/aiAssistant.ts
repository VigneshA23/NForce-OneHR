import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/ai-assistant`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

interface ApiErrorBody {
  message?: string;
  code?: string;
  lockedUntil?: string;
}

/**
 * Thrown instead of a plain Error when the assistant responds 429 with
 * `code: "AI_ASSISTANT_RATE_LIMIT_EXCEEDED"` (see AiExceptionHandler#handleRateLimitExceeded), so
 * the panel can show the specific usage-limit message rather than a generic failure.
 *
 * Modelled directly on `api/auth.ts`'s `LoginLockedError` (423 + `code: 'ACCOUNT_LOCKED'` +
 * `lockedUntil`) - same shape, same reason for existing: an absolute instant a countdown can be
 * rendered from, reused rather than inventing a second one.
 */
export class AssistantRateLimitedError extends Error {
  retryAt: string | null;

  constructor(message: string, retryAt: string | null) {
    super(message);
    this.name = 'AssistantRateLimitedError';
    this.retryAt = retryAt;
  }
}

async function handle<T>(res: Response): Promise<T> {
  let body: ApiErrorBody = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) {
    if (res.status === 429 && body.code === 'AI_ASSISTANT_RATE_LIMIT_EXCEEDED') {
      throw new AssistantRateLimitedError(
        body.message ?? 'You have reached the AI Assistant usage limit.', body.lockedUntil ?? null);
    }
    throw new Error(body.message ?? `Request failed (${res.status})`);
  }
  return body as T;
}

/**
 * "Please try again in about N minutes", from an absolute instant - the same rounding-up-to-whole-
 * units idea as `Login.tsx`'s `formatRemainingLockTime`, just in minutes instead of hours since this
 * window is usually much shorter than an account lockout.
 */
export function formatRetryEstimate(retryAtIso: string | null): string {
  if (!retryAtIso) return 'Please try again shortly.';
  const msRemaining = new Date(retryAtIso).getTime() - Date.now();
  if (msRemaining <= 0) return 'Please try again now.';
  const minutes = Math.max(1, Math.ceil(msRemaining / (1000 * 60)));
  return `Please try again in about ${minutes} ${minutes === 1 ? 'minute' : 'minutes'}.`;
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
 * Whether the assistant is usable at all.
 *
 * Any authenticated user can call this — it is what decides whether the launcher renders, so
 * gating it behind a role would make every employee's first interaction a 403.
 */
export async function fetchHealth(token: string): Promise<AssistantHealth> {
  const res = await fetch(`${BASE}/health`, { headers: authHeaders(token) });
  return handle<AssistantHealth>(res);
}

/** The Super-Admin-editable per-user request budget. Read is Super-Admin-only too, not just write. */
export interface AiRateLimitSettings {
  id: string;
  enabled: boolean;
  requestsPerWindow: number;
  windowMinutes: number;
  updatedAt: string;
}

export interface UpdateAiRateLimitSettingsRequest {
  enabled: boolean;
  requestsPerWindow: number;
  windowMinutes: number;
}

export async function fetchRateLimitSettings(token: string): Promise<AiRateLimitSettings> {
  const res = await fetch(`${BASE}/admin/rate-limit-settings`, { headers: authHeaders(token) });
  return handle<AiRateLimitSettings>(res);
}

export async function updateRateLimitSettings(
  token: string,
  request: UpdateAiRateLimitSettingsRequest,
): Promise<AiRateLimitSettings> {
  const res = await fetch(`${BASE}/admin/rate-limit-settings`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handle<AiRateLimitSettings>(res);
}

// ── API Usage (Super Admin) ─────────────────────────────────────────────────

export interface AiUsageDailyPoint {
  date: string;
  /** Real Mistral API-call attempts (embedding + completion, including retries), not a turn count. */
  requestCount: number;
  successCount: number;
  errorCount: number;
  promptTokens: number;
  completionTokens: number;
  embeddingTokens: number;
}

export interface AiUsageBreakdownPoint {
  key: string;
  count: number;
}

export interface AiUsageStats {
  from: string;
  to: string;
  /** Real Mistral HTTP requests (embedding + completion attempts, including retries) — see
   *  totalTurns for the older, coarser "how many questions were asked" figure. */
  totalRequests: number;
  totalTurns: number;
  successCount: number;
  errorCount: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalEmbeddingTokens: number;
  totalTokens: number;
  avgLatencyMs: number;
  daily: AiUsageDailyPoint[];
  byErrorCode: AiUsageBreakdownPoint[];
  byResponseType: AiUsageBreakdownPoint[];
}

/** Super-Admin-only. `days` matches the server's own clamp (1-90, default 30). */
export async function fetchUsageStats(token: string, days?: number): Promise<AiUsageStats> {
  const qs = days ? `?days=${days}` : '';
  const res = await fetch(`${BASE}/admin/usage-stats${qs}`, { headers: authHeaders(token) });
  return handle<AiUsageStats>(res);
}

// ── Billing estimate (Super Admin) ──────────────────────────────────────────
//
// An estimate computed from OneHR's own token logs × an admin-configured price, never real
// Mistral billing (OneHR has no billing API to read that from) — the Mistral Admin Console
// remains the source of truth for the actual invoiced cost.

export interface AiBilling {
  monthStart: string;
  today: string;
  promptTokens: number;
  completionTokens: number;
  embeddingTokens: number;
  monthlyBudgetUsd: number;
  estimatedCostUsd: number;
  /** 0-100+, uncapped so the caller can distinguish "at budget" from "over budget". */
  usedPercent: number;
}

export interface AiBillingSettings {
  id: string;
  monthlyBudgetUsd: number;
  promptCostPerMillionUsd: number;
  completionCostPerMillionUsd: number;
  embeddingCostPerMillionUsd: number;
  updatedAt: string;
}

export interface UpdateAiBillingSettingsRequest {
  monthlyBudgetUsd: number;
  promptCostPerMillionUsd: number;
  completionCostPerMillionUsd: number;
  embeddingCostPerMillionUsd: number;
}

export async function fetchBilling(token: string): Promise<AiBilling> {
  const res = await fetch(`${BASE}/admin/billing`, { headers: authHeaders(token) });
  return handle<AiBilling>(res);
}

export async function fetchBillingSettings(token: string): Promise<AiBillingSettings> {
  const res = await fetch(`${BASE}/admin/billing-settings`, { headers: authHeaders(token) });
  return handle<AiBillingSettings>(res);
}

export async function updateBillingSettings(
  token: string,
  request: UpdateAiBillingSettingsRequest,
): Promise<AiBillingSettings> {
  const res = await fetch(`${BASE}/admin/billing-settings`, {
    method: 'PUT',
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handle<AiBillingSettings>(res);
}
