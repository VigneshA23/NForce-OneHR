import { useCallback, useEffect, useState } from 'react';
import {
  Activity, Database, ExternalLink, Gauge, MessageSquareText, RefreshCw, Settings2, ShieldAlert, Zap,
} from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Legend,
} from 'recharts';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  fetchUsageStats, fetchBilling, fetchBillingSettings, updateBillingSettings,
  fetchHealth, reindexKnowledge,
  type AiUsageStats, type AiBilling, type AiBillingSettings, type AssistantHealth, type KnowledgeIndexingReport,
} from '../api/aiAssistant';

// ── Shared styles (matches ReportsPage.tsx — plain style objects, no CSS framework) ──

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };

const MISTRAL_ADMIN_URL = 'https://admin.mistral.ai/';

// Distinct from PIE_COLORS elsewhere in the app (ReportsPage/MyTeamPage) — this page's palette is
// success/error/token-source based, not attendance-status based, so it earns its own small set
// rather than importing an unrelated one that happens to share a couple of hex values.
const COLOR_SUCCESS = '#2FB67C';
const COLOR_ERROR = '#E4373D';
const COLOR_PROMPT = '#4C8DD6';
const COLOR_COMPLETION = '#818CF8';
const RESPONSE_TYPE_COLORS: Record<string, string> = {
  HOW_TO: '#2FB67C', EXPLANATION: '#4C8DD6', NAVIGATION: '#818CF8',
  TROUBLESHOOTING: '#E0A93B', PERMISSION: '#B11116', UNKNOWN: 'var(--txt-dim)',
};

const PERIOD_OPTIONS = [
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
] as const;

// Explicit 'en-US' everywhere on this page rather than a bare .toLocaleString()/Intl default,
// which otherwise renders using the browser's OS locale (e.g. en-IN's 1,00,000 lakh grouping) —
// this page always shows American thousands-grouping regardless of the viewer's system locale.
const numberFormatter = new Intl.NumberFormat('en-US');
const compactNumberFormatter = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
const usdFormatter = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

/** "1,234,567" — full grouped number, for KPI cards, tooltips, and other exact readouts. */
function formatNumber(n: number): string { return numberFormatter.format(n); }
/** "1.2M" / "45K" — short form for chart axis ticks, where full grouped numbers would clip. */
function formatCompact(n: number): string { return compactNumberFormatter.format(n); }

function KpiCard({ icon, label, value, note, danger }: { icon: React.ReactNode; label: string; value: string | number; note?: string; danger?: boolean }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ color: 'var(--brand)' }}>{icon}</span>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em' }}>{label}</span>
      </div>
      <div style={{ fontSize: 28, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: danger ? COLOR_ERROR : 'var(--txt)', lineHeight: 1 }}>{value}</div>
      {note && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4 }}>{note}</div>}
    </div>
  );
}

function ChartCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, padding: '16px 18px' }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{title}</div>
      {subtitle && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2, marginBottom: 12 }}>{subtitle}</div>}
      {!subtitle && <div style={{ marginBottom: 12 }} />}
      {children}
    </div>
  );
}

