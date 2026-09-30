import { useEffect, useState } from 'react';
import { Plus, Check, X, ShieldAlert } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  workflowRulesApi, type ApprovalRule, type ApprovalRuleMetadata, type ApprovalRulePreview,
} from '../api/workflowRules';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', background: 'var(--raised)' };
const tdS: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { width: '100%', padding: '9px 10px', borderRadius: 6, border: '1px solid var(--line2)', background: 'var(--shell)', color: 'var(--txt)', fontSize: 13, fontFamily: 'inherit', boxSizing: 'border-box' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.04em' };
const btnStyle: React.CSSProperties = { padding: '8px 16px', background: 'var(--raised2)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 13, cursor: 'pointer' };
const btnPrimaryStyle: React.CSSProperties = { ...btnStyle, background: 'var(--brand)', borderColor: 'var(--brand)', color: '#fff', fontWeight: 600 };
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500, padding: '40px 16px' };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '100%', maxWidth: 560, maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };

// ── Pure, testable logic (mirrors ApprovalRuleEvaluationService — kept in sync deliberately;
//    the backend is always the authoritative source of truth, this is only for instant
//    client-side feedback in the Preview panel before a round-trip completes) ──────────────

export function evaluateConditionLocally(sampleValue: number, operator: string, conditionValue: number): boolean {
  switch (operator) {
    case '>': return sampleValue > conditionValue;
    case '>=': return sampleValue >= conditionValue;
    case '<': return sampleValue < conditionValue;
    case '<=': return sampleValue <= conditionValue;
    case '=': return sampleValue === conditionValue;
    default: return false;
  }
}

export interface RuleFormValues {
  ruleName: string;
  requestType: string;
  conditionField: string;
  operator: string;
  conditionValue: string;
  secondApprovalRoles: string[]; // roles beyond MANAGER, e.g. ['HR_ADMIN']
}

export const EMPTY_FORM: RuleFormValues = {
  ruleName: '', requestType: 'EXPENSE', conditionField: 'AMOUNT', operator: '>', conditionValue: '',
  secondApprovalRoles: ['HR_ADMIN'],
};

/** MANAGER is always stage one (structural, not configurable — see backend's own validation)
 *  followed by whichever roles the admin picked for the "second approval" branch. */
export function stagesFromForm(values: RuleFormValues): string[] {
  return ['MANAGER', ...values.secondApprovalRoles.filter(r => r !== 'MANAGER')];
}

/** Client-side validation mirroring the backend's ApprovalRuleService#validateCore — backend
 *  remains authoritative (this only gives faster feedback before the round-trip). */
export function validateRuleForm(values: RuleFormValues, metadata: ApprovalRuleMetadata | null): string[] {
  const errors: string[] = [];
  if (!values.ruleName.trim()) errors.push('Rule name is required');
  if (!values.requestType) {
    errors.push('Request type is required');
  } else if (metadata && !metadata.requestTypes.includes(values.requestType)) {
    errors.push(`Unsupported request type: ${values.requestType}`);
  }
  if (!values.conditionField) {
    errors.push('Condition field is required');
  } else if (metadata) {
    const allowed = metadata.conditionFieldsByRequestType[values.requestType] ?? [];
    if (!allowed.includes(values.conditionField)) {
      errors.push(`Invalid condition field '${values.conditionField}' for request type ${values.requestType}`);
    }
  }
  if (!values.operator) {
    errors.push('Operator is required');
  } else if (metadata && !metadata.operators.includes(values.operator)) {
    errors.push(`Invalid operator: ${values.operator}`);
  }
  const trimmedValue = values.conditionValue.trim();
  if (!trimmedValue) {
    errors.push('Condition value is required');
  } else {
    const numeric = Number(trimmedValue);
    if (Number.isNaN(numeric)) errors.push(`Condition value must be a valid number: ${trimmedValue}`);
    else if (numeric < 0) errors.push('Condition value cannot be negative');
  }
  if (values.secondApprovalRoles.some(r => r === 'MANAGER')) {
    errors.push('MANAGER is always included automatically and cannot be selected as a second-approval role');
  }
  return errors;
}

