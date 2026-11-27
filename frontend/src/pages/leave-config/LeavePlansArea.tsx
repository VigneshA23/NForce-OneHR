/**
 * LV-01 Leave Plans (Organization Masters > Leave > Leave Plans). A plan bundles leave types with their
 * own rules — days, crediting, carry-forward and who each type applies to — and is assigned to employees
 * on the Assignment tab. Rule changes are saved as a version from a chosen date; earlier versions stay
 * as history. One plan is the organization default, used for anyone not assigned another plan.
 */
import { useCallback, useEffect, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { KebabMenu, type KebabItem } from '../../components/KebabMenu';
import { useToast } from '../../context/ToastContext';
import {
  leaveConfigApi, type LeavePlanDetail, type LeavePlanSummary, type LeavePlanVersionDetail, type LeaveType,
} from '../../api/leave';
import { AppliesToEditor, appliesToSummary } from './AppliesTo';
import {
  Badge, ConfirmDialog, Dialog, DialogActions, FormError, Table, days, errMsg, fmtDate, hintS, inputS, labelS,
  primaryBtn, secondaryBtn, tdS, type Confirm,
} from './leaveConfigUi';
import { firstOfNextMonth, formsFor, isoDate, toPayload, type RuleForm } from './planRules';
import type { Lookups } from './LeaveConfigSection';

// ── Rule rows ──────────────────────────────────────────────────────────────────

function RuleRow({ type, form, onChange, lookups, token, readOnly }: {
  type: LeaveType; form: RuleForm; onChange(next: RuleForm): void; lookups: Lookups; token: string; readOnly: boolean;
}) {
  const [open, setOpen] = useState(false);
  const paid = type.classification === 'PAID';
  const annual = paid && type.entitlementBasis !== 'PER_OCCURRENCE';
  const set = (patch: Partial<RuleForm>) => onChange({ ...form, ...patch });
  const small: React.CSSProperties = { ...inputS, width: 96 };
  return (
    <div data-testid={`plan-rule-${type.code}`} style={{ border: '1px solid var(--line)', borderRadius: 8, marginBottom: 8, background: form.included ? 'var(--panel)' : 'transparent' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 12px', flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 200, flex: '1 1 200px', cursor: readOnly ? 'default' : 'pointer' }}>
          <input type="checkbox" checked={form.included} disabled={readOnly} onChange={e => set({ included: e.target.checked })}
            aria-label={`Include ${type.name}`} />
          <span style={{ fontSize: 13, fontWeight: 600, color: form.included ? 'var(--txt)' : 'var(--txt-dim)' }}>{type.name}</span>
          <Badge tone={paid ? 'ok' : 'warn'}>{paid ? (annual ? 'Paid' : 'Per occurrence') : 'Unpaid'}</Badge>
          {!type.active && <Badge tone="dim">Inactive</Badge>}
        </label>
        {form.included && (
          <>
            {paid ? (
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-mut)' }}>
                <input type="number" min={0} max={999} step={0.5} style={small} value={form.days} disabled={readOnly}
                  aria-label={`${type.name} days`} onChange={e => set({ days: e.target.value })} />
                {annual ? 'days / year' : 'days / occurrence'}
              </label>
            ) : <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Unlimited</span>}
            {annual && (
              <select style={{ ...inputS, width: 150 }} value={form.creditFrequency} disabled={readOnly} aria-label={`${type.name} crediting`}
                onChange={e => set({ creditFrequency: e.target.value as RuleForm['creditFrequency'] })}>
                <option value="MONTHLY">Monthly (1st)</option>
                <option value="YEARLY">All at once (1 Jan)</option>
              </select>
            )}
            <button type="button" onClick={() => setOpen(!open)} style={{ ...secondaryBtn, display: 'inline-flex', alignItems: 'center', gap: 4 }}
              aria-expanded={open}>
              {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />} More
            </button>
          </>
        )}
      </div>
      {form.included && !open && (
        <div style={{ padding: '0 12px 10px 36px', fontSize: 11.5, color: 'var(--txt-dim)' }}>
          Applies to: {appliesToSummary(form, lookups.departments, lookups.locations)}
          {annual && (form.carryForwardEnabled
            ? ` · carries forward ${form.carryForwardMaxDays ? `up to ${form.carryForwardMaxDays} day(s)` : 'all unused days'}${form.carryForwardExpiryMonths ? `, expiring after ${form.carryForwardExpiryMonths} month(s)` : ''}`
            : ' · unused days lapse at year end')}
        </div>
      )}
      {form.included && open && (
        <div style={{ padding: '4px 12px 4px 36px' }}>
          {annual && (
            <fieldset style={{ border: '1px solid var(--line)', borderRadius: 8, padding: '10px 14px', margin: '0 0 12px' }}>
              <legend style={{ ...labelS, padding: '0 6px', marginBottom: 0 }}>Carry-forward</legend>
              <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'flex-end' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13 }}>
                  <input type="checkbox" checked={form.carryForwardEnabled} disabled={readOnly}
                    onChange={e => set({ carryForwardEnabled: e.target.checked })} />
                  Carry unused days into next year
                </label>
                <div>
                  <label style={labelS}>Maximum days</label>
                  <input type="number" min={0} max={999} step={0.5} style={small} value={form.carryForwardMaxDays} placeholder="No cap"
                    disabled={readOnly || !form.carryForwardEnabled} aria-label={`${type.name} maximum carry-forward days`}
                    onChange={e => set({ carryForwardMaxDays: e.target.value })} />
                </div>
                <div>
                  <label style={labelS}>Expires after (months)</label>
                  <input type="number" min={1} max={24} style={small} value={form.carryForwardExpiryMonths} placeholder="Never"
                    disabled={readOnly || !form.carryForwardEnabled} aria-label={`${type.name} carry-forward expiry`}
                    onChange={e => set({ carryForwardExpiryMonths: e.target.value })} />
                </div>
                <div>
                  <label style={labelS}>Use first</label>
                  <select style={{ ...inputS, width: 170 }} value={form.carryForwardPriority} disabled={readOnly || !form.carryForwardEnabled}
                    onChange={e => set({ carryForwardPriority: e.target.value as RuleForm['carryForwardPriority'] })}>
                    <option value="CARRY_FORWARD_FIRST">Carried-forward first</option>
                    <option value="CURRENT_FIRST">Current year first</option>
                  </select>
                </div>
              </div>
            </fieldset>
          )}
          <AppliesToEditor value={form.eligibility} onChange={eligibility => set({ eligibility })} departments={lookups.departments}
            locations={lookups.locations} employees={lookups.employees} token={token} disabled={readOnly} />
        </div>
      )}
    </div>
  );
}

