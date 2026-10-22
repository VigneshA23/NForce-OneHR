import { useCallback, useEffect, useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import DashTile from '../components/DashTile';
import {
  Activity, ExternalLink, Gauge, MessageSquareText, RefreshCw, Settings2, ShieldAlert, Zap,
} from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Legend,
} from 'recharts';
import { useAuthStore } from '../store/authStore';
import { hasPermission } from '../lib/permissions';
import { useToast } from '../context/ToastContext';
import { ChipGroup } from '../components/TabBar';
import {
  fetchUsageStats, fetchBilling, fetchBillingSettings, updateBillingSettings,
  fetchHealth, reindexKnowledge,
  type AiUsageStats, type AiBilling, type AiBillingSettings, type AssistantHealth, type KnowledgeIndexingReport,
} from '../api/aiAssistant';

// Same card/chart conventions as ReportsPage and My Team: theme tokens only, so light/dark and the
// accent colour setting keep working.

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, boxShadow: '0 1px 2px rgba(16, 24, 40, .05)', overflow: 'hidden' };

const MISTRAL_ADMIN_URL = 'https://admin.mistral.ai/';

const COLOR_SUCCESS = 'var(--ok)';
const COLOR_ERROR = 'var(--risk)';
const COLOR_WARN = 'var(--warn)';
const COLOR_PROMPT = 'var(--info)';
const COLOR_COMPLETION = '#818CF8';
const RESPONSE_TYPE_COLORS: Record<string, string> = {
  HOW_TO: 'var(--ok)', EXPLANATION: 'var(--info)', NAVIGATION: '#818CF8',
  TROUBLESHOOTING: 'var(--warn)', PERMISSION: 'var(--brand)', UNKNOWN: 'var(--txt-dim)',
};

// Recharts tooltip, same look as ReportsPage.
const tooltipStyle: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' };
const axisTick = { fill: 'var(--txt-dim)', fontSize: 10.5 };

// Shared small-button / label styles for the cards below.
const ghostBtn: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 12px', fontSize: 12, fontWeight: 600, color: 'var(--txt)', cursor: 'pointer' };
const miniLabel: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em' };
const cardTitle: React.CSSProperties = { fontSize: 14, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', margin: 0 };
const cardSub: React.CSSProperties = { fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2 };

const PERIOD_OPTIONS = [
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
] as const;

// Explicit 'en-US' everywhere on this page rather than a bare .toLocaleString()/Intl default,
// which otherwise renders using the browser's OS locale (e.g. en-IN's 1,00,000 lakh grouping).
// This page always shows American thousands-grouping regardless of the viewer's system locale.
const numberFormatter = new Intl.NumberFormat('en-US');
const compactNumberFormatter = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
const usdFormatter = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

/** "1,234,567", full grouped number for KPI cards, tooltips and other exact readouts. */
function formatNumber(n: number): string { return numberFormatter.format(n); }
/** "1.2M" / "45K", short form for chart axis ticks where full grouped numbers would clip. */
function formatCompact(n: number): string { return compactNumberFormatter.format(n); }

// Same tile as the Home dashboard (components/DashTile); display only.
export function KpiCard({ icon, iconColor, label, value, note, danger }: { icon: React.ReactNode; iconColor: string; label: string; value: string | number; note?: string; danger?: boolean }) {
  return <DashTile icon={icon} label={label} value={String(value)} sub={note} accent={danger ? COLOR_ERROR : iconColor} />;
}

function ChartCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div style={{ ...card, padding: '16px 18px' }}>
      <div style={cardTitle}>{title}</div>
      {subtitle && <div style={cardSub}>{subtitle}</div>}
      <div style={{ marginBottom: 14 }} />
      {children}
    </div>
  );
}

/** "Sep 16", same short-date convention as the rest of the app's chart axes. */
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

const inputS: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '8px 10px', fontSize: 12.5, color: 'var(--txt)', width: '100%', boxSizing: 'border-box' };
const fieldLabel: React.CSSProperties = { ...miniLabel, display: 'block', marginBottom: 6 };

/**
 * This calendar month's spend against a Super Admin configured budget. It is an estimate (OneHR's
 * own token counts times a price the admin enters), never real Mistral billing, which OneHR has no
 * API access to. Loads independently of the period selector: billing is always "this month",
 * whether the charts show the last 7, 30 or 90 days.
 */