function StatusPill({ active }: { active: boolean }) {
  return (
    <span style={{
      fontSize: 11.5, fontWeight: 600, padding: '3px 9px', borderRadius: 20, whiteSpace: 'nowrap',
      color: active ? '#2FB67C' : 'var(--txt-dim)', background: active ? 'rgba(47,182,124,.15)' : 'var(--raised2)',
    }}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

function ConfirmModal({ title, message, confirmLabel, danger, onConfirm, onCancel, busy }: {
  title: string; message: string; confirmLabel: string; danger?: boolean;
  onConfirm: () => void; onCancel: () => void; busy: boolean;
}) {
  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 440 }}>
        <div style={{ padding: 20 }}>
          <h3 style={{ fontSize: 15, fontFamily: 'Inter, sans-serif', color: 'var(--txt)', margin: '0 0 8px' }}>{title}</h3>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: '0 0 20px', lineHeight: 1.5 }}>{message}</p>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <button onClick={onCancel} disabled={busy} style={btnStyle}>Cancel</button>
            <button
              onClick={onConfirm}
              disabled={busy}
              style={{ ...btnPrimaryStyle, background: danger ? '#C0392B' : 'var(--brand)', borderColor: danger ? '#C0392B' : 'var(--brand)' }}
            >
              {busy ? 'Working…' : confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function RuleFormModal({ initial, metadata, onClose, onSaved }: {
  initial: { id: string; values: RuleFormValues } | null;
  metadata: ApprovalRuleMetadata;
  onClose: () => void;
  onSaved: () => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [values, setValues] = useState<RuleFormValues>(initial?.values ?? EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [sampleValue, setSampleValue] = useState(values.conditionValue || '500');
  const [preview, setPreview] = useState<ApprovalRulePreview | null>(null);
  const [previewing, setPreviewing] = useState(false);

  const conditionFields = metadata.conditionFieldsByRequestType[values.requestType] ?? [];
  const secondApprovalOptions = metadata.approvalRoles.filter(r => r !== 'MANAGER');

  function toggleSecondApprovalRole(role: string) {
    setValues(v => ({
      ...v,
      secondApprovalRoles: v.secondApprovalRoles.includes(role)
        ? v.secondApprovalRoles.filter(r => r !== role)
        : [...v.secondApprovalRoles, role],
    }));
  }

  async function handlePreview() {
    const formErrors = validateRuleForm(values, metadata);
    if (formErrors.length > 0) { setErrors(formErrors); return; }
    setErrors([]);
    setPreviewing(true);
    try {
      const result = await workflowRulesApi.preview({
        ruleName: values.ruleName || '(preview)',
        requestType: values.requestType,
        conditionField: values.conditionField,
        operator: values.operator,
        conditionValue: values.conditionValue,
        approvalStages: stagesFromForm(values),
        sampleValue: sampleValue.trim() ? Number(sampleValue) : undefined,
      }, token);
      setPreview(result);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Preview failed');
    } finally {
      setPreviewing(false);
    }
  }

  async function handleSave() {
    const formErrors = validateRuleForm(values, metadata);
    if (formErrors.length > 0) { setErrors(formErrors); return; }
    setErrors([]);
    setSaving(true);
    try {
      const payload = {
        ruleName: values.ruleName.trim(),
        requestType: values.requestType,
        conditionField: values.conditionField,
        operator: values.operator,
        conditionValue: values.conditionValue.trim(),
        approvalStages: stagesFromForm(values),
      };
      if (initial) {
        await workflowRulesApi.update(initial.id, payload, token);
        showToast('success', 'Rule updated');
      } else {
        await workflowRulesApi.create(payload, token);
        showToast('success', 'Rule saved as draft — activate it when you\'re ready');
      }
      onSaved();
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: 'Inter, sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            {initial ? 'Edit Rule' : 'Create Rule'}
          </span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex' }}><X size={16} /></button>
        </div>

        <div style={{ padding: 20 }}>
          {errors.length > 0 && (
            <div style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', marginBottom: 16 }}>
              {errors.map((err, i) => (
                <div key={i} style={{ fontSize: 12.5, color: '#E4373D' }}>{err}</div>
              ))}
            </div>
          )}

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>Rule Name</label>
            <input
              style={inputStyle}
              value={values.ruleName}
              onChange={e => setValues(v => ({ ...v, ruleName: e.target.value }))}
              placeholder="e.g. Second approval above $500"
            />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14 }}>
            <div>
              <label style={labelStyle}>Request Type</label>
              <select
                style={inputStyle}
                value={values.requestType}
                onChange={e => setValues(v => ({ ...v, requestType: e.target.value, conditionField: metadata.conditionFieldsByRequestType[e.target.value]?.[0] ?? '' }))}
              >
                {metadata.requestTypes.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div>
              <label style={labelStyle}>Condition Field</label>
              <select
                style={inputStyle}
                value={values.conditionField}
                onChange={e => setValues(v => ({ ...v, conditionField: e.target.value }))}
              >
                {conditionFields.map(f => <option key={f} value={f}>{f}</option>)}
              </select>
            </div>
          </div>

          <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 14, marginBottom: 16 }}>
            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginBottom: 8, fontWeight: 600 }}>IF</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 13, color: 'var(--txt)', fontWeight: 600 }}>{values.conditionField || 'Field'}</span>
              <select
                style={{ ...inputStyle, width: 70 }}
                value={values.operator}
                onChange={e => setValues(v => ({ ...v, operator: e.target.value }))}
              >
                {metadata.operators.map(op => <option key={op} value={op}>{op}</option>)}
              </select>
              <input
                style={{ ...inputStyle, width: 120 }}
                value={values.conditionValue}
                onChange={e => setValues(v => ({ ...v, conditionValue: e.target.value }))}
                placeholder="500"
                inputMode="decimal"
              />
            </div>

            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 14, marginBottom: 8, fontWeight: 600 }}>THEN</div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              <span style={{
                fontSize: 12, fontWeight: 600, padding: '5px 12px', borderRadius: 20,
                background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px dashed var(--line2)',
              }} title="Manager approval always applies — this cannot be turned off">
                Manager Approval (always required)
              </span>
              {secondApprovalOptions.map(role => {
                const checked = values.secondApprovalRoles.includes(role);
                return (
                  <button
                    key={role}
                    type="button"
                    onClick={() => toggleSecondApprovalRole(role)}
                    style={{
                      fontSize: 12, fontWeight: 600, padding: '5px 12px', borderRadius: 20, cursor: 'pointer',
                      border: checked ? '1px solid var(--brand)' : '1px solid var(--line2)',
                      background: checked ? 'rgba(228,55,61,.12)' : 'var(--shell)',
                      color: checked ? 'var(--brand)' : 'var(--txt-mut)',
                    }}
                  >
                    {checked ? '+ ' : ''}{role.replace('_', ' ')} Approval
                  </button>
                );
              })}
            </div>
            <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 8 }}>
              Applies only when the condition above is TRUE. When FALSE, Manager approval alone is required.
            </div>
          </div>

          <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 14, marginBottom: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              <label style={{ ...labelStyle, margin: 0 }}>Preview with sample amount</label>
              <input
                style={{ ...inputStyle, width: 120 }}
                value={sampleValue}
                onChange={e => setSampleValue(e.target.value)}
                inputMode="decimal"
              />
              <button onClick={handlePreview} disabled={previewing} style={btnStyle}>
                {previewing ? 'Evaluating…' : 'Preview Rule'}
              </button>
            </div>
            {preview && (
              <div style={{ fontSize: 12.5, color: 'var(--txt)', lineHeight: 1.7 }}>
                <div>Condition: <b style={{ fontFamily: 'Inter, sans-serif' }}>{preview.conditionExpression}</b> = <b style={{ color: preview.conditionResult ? '#2FB67C' : '#E4373D' }}>{String(preview.conditionResult).toUpperCase()}</b></div>
                <div style={{ marginTop: 4 }}>Current Routing: <b>{preview.currentRouting.join(' → ')}</b></div>
                <div>New Routing: <b>{preview.newRouting.join(' → ')}</b></div>
              </div>
            )}
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <button onClick={onClose} disabled={saving} style={btnStyle}>Cancel</button>
            <button onClick={handleSave} disabled={saving} style={btnPrimaryStyle}>
              {saving ? 'Saving…' : initial ? 'Save Changes' : 'Save Draft'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function AccessDenied() {
  return (
    <div style={{ ...card, padding: 40, textAlign: 'center' }}>
      <ShieldAlert size={28} style={{ color: 'var(--txt-dim)', marginBottom: 10 }} />
      <div style={{ fontSize: 15, color: 'var(--txt)', marginBottom: 6 }}>Super Admin access required</div>
      <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Workflow Studio rules can only be viewed or changed by a Super Admin.</div>
    </div>
  );
}

export default function WorkflowStudioPage() {
  const token = useAuthStore(s => s.token)!;
  const role = useAuthStore(s => s.user?.role);
  const { showToast } = useToast();
  const [rules, setRules] = useState<ApprovalRule[]>([]);
  const [metadata, setMetadata] = useState<ApprovalRuleMetadata | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [formTarget, setFormTarget] = useState<{ id: string; values: RuleFormValues } | 'new' | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<{ rule: ApprovalRule; action: 'activate' | 'deactivate' | 'delete' } | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);

  // Defensive, matching backend enforcement — the nav item is already SUPER_ADMIN-only, but a
  // direct URL visit by another role must not silently render live rule data.
  const authorized = role === 'SUPER_ADMIN';

  function load() {
    setLoading(true);
    setLoadError('');
    Promise.all([workflowRulesApi.listAll(token), workflowRulesApi.metadata(token)])
      .then(([ruleList, meta]) => { setRules(ruleList); setMetadata(meta); })
      .catch(e => setLoadError(e instanceof Error ? e.message : 'Failed to load'))
      .finally(() => setLoading(false));
  }

  useEffect(() => { if (authorized) load(); else setLoading(false); }, [token, authorized]);

  if (!authorized) return <AccessDenied />;

  async function handleConfirm() {
    if (!confirmTarget) return;
    setConfirmBusy(true);
    try {
      if (confirmTarget.action === 'activate') {
        await workflowRulesApi.activate(confirmTarget.rule.id, token);
        showToast('success', `"${confirmTarget.rule.ruleName}" is now active`);
      } else if (confirmTarget.action === 'deactivate') {
        await workflowRulesApi.deactivate(confirmTarget.rule.id, token);
        showToast('success', `"${confirmTarget.rule.ruleName}" deactivated`);
      } else {
        await workflowRulesApi.delete(confirmTarget.rule.id, token);
        showToast('success', `"${confirmTarget.rule.ruleName}" deleted`);
      }
      setConfirmTarget(null);
      load();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Action failed');
    } finally {
      setConfirmBusy(false);
    }
  }

  function openEdit(rule: ApprovalRule) {
    setFormTarget({
      id: rule.id,
      values: {
        ruleName: rule.ruleName,
        requestType: rule.requestType,
        conditionField: rule.conditionField,
        operator: rule.operator,
        conditionValue: rule.conditionValue,
        secondApprovalRoles: rule.approvalStages.filter(s => s !== 'MANAGER'),
      },
    });
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18 }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Workflow Studio</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
            Configure data-driven approval rules — the Approval Center routes new requests according to whichever rule is active.
          </p>
        </div>
        {metadata && (
          <button onClick={() => setFormTarget('new')} style={{ ...btnPrimaryStyle, display: 'flex', alignItems: 'center', gap: 6 }}>
            <Plus size={14} /> Create Rule
          </button>
        )}
      </div>

      <div style={card}>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 760 }}>
            <thead>
              <tr>
                {['Rule', 'Request Type', 'Condition', 'Approval Stages', 'Status', 'Updated', ''].map(h => (
                  <th key={h} style={thS}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>Loading…</td></tr>
              ) : loadError ? (
                <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>
                  <span style={{ color: '#E4373D' }}>Couldn't load workflow rules ({loadError}).</span>{' '}
                  <button onClick={load} style={{ color: 'var(--info)', background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', fontSize: 13, padding: 0 }}>Retry</button>
                </td></tr>
              ) : rules.length === 0 ? (
                <tr><td colSpan={7} style={{ ...tdS, textAlign: 'center', padding: 40 }}>No rules configured yet. Create one to start routing requests dynamically.</td></tr>
              ) : rules.map(rule => (
                <tr key={rule.id}>
                  <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{rule.ruleName}</td>
                  <td style={tdS}>{rule.requestType}</td>
                  <td style={{ ...tdS, whiteSpace: 'nowrap' }}>
                    <span style={{ fontFamily: 'Inter, sans-serif' }}>{rule.conditionField} {rule.operator} {rule.conditionValue}</span>
                  </td>
                  <td style={tdS}>{rule.approvalStages.join(' → ')}</td>
                  <td style={tdS}><StatusPill active={rule.active} /></td>
                  <td style={{ ...tdS, whiteSpace: 'nowrap' }}>{rule.updatedByName ?? rule.createdByName}</td>
                  <td style={{ ...tdS, whiteSpace: 'nowrap' }}>
                    <div style={{ display: 'flex', gap: 6 }}>
                      <button onClick={() => openEdit(rule)} style={{ ...btnStyle, padding: '5px 10px', fontSize: 12 }}>Edit</button>
                      {rule.active ? (
                        <button onClick={() => setConfirmTarget({ rule, action: 'deactivate' })} style={{ ...btnStyle, padding: '5px 10px', fontSize: 12 }}>Deactivate</button>
                      ) : (
                        <>
                          <button onClick={() => setConfirmTarget({ rule, action: 'activate' })} style={{ ...btnStyle, padding: '5px 10px', fontSize: 12, background: 'rgba(47,182,124,.15)', borderColor: 'rgba(47,182,124,.3)', color: '#2FB67C' }}>
                            <Check size={12} style={{ verticalAlign: -1 }} /> Activate
                          </button>
                          <button onClick={() => setConfirmTarget({ rule, action: 'delete' })} style={{ ...btnStyle, padding: '5px 10px', fontSize: 12, background: 'rgba(228,55,61,.1)', borderColor: 'rgba(228,55,61,.28)', color: '#E4373D' }}>
                            Delete
                          </button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {formTarget && metadata && (
        <RuleFormModal
          initial={formTarget === 'new' ? null : formTarget}
          metadata={metadata}
          onClose={() => setFormTarget(null)}
          onSaved={load}
        />
      )}

      {confirmTarget && (
        <ConfirmModal
          title={
            confirmTarget.action === 'activate' ? 'Activate this rule?'
              : confirmTarget.action === 'deactivate' ? 'Deactivate this rule?'
                : 'Delete this rule?'
          }
          message={
            confirmTarget.action === 'activate'
              ? `"${confirmTarget.rule.ruleName}" will start governing new ${confirmTarget.rule.requestType} requests immediately. Any other active rule for ${confirmTarget.rule.requestType} will be automatically deactivated. Requests already in flight are not affected.`
              : confirmTarget.action === 'deactivate'
                ? `New ${confirmTarget.rule.requestType} requests will fall back to the default routing (both Manager and HR approval required) until another rule is activated. Requests already in flight are not affected.`
                : 'This cannot be undone. Only inactive rules can be deleted.'
          }
          confirmLabel={confirmTarget.action === 'activate' ? 'Activate' : confirmTarget.action === 'deactivate' ? 'Deactivate' : 'Delete'}
          danger={confirmTarget.action === 'delete'}
          busy={confirmBusy}
          onConfirm={handleConfirm}
          onCancel={() => setConfirmTarget(null)}
        />
      )}
    </div>
  );
}