// ── Editor ─────────────────────────────────────────────────────────────────────

function PlanEditor({ planId, types, token, canConfigure, lookups, onClose, onSaved }: {
  planId: string | null; types: LeaveType[]; token: string; canConfigure: boolean; lookups: Lookups;
  onClose(): void; onSaved(detail: LeavePlanDetail): void;
}) {
  const creating = planId === null;
  const [detail, setDetail] = useState<LeavePlanDetail | null>(null);
  const [version, setVersion] = useState<LeavePlanVersionDetail | null>(null);
  const [forms, setForms] = useState<RuleForm[]>(() => creating ? formsFor(types, []).map(f => ({
    ...f, included: types.find(t => t.id === f.leaveTypeId)?.systemKey != null,
  })) : []);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(firstOfNextMonth(new Date()));
  const [recredit, setRecredit] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const showVersion = useCallback((v: LeavePlanVersionDetail) => {
    setVersion(v);
    setForms(formsFor(types, v.rules));
    if (v.version.status === 'SCHEDULED') setEffectiveFrom(v.version.effectiveFrom);
  }, [types]);

  useEffect(() => {
    if (creating || !planId) return;
    Promise.all([leaveConfigApi.plan(planId, token), leaveConfigApi.currentPlanVersion(planId, token)])
      .then(([d, v]) => { setDetail(d); setName(d.plan.name); setDescription(d.plan.description ?? ''); showVersion(v); })
      .catch(e => setError(errMsg(e)));
  }, [creating, planId, token, showVersion]);

  const readOnly = !canConfigure;
  const thisYear = new Date().getFullYear();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) { setError('Name is required'); return; }
    const { rules, error: invalid } = toPayload(forms, types);
    if (invalid) { setError(invalid); return; }
    if (!creating && effectiveFrom < `${thisYear}-01-01`) { setError(`Changes can't take effect before 1 Jan ${thisYear}: earlier leave years are closed.`); return; }
    setBusy(true);
    setError('');
    try {
      let saved: LeavePlanDetail;
      if (creating) {
        saved = await leaveConfigApi.createPlan({ name: name.trim(), description: description.trim() || undefined, rules }, token);
      } else {
        if (detail && (name.trim() !== detail.plan.name || (description.trim() || null) !== (detail.plan.description ?? null))) {
          await leaveConfigApi.updatePlan(planId!, { name: name.trim(), description: description.trim() || undefined }, token);
        }
        saved = await leaveConfigApi.savePlanVersion(planId!, { effectiveFrom, rules, recreditCredited: recredit }, token);
      }
      onSaved(saved);
      onClose();
    } catch (err) {
      setError(errMsg(err));
      setBusy(false);
    }
  }

  const typeById = new Map(types.map(t => [t.id, t]));
  return (
    <Dialog title={creating ? 'Add Leave Plan' : `Leave Plan — ${detail?.plan.name ?? ''}`} kind={creating ? 'add' : 'edit'} width={880} onClose={onClose}>
      <form onSubmit={submit}>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 14 }}>
          <div style={{ flex: '1 1 240px' }}>
            <label style={labelS} htmlFor="lp-name">Plan name *</label>
            <input id="lp-name" style={inputS} value={name} maxLength={100} disabled={readOnly} onChange={e => setName(e.target.value)}
              placeholder="e.g. Full-time India, Interns" autoFocus={creating} />
          </div>
          <div style={{ flex: '2 1 320px' }}>
            <label style={labelS} htmlFor="lp-desc">Description</label>
            <input id="lp-desc" style={inputS} value={description} maxLength={500} disabled={readOnly} onChange={e => setDescription(e.target.value)} />
          </div>
          <div style={{ width: 150 }}>
            <label style={labelS}>Leave year</label>
            <input style={{ ...inputS, opacity: 0.7 }} value="January – December" disabled
              title="Other leave-year start months are not available yet" />
          </div>
        </div>

        {!creating && detail && (
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Versions</label>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {detail.versions.map(v => (
                <button key={v.id} type="button" onClick={() => leaveConfigApi.planVersion(v.id, token).then(showVersion).catch(e2 => setError(errMsg(e2)))}
                  style={{ ...secondaryBtn, borderColor: version?.version.id === v.id ? 'var(--brand)' : 'var(--line2)', color: version?.version.id === v.id ? 'var(--brand)' : 'var(--txt-mut)' }}>
                  v{v.version} · {fmtDate(v.effectiveFrom)}{v.effectiveTo ? ` – ${fmtDate(v.effectiveTo)}` : ' onwards'}
                  {v.status !== 'PAST' && <span style={{ marginLeft: 6 }}><Badge tone={v.status === 'CURRENT' ? 'ok' : 'info'}>{v.status === 'CURRENT' ? 'In effect' : 'Scheduled'}</Badge></span>}
                </button>
              ))}
            </div>
            <p style={{ ...hintS, margin: '6px 0 0', fontSize: 11.5 }}>
              Showing the rules of v{version?.version.version}. Saving creates a new version from the date below (or replaces the
              version that starts on that date); versions before and after it are kept.
            </p>
          </div>
        )}

        <label style={labelS}>Leave types in this plan</label>
        <p style={{ ...hintS, fontSize: 11.5 }}>
          Only the types ticked here are available to employees on this plan. Paid leave needs a number of days.
        </p>
        <div style={{ maxHeight: '46vh', overflowY: 'auto', marginBottom: 14 }}>
          {forms.map((f, i) => {
            const t = typeById.get(f.leaveTypeId);
            return t ? (
              <RuleRow key={f.leaveTypeId} type={t} form={f} lookups={lookups} token={token} readOnly={readOnly}
                onChange={next => setForms(forms.map((x, j) => (j === i ? next : x)))} />
            ) : null;
          })}
        </div>

        {!creating && canConfigure && (
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 14 }}>
            <div style={{ width: 170 }}>
              <label style={labelS} htmlFor="lp-from">Changes take effect from *</label>
              <input id="lp-from" type="date" style={inputS} value={effectiveFrom} min={`${thisYear}-01-01`} onChange={e => setEffectiveFrom(e.target.value)} />
            </div>
            <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 12.5, maxWidth: 520, color: 'var(--txt-mut)' }}>
              <input type="checkbox" checked={recredit} onChange={e => setRecredit(e.target.checked)} style={{ marginTop: 3 }} />
              <span>Re-credit months already credited from this date (otherwise only months not credited yet follow the new days).</span>
            </label>
          </div>
        )}
        {creating && (
          <p style={{ ...hintS, fontSize: 11.5 }}>
            The plan applies from {fmtDate(isoDate(new Date(new Date().getFullYear(), 0, 1)))} for anyone assigned to it. Assign
            employees on the Assignment tab.
          </p>
        )}
        <FormError error={error} />
        {canConfigure
          ? <DialogActions busy={busy} label={creating ? 'Create Plan' : 'Save'} onCancel={onClose} />
          : <div style={{ display: 'flex', justifyContent: 'flex-end' }}><button type="button" style={secondaryBtn} onClick={onClose}>Close</button></div>}
      </form>
    </Dialog>
  );
}