function BillingCard({ token, canConfigure }: { token: string; canConfigure: boolean }) {
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
  const barColor = overBudget ? COLOR_ERROR : (billing?.usedPercent ?? 0) >= 80 ? COLOR_WARN : COLOR_SUCCESS;

  return (
    <div style={{ ...card, padding: '16px 18px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, marginBottom: 4 }}>
        <div>
          <div style={cardTitle}>Monthly billing estimate</div>
          {billing && (
            <div style={cardSub}>
              {new Date(billing.monthStart + 'T00:00:00').toLocaleDateString('en-US', { month: 'long', year: 'numeric' })} · based on OneHR's own token logs, not Mistral's invoice
            </div>
          )}
        </div>
        {canConfigure && (
          <button onClick={() => (editing ? setEditing(false) : openEditor())} style={ghostBtn}>
            <Settings2 size={13} /> {editing ? 'Close' : 'Configure'}
          </button>
        )}
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
          <div style={{ height: 8, borderRadius: 6, background: 'var(--raised2)', overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${pct}%`, background: barColor, borderRadius: 6, transition: 'width .3s ease' }} />
          </div>
          {overBudget && (
            <div style={{ fontSize: 11.5, color: COLOR_ERROR, marginTop: 6 }}>
              Over the configured budget for this month.
            </div>
          )}
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 8 }}>
            {formatNumber(billing.promptTokens)} prompt, {formatNumber(billing.completionTokens)} completion and {formatNumber(billing.embeddingTokens)} embedding tokens so far this month
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
                Match these to your Mistral plan pricing. You can find it in the Admin Console.
              </p>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 12 }}>
                <div>
                  <label style={fieldLabel}>Monthly budget (USD)</label>
                  <input type="number" min={0} step="0.01" style={inputS} value={budget} onChange={e => setBudget(e.target.value)} />
                </div>
                <div>
                  <label style={fieldLabel}>Prompt $/1M tokens</label>
                  <input type="number" min={0} step="0.0001" style={inputS} value={promptCost} onChange={e => setPromptCost(e.target.value)} />
                </div>
                <div>
                  <label style={fieldLabel}>Completion $/1M tokens</label>
                  <input type="number" min={0} step="0.0001" style={inputS} value={completionCost} onChange={e => setCompletionCost(e.target.value)} />
                </div>
                <div>
                  <label style={fieldLabel}>Embedding $/1M tokens</label>
                  <input type="number" min={0} step="0.0001" style={inputS} value={embeddingCost} onChange={e => setEmbeddingCost(e.target.value)} />
                </div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 14 }}>
                <button onClick={save} disabled={saving || !dirty} style={{
                  padding: '8px 18px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7,
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

/** "Sep 24, 2026, 6:59 PM": a full timestamp, since an admin deciding whether to re-index needs the
 *  exact moment, not a relative "3 hours ago" that goes stale while the page is left open. */
function formatIndexedAt(iso: string | null): string {
  if (!iso) return 'Never';
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

/**
 * The assistant answers only from what was in the index at the last re-index. Editing Help
 * Content or a knowledge YAML file has no effect on live answers until this runs (see
 * KnowledgeIndexingService's own Javadoc: no startup/scheduled trigger, by design, since a full
 * rebuild costs real embedding calls and briefly leaves the index inconsistent). This card exists
 * so that "I fixed the knowledge base" and "the assistant now answers correctly" are the same
 * moment for a Super Admin, instead of a manual API call nobody but engineering knows to make.
 */
function KnowledgeBaseCard({ token, canConfigure }: { token: string; canConfigure: boolean }) {
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
      showToast('success', `Re-indexed ${report.documents} documents into ${report.chunks} chunks`);
      load(); // refresh indexReady/indexedChunks/lastIndexedAt against the new index
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Re-index failed');
    } finally {
      setReindexing(false);
    }
  }

  return (
    <div style={{ ...card, padding: '16px 18px' }}>
      <style>{'@keyframes nf-spin { to { transform: rotate(360deg); } }'}</style>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
        <div>
          <div style={cardTitle}>Knowledge base</div>
          <div style={cardSub}>What NORA answers from. Content changes only apply after a re-index.</div>
        </div>
        {canConfigure && (
          <button
            onClick={runReindex}
            disabled={reindexing || loading}
            style={{ ...ghostBtn, cursor: (reindexing || loading) ? 'default' : 'pointer', opacity: loading ? 0.6 : 1 }}
          >
            <RefreshCw size={13} style={reindexing ? { animation: 'nf-spin 1s linear infinite' } : undefined} />
            {reindexing ? 'Re-indexing…' : 'Re-index now'}
          </button>
        )}
      </div>

      {loading ? (
        <div style={{ padding: '16px 0', color: 'var(--txt-dim)', fontSize: 12.5 }}>Loading…</div>
      ) : error ? (
        <div style={{ padding: '16px 0' }}>
          <span style={{ color: COLOR_ERROR, fontSize: 12.5 }}>Couldn't load index status ({error}).</span>{' '}
          <button onClick={load} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 12.5, padding: 0 }}>Retry</button>
        </div>
      ) : health && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: '14px 20px', marginTop: 16 }}>
          <div>
            <div style={miniLabel}>Status</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: health.indexReady ? COLOR_SUCCESS : COLOR_ERROR, marginTop: 4 }}>
              {health.indexReady ? 'Ready' : 'Not ready'}
            </div>
          </div>
          <div>
            <div style={miniLabel}>Indexed chunks</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', marginTop: 4 }}>{formatNumber(health.indexedChunks)}</div>
          </div>
          <div>
            <div style={miniLabel}>Last indexed</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', marginTop: 4 }}>{formatIndexedAt(health.lastIndexedAt)}</div>
          </div>
          <div>
            <div style={miniLabel}>Sources</div>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', marginTop: 4 }}>
              {health.knowledgeSources.length > 0 ? health.knowledgeSources.join(', ') : 'None'}
            </div>
          </div>
        </div>
      )}

      {reindexing && (
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 12 }}>
          Rebuilding the index. This page updates when it's done, and answers keep using the
          previous index until then.
        </div>
      )}
      {lastReport && !reindexing && (
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 12 }}>
          Last run: {formatNumber(lastReport.documents)} documents into {formatNumber(lastReport.chunks)} chunks
          ({formatNumber(lastReport.removed)} stale rows replaced).
        </div>
      )}
    </div>
  );
}

export default function ApiUsagePage() {
  const token = useAuthStore(s => s.token) ?? '';
  const permissions = useAuthStore(s => s.permissions);
  // The same permissions the /admin/* endpoints require (AiAssistantController) — both seeded
  // to SUPER_ADMIN only (V225).
  const authorized = hasPermission(permissions, 'ai_assistant', 'VIEW', 'ALL');
  const canConfigure = hasPermission(permissions, 'ai_assistant', 'CONFIGURE', 'ALL');

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

  // Success rate is per question (was it answered), so it is computed against totalTurns, not
  // totalRequests (real API call attempts). A question retried twice by the transport before
  // succeeding is still one successful question.
  const successRate = stats && stats.totalTurns > 0
    ? `${Math.round((stats.successCount / stats.totalTurns) * 100)}%`
    : 'N/A';

  return (
    <div>
      <div className="nf-page-hdr" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
        <PageTitleBlock icon={Gauge}>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>API Usage</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13, maxWidth: '62ch' }}>
            NORA request volume, token usage and estimated spend.
          </p>
        </PageTitleBlock>
        <a
          href={MISTRAL_ADMIN_URL}
          target="_blank"
          rel="noopener noreferrer"
          style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', textDecoration: 'none', flexShrink: 0 }}
        >
          Open Mistral Admin Console <ExternalLink size={13} />
        </a>
      </div>

      {/* Billing and knowledge base side by side, like the paired cards on the other admin pages */}
      <div className="nf-grid-side-collapse" style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: 12, marginBottom: 18, alignItems: 'start' }}>
        <BillingCard token={token} canConfigure={canConfigure} />
        <KnowledgeBaseCard token={token} canConfigure={canConfigure} />
      </div>

      {/* Period selector */}
      <ChipGroup
        ariaLabel="Usage period"
        style={{ marginBottom: 18 }}
        value={String(days)}
        onChange={k => setDays(Number(k))}
        options={PERIOD_OPTIONS.map(opt => ({ key: String(opt.days), label: `Last ${opt.label}` }))}
      />

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
            <KpiCard icon={<MessageSquareText size={20} />} iconColor="var(--info)" label="Total Requests" value={formatNumber(stats.totalRequests)} note={`${formatNumber(stats.totalTurns)} question${stats.totalTurns === 1 ? '' : 's'} asked`} />
            <KpiCard icon={<Zap size={20} />} iconColor="var(--brand-bright)" label="Total Tokens" value={formatNumber(stats.totalTokens)} note={`${formatCompact(stats.totalPromptTokens)} prompt · ${formatCompact(stats.totalCompletionTokens)} completion · ${formatCompact(stats.totalEmbeddingTokens)} embedding`} />
            <KpiCard icon={<Activity size={20} />} iconColor="var(--ok)" label="Success Rate" value={successRate} note={`${stats.errorCount} failed question${stats.errorCount === 1 ? '' : 's'}`} danger={stats.totalTurns > 0 && stats.errorCount / stats.totalTurns > 0.1} />
            <KpiCard icon={<Gauge size={20} />} iconColor="var(--warn)" label="Avg Latency" value={`${Math.round(stats.avgLatencyMs)}ms`} note="per completed request" />
          </div>

          {/* Charts */}
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: 12, marginBottom: 12 }} className="nf-grid-side-collapse">
            <ChartCard title="Request volume" subtitle="Mistral API requests per day, including retries">
              {requestsChartData.length === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No requests in this period.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={requestsChartData} margin={{ top: 0, right: 0, left: -10, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                    <XAxis dataKey="date" tick={axisTick} axisLine={false} tickLine={false} />
                    <YAxis allowDecimals={false} tick={axisTick} axisLine={false} tickLine={false} tickFormatter={formatCompact} width={42} />
                    <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'rgba(177,17,22,.08)' }} formatter={(value) => formatNumber(Number(value))} />
                    <Bar dataKey="Requests" fill="var(--brand)" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </ChartCard>

            <ChartCard title="Token usage" subtitle="Prompt, completion and embedding tokens per day">
              {tokensChartData.length === 0 ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No token usage in this period.</div>
              ) : (
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={tokensChartData} margin={{ top: 0, right: 0, left: -10, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--line)" vertical={false} />
                    <XAxis dataKey="date" tick={axisTick} axisLine={false} tickLine={false} />
                    <YAxis allowDecimals={false} tick={axisTick} axisLine={false} tickLine={false} tickFormatter={formatCompact} width={42} />
                    <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'rgba(177,17,22,.08)' }} formatter={(value) => formatNumber(Number(value))} />
                    <Legend verticalAlign="bottom" height={28} formatter={(value) => <span style={{ color: 'var(--txt-mut)', fontSize: 12 }}>{value}</span>} />
                    <Bar dataKey="Prompt" stackId="tok" fill={COLOR_PROMPT} radius={[0, 0, 0, 0]} />
                    <Bar dataKey="Completion" stackId="tok" fill={COLOR_COMPLETION} radius={[0, 0, 0, 0]} />
                    <Bar dataKey="Embedding" stackId="tok" fill={COLOR_SUCCESS} radius={[4, 4, 0, 0]} />
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
                    <Pie data={responseTypeData} dataKey="value" nameKey="name" cx="50%" cy="50%" innerRadius="55%" outerRadius="85%" strokeWidth={0}>
                      {responseTypeData.map(d => <Cell key={d.key} fill={RESPONSE_TYPE_COLORS[d.key] ?? 'var(--txt-dim)'} />)}
                    </Pie>
                    <Legend verticalAlign="bottom" height={28} formatter={(value) => <span style={{ color: 'var(--txt-mut)', fontSize: 12 }}>{value}</span>} />
                    <Tooltip contentStyle={tooltipStyle} />
                  </PieChart>
                </ResponsiveContainer>
              )}
            </ChartCard>

            {/* Not a chart when there is nothing to show: an empty bar chart reads as broken, an
                explicit all-clear message does not. */}
            <ChartCard title="Failed requests by cause" subtitle="Where errors are coming from, if any">
              {(stats.byErrorCode.length === 0) ? (
                <div style={{ padding: '30px 0', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>
                  No failed requests in this period.
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {stats.byErrorCode.map(b => {
                    const pct = stats.errorCount > 0 ? (b.count / stats.errorCount) * 100 : 0;
                    return (
                      <div key={b.key}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, marginBottom: 5 }}>
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

          <div style={{ ...card, padding: '14px 18px', display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 12, lineHeight: 1.5, color: 'var(--txt-dim)' }}>
            <Zap size={14} style={{ flexShrink: 0, marginTop: 2 }} />
            <span>
              Counts come from OneHR's own request logs, including retries, not from Mistral's billing.
              They can run slightly under Mistral's own numbers because rebuilding the knowledge index
              also calls Mistral directly. For actual cost and rate limits, use the{' '}
              <a href={MISTRAL_ADMIN_URL} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--info)' }}>Mistral Admin Console</a>.
            </span>
          </div>
        </>
      )}
    </div>
  );
}
