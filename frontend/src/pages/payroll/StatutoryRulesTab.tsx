import { useEffect, useMemo, useState } from 'react';
import { Plus, History, Pencil, CheckCircle2, AlertCircle, TriangleAlert } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type StatutoryRuleResponse, type LocationCountryMismatchResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtDate, EmptyRow, Modal, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead, StatTile, WithFaqPanel, type FaqItem,
} from './payrollShared';

const FAQ_ITEMS: FaqItem[] = [
  { q: 'What actually gets computed from a rule saved here?', a: 'Only PF, ESI and Professional Tax are read by payroll when building a payslip. A rule saved under any other name (e.g. a TDS or Gratuity slab) is stored but never applied - the create form warns you about this as you type.' },
  { q: 'How do I update a rate or ceiling (e.g. the PF wage ceiling)?', a: 'Click Revise on the PF/ESI/Professional Tax row and change the number that’s changing. It creates a new version effective from the date you set - the previous one is automatically closed out, not overwritten, so its full history stays visible.' },
  { q: 'Where do I see what changed and when?', a: 'Click History on any row to see every past version of that country + rule type, each with its own effective date range and values.' },
  { q: 'Why does a Location show up in the warning banner?', a: 'Its Country text doesn’t exactly match the Country saved on any rule below (e.g. "India" vs "IN") - or it has no Country set at all. Payroll matches Country as plain text, so even a one-character difference means PF/ESI/Professional Tax get silently skipped for every employee at that Location. Fix the Location’s Country (People > Organization > Organization Structure > Locations) to match exactly.' },
  { q: 'Why does a card say "Not Set Up"?', a: 'No rule exists yet for that type in any country. Until one is added, payroll silently skips that deduction on every payslip - it doesn’t fail or block the run.' },
];

// Only these three are actually read by StatutoryDeductionService.computeDeductions - TDS and
// Gratuity are explicitly out of scope (see that service's own Javadoc: TDS needs a full annual
// income-tax slab computation, Gratuity is an employer-side accrual, never a payslip line item).
// A rule saved under any other name is valid data but will never be computed by payroll - the
// note below the field says so; this list is deliberately not open to suggesting them.
const RULE_TYPE_SUGGESTIONS = ['PF', 'ESI', 'PROFESSIONAL_TAX'];
const RULE_TYPE_LABEL: Record<string, string> = { PF: 'Provident Fund', ESI: 'ESI', PROFESSIONAL_TAX: 'Professional Tax' };

// slabConfig is always a flat JSON object of plain numbers (see StatutoryDeductionService's own
// tests - e.g. {"employeeRate":0.12,"wageCeiling":25000}), never a fixed schema across rule
// types. Generic camelCase -> "SPACED UPPERCASE" labeling (not a per-field description) is the
// only honest way to show it structurally without guessing what a field means for a rule type
// this UI doesn't otherwise understand.
function humanizeKey(key: string): string {
  return key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toUpperCase();
}

/** Parses slabConfig into label/value pairs for display; null if it isn't a flat object (keeps
 * the raw-JSON fallback available for whatever doesn't fit this shape). */
function parseSlabConfig(slabConfig: string): [string, unknown][] | null {
  try {
    const parsed = JSON.parse(slabConfig);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return Object.entries(parsed);
  } catch { /* not valid JSON - fall through */ }
  return null;
}

function SlabConfigSummary({ slabConfig }: { slabConfig: string }) {
  const entries = parseSlabConfig(slabConfig);
  if (!entries || entries.length === 0) return <span style={{ color: 'var(--txt-dim)' }}>—</span>;
  return (
    <span style={{ color: 'var(--txt-mut)' }}>
      {entries.map(([k, v]) => `${humanizeKey(k)}: ${v}`).join(' · ')}
    </span>
  );
}