/** "Sep 16" — same short-date convention as the rest of the app's chart axes. */
function shortDate(iso: string): string {
  return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

/** Human-friendly error/response-type code, e.g. "RATE_LIMITED" -> "Rate Limited". */
function humanizeCode(code: string): string {
  return code.toLowerCase().split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

function AccessDenied() {
  return (
    <div style={{ ...card, padding: 40, textAlign: 'center' }}>
      <ShieldAlert size={28} style={{ color: 'var(--txt-dim)', marginBottom: 10 }} />
      <div style={{ fontSize: 15, color: 'var(--txt)', marginBottom: 6 }}>You don't have access to this page</div>
      <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>API Usage is available to Super Admins only.</div>
    </div>
  );
}

const inputS: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 9px', fontSize: 12.5, color: 'var(--txt)', width: 110, boxSizing: 'border-box' };

/**
 * This calendar month's spend against a Super-Admin-configured budget — an estimate (OneHR's own
 * token counts × a price the admin enters), never real Mistral billing, which OneHR has no API
 * access to. Loads independently of the period selector above/below it: billing is always "this
 * month", regardless of whether the charts are showing the last 7/30/90 days.
 */
function BillingCard({ token }: { token: string }) {
  const { showToast } = useToast();

  const [billing, setBilling] = useState<AiBilling | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [settings, setSettings] = useState<AiBillingSettings | null>(null);
  const [budget, setBudget] = useState('');
  const [promptCost, setPromptCost] = useState('');
  const [completionCost, setCompletionCost] = useState('');
  const [embeddingCost, setEmbeddingCost] = useState('');
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchBilling(token)
      .then(setBilling)
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load billing'))
      .finally(() => setLoading(false));
  }, [token]);

  useEffect(() => { load(); }, [load]);

  function openEditor() {
    setSaveError('');
    setEditing(true);
    if (settings) return; // already loaded once this session
    fetchBillingSettings(token).then(s => {
      setSettings(s);
      setBudget(String(s.monthlyBudgetUsd));
      setPromptCost(String(s.promptCostPerMillionUsd));
      setCompletionCost(String(s.completionCostPerMillionUsd));
      setEmbeddingCost(String(s.embeddingCostPerMillionUsd));
    }).catch(err => setSaveError(err instanceof Error ? err.message : "Couldn't load billing settings"));
  }

  const dirty = settings != null && (
    Number(budget) !== settings.monthlyBudgetUsd
    || Number(promptCost) !== settings.promptCostPerMillionUsd
    || Number(completionCost) !== settings.completionCostPerMillionUsd
    || Number(embeddingCost) !== settings.embeddingCostPerMillionUsd
  );

  async function save() {
    setSaveError('');
    const budgetN = Number(budget);
    const promptN = Number(promptCost);
    const completionN = Number(completionCost);
    const embeddingN = Number(embeddingCost);
    if (budget.trim() === '' || !Number.isFinite(budgetN) || budgetN < 0) {
      setSaveError('Monthly budget must be a number that is 0 or more'); return;
    }
    if (promptCost.trim() === '' || !Number.isFinite(promptN) || promptN < 0) {
      setSaveError('Prompt cost must be a number that is 0 or more'); return;
    }
    if (completionCost.trim() === '' || !Number.isFinite(completionN) || completionN < 0) {
      setSaveError('Completion cost must be a number that is 0 or more'); return;
    }
    if (embeddingCost.trim() === '' || !Number.isFinite(embeddingN) || embeddingN < 0) {
      setSaveError('Embedding cost must be a number that is 0 or more'); return;
    }
    setSaving(true);
    try {
      const updated = await updateBillingSettings(token, {
        monthlyBudgetUsd: budgetN, promptCostPerMillionUsd: promptN, completionCostPerMillionUsd: completionN,
        embeddingCostPerMillionUsd: embeddingN,
      });
      setSettings(updated);
      setBudget(String(updated.monthlyBudgetUsd));
      setPromptCost(String(updated.promptCostPerMillionUsd));
      setCompletionCost(String(updated.completionCostPerMillionUsd));
      setEmbeddingCost(String(updated.embeddingCostPerMillionUsd));
      showToast('success', 'Billing settings updated');
      setEditing(false);
      load(); // refresh the progress bar against the new budget/pricing
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setSaving(false);
    }
  }

  const pct = billing ? Math.min(100, billing.usedPercent) : 0;
  const overBudget = billing ? billing.usedPercent > 100 : false;
  const barColor = overBudget ? COLOR_ERROR : (billing?.usedPercent ?? 0) >= 80 ? '#E0A93B' : COLOR_SUCCESS;

  return (
    <div style={{ ...card, padding: '16px 18px', marginBottom: 18 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, marginBottom: 4 }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>This month's billing (estimate)</div>
          {billing && (
            <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>
              {new Date(billing.monthStart + 'T00:00:00').toLocaleDateString('en-US', { month: 'long', year: 'numeric' })} · based on OneHR's own token logs, not Mistral's actual invoice
            </div>
          )}
        </div>
        <button onClick={() => (editing ? setEditing(false) : openEditor())} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', cursor: 'pointer', flexShrink: 0 }}>
          <Settings2 size={12} /> {editing ? 'Close' : 'Configure'}
        </button>
      </div>

      {loading ? (
        <div style={{ padding: '16px 0', color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading…</div>
      ) : error ? (
        <div style={{ padding: '16px 0' }}>
          <span style={{ color: COLOR_ERROR, fontSize: 12.5 }}>Couldn't load billing ({error}).</span>{' '}
          <button onClick={load} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 12.5, padding: 0 }}>Retry</button>
        </div>
      ) : billing && (
        <>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginTop: 14, marginBottom: 6 }}>
            <span style={{ fontSize: 22, fontWeight: 700, fontFamily: 'Inter, sans-serif', color: overBudget ? COLOR_ERROR : 'var(--txt)' }}>
              {usdFormatter.format(billing.estimatedCostUsd)}
            </span>
            <span style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>
              of {usdFormatter.format(billing.monthlyBudgetUsd)} budget · {Math.round(billing.usedPercent)}% used
            </span>
          </div>
          <div style={{ height: 10, borderRadius: 6, background: 'var(--raised2)', overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${pct}%`, background: barColor, borderRadius: 6, transition: 'width .3s ease' }} />
          </div>
          {overBudget && (
            <div style={{ fontSize: 11.5, color: COLOR_ERROR, marginTop: 6 }}>
              Over the configured budget for this month.
            </div>
          )}
          <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 8 }}>
            {formatNumber(billing.promptTokens)} prompt + {formatNumber(billing.completionTokens)} completion + {formatNumber(billing.embeddingTokens)} embedding tokens so far this month
          </div>
        </>
      )}

      {editing && (
        <div style={{ marginTop: 16, paddingTop: 16, borderTop: '1px solid var(--line)' }}>
          {!settings ? (
            <div style={{ color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading settings…</div>
          ) : (
            <>
              <p style={{ margin: '0 0 12px', fontSize: 11.5, color: 'var(--txt-dim)', lineHeight: 1.5 }}>
                Set these to match your actual Mistral plan pricing (check the Admin Console) — OneHR
                has no way to read your real prices automatically.
              </p>
              <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap' }}>
                <div>
                  <label style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Monthly budget (USD)</label>
                  <input type="number" min={0} step="0.01" style={inputS} value={budget} onChange={e => setBudget(e.target.value)} />
                </div>
                <div>
                  <label style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Prompt $/1M tokens</label>
                  <input type="number" min={0} step="0.0001" style={inputS} value={promptCost} onChange={e => setPromptCost(e.target.value)} />
                </div>
                <div>
                  <label style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Completion $/1M tokens</label>
                  <input type="number" min={0} step="0.0001" style={inputS} value={completionCost} onChange={e => setCompletionCost(e.target.value)} />
                </div>
                <div>
                  <label style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Embedding $/1M tokens</label>
                  <input type="number" min={0} step="0.0001" style={inputS} value={embeddingCost} onChange={e => setEmbeddingCost(e.target.value)} />
                </div>
                <button onClick={save} disabled={saving || !dirty} style={{
                  padding: '8px 16px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
                  fontSize: 12.5, fontWeight: 600, cursor: saving || !dirty ? 'not-allowed' : 'pointer', opacity: saving || !dirty ? 0.6 : 1,
                }}>
                  {saving ? 'Saving…' : 'Save'}
                </button>
              </div>
              {saveError && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginTop: 10 }}>{saveError}</div>}
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** "Sep 24, 2026, 6:59 PM" — full timestamp, since an admin deciding whether to re-index needs the
 *  exact moment, not a relative "3 hours ago" that goes stale the instant the page is left open. */
function formatIndexedAt(iso: string | null): string {
  if (!iso) return 'Never';
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

/**
 * The assistant answers only from what was in the index at the last re-index — editing Help
 * Content or a knowledge YAML file has zero effect on live answers until this runs (see
 * KnowledgeIndexingService's own Javadoc: no startup/scheduled trigger, by design, since a full
 * rebuild costs real embedding calls and briefly leaves the index inconsistent). This card exists
 * so that "I fixed the knowledge base" and "the assistant now answers correctly" are the same
 * moment for a Super Admin, instead of a manual API call nobody but engineering knows to make.
 */
function KnowledgeBaseCard({ token }: { token: string }) {
  const { showToast } = useToast();

  const [health, setHealth] = useState<AssistantHealth | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reindexing, setReindexing] = useState(false);
  const [lastReport, setLastReport] = useState<KnowledgeIndexingReport | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchHealth(token)
      .then(setHealth)
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load index status'))
      .finally(() => setLoading(false));
  }, [token]);

  useEffect(() => { load(); }, [load]);

  async function runReindex() {
    if (!window.confirm(
      "Re-index the knowledge base now?\n\nThis rebuilds NORA's entire search index from the " +
      'current Help Content and knowledge files, costs real Mistral embedding calls, and takes ' +
      'about a minute. Do this after publishing a Help Content change or editing a knowledge file.',
    )) return;
    setReindexing(true);
    try {
      const report = await reindexKnowledge(token);
      setLastReport(report);
      showToast('success', `Re-indexed: ${report.documents} documents → ${report.chunks} chunks`);
      load(); // refresh indexReady/indexedChunks/lastIndexedAt against the new index
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Re-index failed');
    } finally {
      setReindexing(false);
    }
  }

  return (
    <div style={{ ...card, padding: '16px 18px', marginBottom: 18 }}>
      <style>{'@keyframes nf-spin { to { transform: rotate(360deg); } }'}</style>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            <Database size={14} style={{ color: 'var(--brand)' }} /> Knowledge base
          </div>
          <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>
            What NORA answers from. A Help Content or knowledge-file change only takes effect here.
          </div>
        </div>
        <button
          onClick={runReindex}
          disabled={reindexing || loading}
          style={{
            display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0,
            background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px',
            fontSize: 11.5, fontWeight: 600, color: 'var(--txt-mut)',
            cursor: (reindexing || loading) ? 'default' : 'pointer', opacity: loading ? 0.6 : 1,
          }}
        >
          <RefreshCw size={12} style={reindexing ? { animation: 'nf-spin 1s linear infinite' } : undefined} />
          {reindexing ? 'Re-indexing…' : 'Re-index now'}
        </button>
      </div>

      {loading ? (
        <div style={{ padding: '16px 0', color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading…</div>
      ) : error ? (
        <div style={{ padding: '16px 0' }}>
          <span style={{ color: COLOR_ERROR, fontSize: 12.5 }}>Couldn't load index status ({error}).</span>{' '}
          <button onClick={load} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 12.5, padding: 0 }}>Retry</button>
        </div>
      ) : health && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px 28px', marginTop: 14 }}>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Status</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: health.indexReady ? COLOR_SUCCESS : COLOR_ERROR, marginTop: 3 }}>
              {health.indexReady ? 'Ready' : 'Not ready'}
            </div>
          </div>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Indexed chunks</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', marginTop: 3 }}>{formatNumber(health.indexedChunks)}</div>
          </div>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Last indexed</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', marginTop: 3 }}>{formatIndexedAt(health.lastIndexedAt)}</div>
          </div>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>Sources</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', marginTop: 3 }}>
              {health.knowledgeSources.length > 0 ? health.knowledgeSources.join(', ') : '—'}
            </div>
          </div>
        </div>
      )}

      {reindexing && (
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 12 }}>
          Rebuilding the index — this page will update automatically when it's done. Answers keep
          using the previous index until the rebuild finishes.
        </div>
      )}
      {lastReport && !reindexing && (
        <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 12 }}>
          Last run: {formatNumber(lastReport.documents)} documents → {formatNumber(lastReport.chunks)} chunks
          ({formatNumber(lastReport.removed)} stale rows replaced).
        </div>
      )}
    </div>
  );
}

export default function ApiUsagePage() {
  const token = useAuthStore(s => s.token) ?? '';
  const role = useAuthStore(s => s.user?.role);
  const authorized = role === 'SUPER_ADMIN';

  const [days, setDays] = useState<number>(30);
  const [stats, setStats] = useState<AiUsageStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!authorized) return;
    setLoading(true);
    setError(null);
    fetchUsageStats(token, days)
      .then(setStats)
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load usage stats'))
      .finally(() => setLoading(false));
  }, [token, days, authorized]);

  useEffect(() => { load(); }, [load]);

  if (!authorized) return <AccessDenied />;

  const requestsChartData = (stats?.daily ?? []).map(d => ({
    date: shortDate(d.date), Requests: d.requestCount,
  }));
  const tokensChartData = (stats?.daily ?? []).map(d => ({
    date: shortDate(d.date), Prompt: d.promptTokens, Completion: d.completionTokens, Embedding: d.embeddingTokens,
  }));
  const responseTypeData = (stats?.byResponseType ?? []).map(b => ({ name: humanizeCode(b.key), value: b.count, key: b.key }));

  // Success/error rate is a per-turn concept (did the question get answered), so it's computed
  // against totalTurns, not totalRequests (real API-call attempts) - a turn retried twice by the
  // transport before succeeding is still one successful turn, not two-thirds of one.
  const successRate = stats && stats.totalTurns > 0
    ? `${Math.round((stats.successCount / stats.totalTurns) * 100)}%`
    : '—';

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>API Usage</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13, maxWidth: '62ch' }}>
            NORA's request volume and token usage, so growing adoption never turns into a budget
            surprise — and if usage spikes, the error breakdown below traces it to a cause instead
            of leaving it opaque.
          </p>
        </div>
        <a
          href={MISTRAL_ADMIN_URL}
          target="_blank"
          rel="noopener noreferrer"
          style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', textDecoration: 'none', flexShrink: 0 }}
        >
          Open Mistral Admin Console <ExternalLink size={13} />
        </a>
      </div>

      <KnowledgeBaseCard token={token} />
      <BillingCard token={token} />

      {/* Period selector */}
      <div style={{ display: 'inline-flex', gap: 4, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 9, padding: 4, marginBottom: 18 }} role="tablist" aria-label="Usage period">
        {PERIOD_OPTIONS.map(opt => (
          <button
            key={opt.days}
            role="tab"
            aria-selected={days === opt.days}
            onClick={() => setDays(opt.days)}
            style={{
              fontSize: 12.5, fontWeight: 600, padding: '7px 14px', borderRadius: 6, border: 'none', cursor: 'pointer',
              background: days === opt.days ? 'var(--brand)' : 'transparent', color: days === opt.days ? '#fff' : 'var(--txt-mut)',
            }}
          >
            Last {opt.label}
          </button>
        ))}
      </div>

      {loading ? (
        <div style={{ ...card, padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
      ) : error ? (
        <div style={{ ...card, padding: 40, textAlign: 'center' }}>
          <span style={{ color: COLOR_ERROR }}>Couldn't load usage stats ({error}).</span>{' '}
          <button onClick={load} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 13, padding: 0 }}>Retry</button>
        </div>
      ) : stats && (
        <>
          {/* KPI cards */}
          <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12, marginBottom: 20 }}>
            <KpiCard icon={<MessageSquareText size={14} />} label="Total Requests" value={formatNumber(stats.totalRequests)} note={`real Mistral calls · ${formatNumber(stats.totalTurns)} question${stats.totalTurns === 1 ? '' : 's'} asked`} />
            <KpiCard icon={<Zap size={14} />} label="Total Tokens" value={formatNumber(stats.totalTokens)} note={`${formatNumber(stats.totalPromptTokens)} prompt · ${formatNumber(stats.totalCompletionTokens)} completion · ${formatNumber(stats.totalEmbeddingTokens)} embedding`} />
            <KpiCard icon={<Activity size={14} />} label="Success Rate" value={successRate} note={`${stats.errorCount} failed question${stats.errorCount === 1 ? '' : 's'}`} danger={stats.totalTurns > 0 && stats.errorCount / stats.totalTurns > 0.1} />
            <KpiCard icon={<Gauge size={14} />} label="Avg Latency" value={`${Math.round(stats.avgLatencyMs)}ms`} note="per completed request" />
          </div>

          {/* Charts */}
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: 12, marginBottom: 12 }} className="nf-grid-side-collapse">
            <ChartCard title="Request volume" subtitle="Real Mistral API requests per day (embedding + completion calls, including retries)">
              {requestsChartData.length === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No requests in this period.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={requestsChartData} margin={{ top: 0, right: 0, left: 0, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                    <XAxis dataKey="date" tick={{ fontSize: 10.5, fill: 'var(--txt-dim)' }} axisLine={{ stroke: 'var(--line2)' }} tickLine={false} />
                    <YAxis allowDecimals={false} tick={{ fontSize: 10.5, fill: 'var(--txt-dim)' }} axisLine={false} tickLine={false} tickFormatter={formatCompact} width={42} />
                    <Tooltip contentStyle={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 12 }} formatter={(value) => formatNumber(Number(value))} />
                    <Bar dataKey="Requests" fill={COLOR_SUCCESS} radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </ChartCard>

            <ChartCard title="Token usage" subtitle="Prompt, completion, and embedding tokens per day">
              {tokensChartData.length === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No token usage in this period.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={tokensChartData} margin={{ top: 0, right: 0, left: 0, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                    <XAxis dataKey="date" tick={{ fontSize: 10.5, fill: 'var(--txt-dim)' }} axisLine={{ stroke: 'var(--line2)' }} tickLine={false} />
                    <YAxis allowDecimals={false} tick={{ fontSize: 10.5, fill: 'var(--txt-dim)' }} axisLine={false} tickLine={false} tickFormatter={formatCompact} width={42} />
                    <Tooltip contentStyle={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 12 }} formatter={(value) => formatNumber(Number(value))} />
                    <Bar dataKey="Prompt" stackId="tok" fill={COLOR_PROMPT} radius={[0, 0, 0, 0]} />
                    <Bar dataKey="Completion" stackId="tok" fill={COLOR_COMPLETION} radius={[0, 0, 0, 0]} />
                    <Bar dataKey="Embedding" stackId="tok" fill={COLOR_SUCCESS} radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </ChartCard>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1.4fr)', gap: 12, marginBottom: 20 }} className="nf-grid-side-collapse">
            <ChartCard title="Answers by type" subtitle="What kind of question NORA is answering">
              {responseTypeData.length === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No requests in this period.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <PieChart>
                    <Pie data={responseTypeData} dataKey="value" nameKey="name" innerRadius={45} outerRadius={80} paddingAngle={2}>
                      {responseTypeData.map(d => <Cell key={d.key} fill={RESPONSE_TYPE_COLORS[d.key] ?? 'var(--txt-dim)'} />)}
                    </Pie>
                    <Tooltip contentStyle={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 12 }} />
                    <Legend wrapperStyle={{ fontSize: 11 }} />
                  </PieChart>
                </ResponsiveContainer>
              )}
            </ChartCard>

            {/* AC2: "usage spikes are traceable to a cause rather than opaque" — an error-code
                breakdown is exactly that trace. Deliberately not a chart when there's nothing to
                show: an empty bar chart reads as "broken", an explicit all-clear message doesn't. */}
            <ChartCard title="Failed requests by cause" subtitle="Where errors are coming from, if any">
              {(stats.byErrorCode.length === 0) ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>
                  No failed requests in this period.
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {stats.byErrorCode.map(b => {
                    const pct = stats.errorCount > 0 ? (b.count / stats.errorCount) * 100 : 0;
                    return (
                      <div key={b.key}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 3 }}>
                          <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{humanizeCode(b.key)}</span>
                          <span style={{ color: 'var(--txt-dim)' }}>{b.count}</span>
                        </div>
                        <div style={{ height: 6, borderRadius: 4, background: 'var(--raised2)', overflow: 'hidden' }}>
                          <div style={{ height: '100%', width: `${pct}%`, background: COLOR_ERROR, borderRadius: 4 }} />
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </ChartCard>
          </div>

          <div style={{ ...card, padding: '14px 18px', display: 'flex', alignItems: 'center', gap: 10, fontSize: 12, color: 'var(--txt-dim)' }}>
            <Zap size={14} style={{ flexShrink: 0 }} />
            Counts come from OneHR's own request logs (every embedding and completion call a
            question costs, including retries), not from Mistral's billing — so this figure may
            still run a little under Mistral's own count, since rebuilding the knowledge index also
            calls Mistral directly and isn't tied to a question. For the actual dollar cost and
            rate-limit/quota details, use the{' '}
            <a href={MISTRAL_ADMIN_URL} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--info)' }}>Mistral Admin Console</a>.
          </div>
        </>
      )}
    </div>
  );
}