// ── Clone / rename ─────────────────────────────────────────────────────────────

function CloneDialog({ plan, token, onClose, onSaved }: { plan: LeavePlanSummary; token: string; onClose(): void; onSaved(d: LeavePlanDetail): void }) {
  const [name, setName] = useState(`${plan.name} (copy)`);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) { setError('Name is required'); return; }
    setBusy(true);
    try { onSaved(await leaveConfigApi.clonePlan(plan.id, name.trim(), token)); onClose(); } catch (err) { setError(errMsg(err)); setBusy(false); }
  }
  return (
    <Dialog title={`Clone "${plan.name}"`} kind="add" width={440} onClose={onClose}>
      <form onSubmit={submit}>
        <p style={hintS}>The new plan starts with the rules in effect today. Nobody is assigned to it until you assign them.</p>
        <label style={labelS} htmlFor="lp-clone">New plan name *</label>
        <input id="lp-clone" style={{ ...inputS, marginBottom: 16 }} value={name} maxLength={100} onChange={e => setName(e.target.value)} autoFocus />
        <FormError error={error} />
        <DialogActions busy={busy} label="Clone" onCancel={onClose} />
      </form>
    </Dialog>
  );
}

// ── Area ───────────────────────────────────────────────────────────────────────

export default function LeavePlansArea({ types, token, canConfigure, lookups }: {
  types: LeaveType[]; token: string; canConfigure: boolean; lookups: Lookups;
}) {
  const { showToast } = useToast();
  const [plans, setPlans] = useState<LeavePlanSummary[] | null>(null);
  const [error, setError] = useState('');
  const [editor, setEditor] = useState<{ planId: string | null; key: number } | null>(null);
  const [cloning, setCloning] = useState<LeavePlanSummary | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  const reload = useCallback(async () => {
    try { setPlans(await leaveConfigApi.plans(token)); setError(''); } catch (e) { setError(errMsg(e)); }
  }, [token]);
  useEffect(() => { void reload(); }, [reload]);

  function items(p: LeavePlanSummary): KebabItem[] {
    const open = { label: canConfigure ? 'Edit rules' : 'View rules', onClick: () => setEditor({ planId: p.id, key: Date.now() }) };
    if (!canConfigure) return [open];
    const out: KebabItem[] = [open, { label: 'Clone', onClick: () => setCloning(p) }];
    if (!p.orgDefault && p.status === 'ACTIVE') {
      out.push({
        label: 'Make organization default', onClick: () => setConfirm({
          title: `Make "${p.name}" the default`,
          body: `Everyone with no plan assigned directly or by a rule, including new joiners, will be on "${p.name}" from today. Months already credited this year are re-credited under it.`,
          confirmLabel: 'Make default',
          onConfirm: async () => { await leaveConfigApi.setDefaultPlan(p.id, token); showToast('success', `"${p.name}" is now the default plan`); await reload(); },
        }),
      });
    }
    if (!p.orgDefault) {
      out.push({
        label: p.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate', dividerBefore: true, onClick: () => setConfirm({
          title: p.status === 'ACTIVE' ? `Deactivate "${p.name}"` : `Reactivate "${p.name}"`,
          body: p.status === 'ACTIVE'
            ? `The ${p.employeeCount} employee(s) on "${p.name}" move to their rule's plan or the organization default from today, and months already credited are re-credited.`
            : `Employees assigned to "${p.name}" (directly or by a rule) are on it again from today.`,
          confirmLabel: p.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate',
          danger: p.status === 'ACTIVE',
          onConfirm: async () => { await leaveConfigApi.setPlanActive(p.id, p.status !== 'ACTIVE', token); showToast('success', 'Plan updated'); await reload(); },
        }),
      });
      out.push({
        label: 'Delete', danger: true, onClick: () => setConfirm({
          title: `Delete "${p.name}"`,
          body: `"${p.name}" will be permanently deleted. Only a plan that was never assigned to anyone can be deleted; otherwise deactivate it.`,
          confirmLabel: 'Delete', danger: true,
          onConfirm: async () => { await leaveConfigApi.deletePlan(p.id, token); showToast('success', `"${p.name}" deleted`); await reload(); },
        }),
      });
    }
    return out;
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
        <p style={{ ...hintS, margin: 0, maxWidth: 680 }}>
          A leave plan bundles leave types with their own days, crediting, carry-forward and "applies to" rules, so different
          groups get different entitlements. Every employee is on exactly one plan: the one assigned to them directly, else the
          first matching rule, else the organization default. Rule changes take effect from a date you choose.
        </p>
        {canConfigure && <button style={primaryBtn()} onClick={() => setEditor({ planId: null, key: Date.now() })}>Add Leave Plan</button>}
      </div>
      <FormError error={error} />
      {plans === null ? <p style={hintS}>Loading…</p> : (
        <Table head={['Plan', 'Leave types', 'Employees today', 'Rules in effect', 'Status', '']} empty={plans.length === 0 && 'No leave plans yet.'}>
          {plans.map(p => (
            <tr key={p.id}>
              <td style={{ ...tdS, color: 'var(--txt)' }}>
                <button type="button" onClick={() => setEditor({ planId: p.id, key: Date.now() })}
                  style={{ background: 'none', border: 'none', padding: 0, cursor: 'pointer', color: 'var(--txt)', fontWeight: 600, fontSize: 13 }}>
                  {p.name}
                </button>
                {p.orgDefault && <span style={{ marginLeft: 8 }}><Badge tone="info">Organization default</Badge></span>}
                {p.description && <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2 }}>{p.description}</div>}
              </td>
              <td style={tdS}>{p.leaveTypeCount}</td>
              <td style={tdS}>{days(p.employeeCount)}</td>
              <td style={tdS}>{p.currentVersion != null ? `v${p.currentVersion} since ${fmtDate(p.currentVersionFrom)}` : '–'}</td>
              <td style={tdS}><Badge tone={p.status === 'ACTIVE' ? 'ok' : 'dim'}>{p.status === 'ACTIVE' ? 'Active' : 'Inactive'}</Badge></td>
              <td style={{ ...tdS, textAlign: 'right', width: 52 }}><KebabMenu items={items(p)} /></td>
            </tr>
          ))}
        </Table>
      )}
      {editor && (
        <PlanEditor key={editor.key} planId={editor.planId} types={types} token={token} canConfigure={canConfigure} lookups={lookups}
          onClose={() => setEditor(null)}
          onSaved={d => { showToast('success', editor.planId ? `"${d.plan.name}" saved` : `"${d.plan.name}" created`); void reload(); }} />
      )}
      {cloning && (
        <CloneDialog plan={cloning} token={token} onClose={() => setCloning(null)}
          onSaved={d => { showToast('success', `"${d.plan.name}" created`); void reload(); }} />
      )}
      {confirm && <ConfirmDialog {...confirm} onClose={() => setConfirm(null)} />}
    </div>
  );
}