function SlabConfigGrid({ slabConfig }: { slabConfig: string }) {
  const entries = parseSlabConfig(slabConfig);
  if (!entries) {
    return <pre style={{ margin: 0, fontSize: 11, whiteSpace: 'pre-wrap', color: 'var(--txt)' }}>{slabConfig}</pre>;
  }
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 10 }}>
      {entries.map(([k, v]) => (
        <div key={k}>
          <div style={labelStyle}>{humanizeKey(k)}</div>
          <div style={{ fontSize: 14, color: 'var(--txt)' }}>{String(v)}</div>
        </div>
      ))}
    </div>
  );
}

/** Percentage fields are stored in slabConfig as a plain decimal (0.12), same as
 * StatutoryDeductionService reads them - this is the only place that fraction/percent
 * conversion happens, so an HR admin only ever types "12", never "0.12". */
function pctFromSlab(slab: Record<string, unknown>, key: string): string {
  const v = slab[key];
  return typeof v === 'number' ? String(v * 100) : '';
}
function numFromSlab(slab: Record<string, unknown>, key: string): string {
  const v = slab[key];
  return typeof v === 'number' ? String(v) : '';
}

function ReviseRuleModal({ initial, onClose, onCreated }: {
  initial?: StatutoryRuleResponse; onClose: () => void; onCreated: (r: StatutoryRuleResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [country, setCountry] = useState(initial?.country ?? '');
  const [ruleType, setRuleType] = useState(initial?.ruleType ?? '');

  const initialSlab: Record<string, unknown> = (() => {
    if (!initial) return {};
    try { const p = JSON.parse(initial.slabConfig); return p && typeof p === 'object' ? p : {}; } catch { return {}; }
  })();

  // Friendly fields for the three rule types payroll actually computes - HR never sees or types
  // raw JSON for these. Rates are typed as plain percentages (e.g. "12"), converted to a decimal
  // only when building the request.
  const [employeeRatePct, setEmployeeRatePct] = useState(() => pctFromSlab(initialSlab, 'employeeRate'));
  const [employerRatePct, setEmployerRatePct] = useState(() => pctFromSlab(initialSlab, 'employerRate'));
  const [wageCeiling, setWageCeiling] = useState(() => numFromSlab(initialSlab, 'wageCeiling'));
  const [ptAmount, setPtAmount] = useState(() => numFromSlab(initialSlab, 'amount'));

  // Fallback for any rule type outside the known three - same raw-JSON editor as before.
  const [rawSlabConfig, setRawSlabConfig] = useState(initial?.slabConfig ?? '{\n  \n}');
  const [jsonError, setJsonError] = useState<string | null>(null);

  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [submitting, setSubmitting] = useState(false);

  function checkJson(value: string) {
    setRawSlabConfig(value);
    try { JSON.parse(value); setJsonError(null); } catch { setJsonError('Not valid JSON'); }
  }

  const isKnownType = RULE_TYPE_SUGGESTIONS.includes(ruleType);

  function buildSlabConfig(): string | null {
    if (ruleType === 'PF') {
      if (!employeeRatePct.trim() || !wageCeiling.trim()) return null;
      const obj: Record<string, number> = { employeeRate: Number(employeeRatePct) / 100, wageCeiling: Number(wageCeiling) };
      if (employerRatePct.trim()) obj.employerRate = Number(employerRatePct) / 100;
      return JSON.stringify(obj);
    }
    if (ruleType === 'ESI') {
      if (!employeeRatePct.trim() || !wageCeiling.trim()) return null;
      return JSON.stringify({ employeeRate: Number(employeeRatePct) / 100, wageCeiling: Number(wageCeiling) });
    }
    if (ruleType === 'PROFESSIONAL_TAX') {
      if (!ptAmount.trim()) return null;
      return JSON.stringify({ amount: Number(ptAmount) });
    }
    if (jsonError || !rawSlabConfig.trim()) return null;
    return rawSlabConfig.trim();
  }

  const slabConfig = buildSlabConfig();
  const valid = !!country.trim() && !!ruleType.trim() && !!effectiveFrom && !!slabConfig;

  async function submit() {
    if (!valid || !slabConfig) return;
    setSubmitting(true);
    try {
      const rule = await payrollApi.createOrReviseStatutoryRule({ country: country.trim(), ruleType: ruleType.trim(), slabConfig, effectiveFrom }, token);
      onCreated(rule);
      onClose();
      showToast('success', 'Statutory rule saved');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save rule');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title={initial ? `Revise ${RULE_TYPE_LABEL[initial.ruleType] ?? initial.ruleType} (${initial.country})` : 'Add / Revise Statutory Rule'}
      subtitle="Creates a new version effective from the date below. The existing current rule for the same country + rule type is automatically closed out, not overwritten - its history is preserved."
      onClose={onClose}
    >
      <FormRow>
        <label style={labelStyle}>Country</label>
        <input value={country} onChange={e => setCountry(e.target.value)} style={inputStyle} placeholder="e.g. India" disabled={!!initial} />
        <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 4 }}>
          Must exactly match the Country text saved on each employee's Location - e.g. "India", "USA" (case-sensitive, so "india" or "IN" won't match "India").
        </div>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Rule Type</label>
        <input list="rule-type-suggestions" value={ruleType} onChange={e => setRuleType(e.target.value.toUpperCase())} style={inputStyle} placeholder="e.g. PF" disabled={!!initial} />
        <datalist id="rule-type-suggestions">
          {RULE_TYPE_SUGGESTIONS.map(t => <option key={t} value={t} />)}
        </datalist>
        {ruleType && !isKnownType && (
          <div style={{ fontSize: 12, color: 'var(--warn)', marginTop: 4 }}>
            Payroll only computes PF, ESI and Professional Tax today - a rule saved under "{ruleType}" will be stored but never applied to a payslip.
          </div>
        )}
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Effective From</label>
        <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} style={inputStyle} />
      </FormRow>

      {ruleType === 'PF' && (
        <>
          <FormRow>
            <label style={labelStyle}>Employee PF Rate (%)</label>
            <input type="number" step="0.01" value={employeeRatePct} onChange={e => setEmployeeRatePct(e.target.value)} style={inputStyle} placeholder="e.g. 12" />
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Employer PF Rate (%) <span style={{ fontWeight: 400, textTransform: 'none' }}>(optional - prints "Employer PF" on the payslip)</span></label>
            <input type="number" step="0.01" value={employerRatePct} onChange={e => setEmployerRatePct(e.target.value)} style={inputStyle} placeholder="e.g. 12" />
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Wage Ceiling (₹ per month)</label>
            <input type="number" value={wageCeiling} onChange={e => setWageCeiling(e.target.value)} style={inputStyle} placeholder="e.g. 25000" />
          </FormRow>
        </>
      )}
      {ruleType === 'ESI' && (
        <>
          <FormRow>
            <label style={labelStyle}>Employee ESI Rate (%)</label>
            <input type="number" step="0.01" value={employeeRatePct} onChange={e => setEmployeeRatePct(e.target.value)} style={inputStyle} placeholder="e.g. 0.75" />
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Eligibility Ceiling (₹ per month gross)</label>
            <input type="number" value={wageCeiling} onChange={e => setWageCeiling(e.target.value)} style={inputStyle} placeholder="e.g. 21000" />
            <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 4 }}>All-or-nothing: anyone over this gross pay gets no ESI deduction at all this cycle.</div>
          </FormRow>
        </>
      )}
      {ruleType === 'PROFESSIONAL_TAX' && (
        <FormRow>
          <label style={labelStyle}>Amount (₹ per month)</label>
          <input type="number" value={ptAmount} onChange={e => setPtAmount(e.target.value)} style={inputStyle} placeholder="e.g. 200" />
        </FormRow>
      )}
      {ruleType && !isKnownType && (
        <FormRow>
          <label style={labelStyle}>Slab Config (JSON)</label>
          <textarea
            value={rawSlabConfig}
            onChange={e => checkJson(e.target.value)}
            style={{ ...inputStyle, minHeight: 140, resize: 'vertical', fontFamily: 'monospace', fontSize: 12 }}
          />
          {jsonError && <div style={{ color: 'var(--risk)', fontSize: 12, marginTop: 4 }}>{jsonError}</div>}
        </FormRow>
      )}

      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : 'Save'}</BtnPrimary>
      </div>
    </Modal>
  );
}

function HistoryModal({ country, ruleType, otherCountryRules, onClose }: {
  country: string; ruleType: string; otherCountryRules: StatutoryRuleResponse[]; onClose: () => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [history, setHistory] = useState<StatutoryRuleResponse[]>([]);

  useEffect(() => {
    payrollApi.getStatutoryRuleHistory(country, ruleType, token).then(setHistory).catch(() => showToast('error', 'Failed to load history'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [country, ruleType, token]);

  return (
    <Modal title={`${ruleType} (${country}) - History`} onClose={onClose}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {history.map(r => (
          <div key={r.id} style={{ border: '1px solid var(--line)', borderRadius: 8, padding: 12 }}>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 10 }}>
              {fmtDate(r.effectiveFrom)} - {r.effectiveTo ? fmtDate(r.effectiveTo) : 'Current'}
            </div>
            <SlabConfigGrid slabConfig={r.slabConfig} />
          </div>
        ))}
        {history.length === 0 && <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No history found.</div>}
      </div>

      {otherCountryRules.length > 0 && (
        <div style={{ marginTop: 18, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
          <div style={{ ...labelStyle, marginBottom: 10 }}>Previously configured under a different Country</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {otherCountryRules.map(r => (
              <div key={r.id} style={{ border: '1px solid var(--line)', borderRadius: 8, padding: 12, opacity: 0.75 }}>
                <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 10 }}>
                  Country "{r.country}" - effective {fmtDate(r.effectiveFrom)} - no longer matches any Location, kept for reference only
                </div>
                <SlabConfigGrid slabConfig={r.slabConfig} />
              </div>
            ))}
          </div>
        </div>
      )}
    </Modal>
  );
}

export function StatutoryRulesTab() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [rules, setRules] = useState<StatutoryRuleResponse[]>([]);
  const [mismatches, setMismatches] = useState<LocationCountryMismatchResponse[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [revising, setRevising] = useState<StatutoryRuleResponse | null>(null);
  const [historyFor, setHistoryFor] = useState<{ country: string; ruleType: string } | null>(null);

  function reload() {
    payrollApi.listStatutoryRules(token).then(setRules).catch(() => showToast('error', 'Failed to load statutory rules'));
    payrollApi.getLocationCountryMismatches(token).then(setMismatches).catch(() => {});
  }

  useEffect(reload, [token]);

  // One row per rule type, ever - whichever was created most recently. Rules only ever get
  // retired by creating a new one under a different Country text (e.g. "IN" -> "India"), never
  // deleted, so a superseded row would otherwise sit in the main table forever looking like a
  // second live rule. It's surfaced instead inside the live row's own History modal.
  const liveRules = useMemo(() => {
    const byType = new Map<string, StatutoryRuleResponse>();
    for (const r of rules) {
      const current = byType.get(r.ruleType);
      if (!current || new Date(r.createdAt).getTime() > new Date(current.createdAt).getTime()) {
        byType.set(r.ruleType, r);
      }
    }
    return [...byType.values()];
  }, [rules]);

  return (
    <WithFaqPanel items={FAQ_ITEMS} title="Statutory Rules Help">
    <div>
      {mismatches.length > 0 && (
        <div style={{
          display: 'flex', gap: 12, alignItems: 'flex-start', padding: '14px 18px', borderRadius: 12, marginBottom: 20,
          background: 'color-mix(in srgb, var(--warn) 10%, var(--panel))', border: '1px solid color-mix(in srgb, var(--warn) 35%, transparent)',
        }}>
          <TriangleAlert size={18} style={{ color: 'var(--warn)', flexShrink: 0, marginTop: 1 }} />
          <div style={{ fontSize: 13, color: 'var(--txt)' }}>
            <strong>PF/ESI/Professional Tax will be silently skipped for {mismatches.reduce((n, m) => n + m.employeeCount, 0)} employee(s)</strong> - their Location's Country doesn't exactly match any rule below:
            <ul style={{ margin: '8px 0 0', paddingLeft: 18 }}>
              {mismatches.map(m => (
                <li key={m.locationId}>
                  <strong>{m.locationName}</strong> - Country is {m.country ? `"${m.country}"` : 'not set'} ({m.employeeCount} employee{m.employeeCount === 1 ? '' : 's'})
                </li>
              ))}
            </ul>
            <div style={{ marginTop: 8, color: 'var(--txt-mut)' }}>
              Fix each Location's Country under People &gt; Organization &gt; Organization Structure &gt; Locations so it matches a rule's Country exactly (case-sensitive).
            </div>
          </div>
        </div>
      )}

      {/* At-a-glance: is each statutory deduction actually configured, so a rule saved under the
          wrong country/name (or just never created) is obvious without reading the table below. */}
      <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
        {RULE_TYPE_SUGGESTIONS.map(type => {
          const rule = liveRules.find(r => r.ruleType === type);
          return (
            <StatTile
              key={type}
              tone={rule ? 'var(--ok)' : 'var(--warn)'}
              icon={rule ? <CheckCircle2 size={20} /> : <AlertCircle size={20} />}
              label={RULE_TYPE_LABEL[type] ?? type}
              value={rule ? 'Configured' : 'Not Set Up'}
              hint={rule ? `${rule.country} · effective ${fmtDate(rule.effectiveFrom)}` : 'No rule saved for this yet - payslips will skip this deduction'}
            />
          );
        })}
      </div>

      <SectionHead
        title="Statutory Rules"
        action={<BtnPrimary onClick={() => setShowCreate(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add / Revise Rule</BtnPrimary>}
      />
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
        HR-editable config for statutory slabs (PF, ESI, Professional Tax, TDS, etc.) - version-dated, never hardcoded in application code.
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Country', 'Rule Type', 'Current Config', 'Effective From', 'Created', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {liveRules.map(r => (
              <tr key={r.id}>
                <td style={tdStyle}>{r.country}</td>
                <td style={tdStyle}>{r.ruleType}</td>
                <td style={tdStyle}><SlabConfigSummary slabConfig={r.slabConfig} /></td>
                <td style={tdStyle}>{fmtDate(r.effectiveFrom)}</td>
                <td style={tdStyle}>{fmtDate(r.createdAt)}</td>
                <td style={tdStyle}>
                  <ActionDock items={[
                    { label: 'Revise', icon: Pencil, onClick: () => setRevising(r) },
                    { label: 'History', icon: History, onClick: () => setHistoryFor({ country: r.country, ruleType: r.ruleType }) },
                  ]} />
                </td>
              </tr>
            ))}
            {liveRules.length === 0 && <EmptyRow cols={6} msg="No statutory rules configured yet." />}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <ReviseRuleModal onClose={() => setShowCreate(false)} onCreated={() => reload()} />
      )}
      {revising && (
        <ReviseRuleModal initial={revising} onClose={() => setRevising(null)} onCreated={() => reload()} />
      )}
      {historyFor && (
        <HistoryModal
          country={historyFor.country} ruleType={historyFor.ruleType}
          otherCountryRules={rules.filter(r => r.ruleType === historyFor.ruleType && r.country !== historyFor.country)}
          onClose={() => setHistoryFor(null)}
        />
      )}
    </div>
    </WithFaqPanel>
  );
}
