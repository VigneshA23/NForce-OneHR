import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { TabBar, type TabDef } from '../../components/TabBar';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import {
  payrollApi, type PayGroupResponse, type PayGroupFilingDetailsResponse, type PtRegisteredLocationResponse,
} from '../../api/payroll';
import {
  panelStyle, Drawer, FormRow, labelStyle, inputStyle, BtnPrimary, BtnGhost, SectionHead,
  ConfirmDialog, fmtDate, WithFaqPanel, type FaqItem,
} from './payrollShared';

const FAQ_ITEMS: FaqItem[] = [
  { q: 'What are Filing Details actually for?', a: "They record the registration numbers (PAN/TAN, PF and ESI establishment IDs, PT-registered locations) a pay group needs for statutory filings. They're reference data only — saving them doesn't change any payroll calculation." },
  { q: 'How is this scoped?', a: 'Per pay group, via the selector at the top — the same scoping Payslip Settings uses. Switch pay groups to see or edit a different one’s filing details.' },
  { q: 'How do I register a Professional Tax location?', a: 'Open the Professional Tax (PT) tab. Pick an existing state on the left, then "Add Location to <state>" — or "Add New PT State" to start one that isn’t listed yet.' },
  { q: 'Is anything here validated against a government portal?', a: 'No. This app doesn’t integrate with any external tax authority — these fields are admin-entered and trusted as-is, the same as the Accounting Code field on Salary Components.' },
];

/** A label/value field in an editable settings grid — the pattern every Filing Details panel
 * (Income Tax, PF, ESI) shares, matching Keka's own label/value grid (§3.8). */
function SettingField({ label, value, onChange, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <FormRow>
      <label style={labelStyle}>{label}</label>
      <input value={value} onChange={e => onChange(e.target.value)} style={inputStyle} placeholder={placeholder} />
    </FormRow>
  );
}

type Draft = Omit<PayGroupFilingDetailsResponse, 'payGroupId' | 'updatedAt'>;

function toDraft(d: PayGroupFilingDetailsResponse): Draft {
  return {
    pan: d.pan, tan: d.tan, tanCircleNumber: d.tanCircleNumber, citTdsLocation: d.citTdsLocation,
    form130Signatory: d.form130Signatory, signatoryDesignation: d.signatoryDesignation,
    signatoryFatherName: d.signatoryFatherName, pfEstablishmentId: d.pfEstablishmentId, esiEstablishmentId: d.esiEstablishmentId,
  };
}

function IncomeTaxPanel({ details, onSave, saving }: { details: PayGroupFilingDetailsResponse; onSave: (patch: Partial<Draft>) => void; saving: boolean }) {
  const [draft, setDraft] = useState<Draft>(toDraft(details));
  useEffect(() => setDraft(toDraft(details)), [details]);

  return (
    <div>
      <SectionHead title="Income Tax Filing Details" action={<BtnPrimary onClick={() => onSave(draft)} disabled={saving}>{saving ? 'Saving…' : 'Save'}</BtnPrimary>} />
      <div style={{ ...panelStyle, padding: '18px 22px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
          <SettingField label="PAN" value={draft.pan ?? ''} onChange={v => setDraft(d => ({ ...d, pan: v.toUpperCase() }))} placeholder="e.g. AAAAA0000A" />
          <SettingField label="TAN" value={draft.tan ?? ''} onChange={v => setDraft(d => ({ ...d, tan: v.toUpperCase() }))} placeholder="e.g. AAAA00000A" />
          <SettingField label="TAN Circle Number" value={draft.tanCircleNumber ?? ''} onChange={v => setDraft(d => ({ ...d, tanCircleNumber: v }))} />
          <SettingField label="CIT (TDS) Location" value={draft.citTdsLocation ?? ''} onChange={v => setDraft(d => ({ ...d, citTdsLocation: v }))} placeholder="e.g. Hyderabad" />
          <SettingField label="Form 130 Signatory" value={draft.form130Signatory ?? ''} onChange={v => setDraft(d => ({ ...d, form130Signatory: v }))} />
          <SettingField label="Signatory Designation" value={draft.signatoryDesignation ?? ''} onChange={v => setDraft(d => ({ ...d, signatoryDesignation: v }))} />
          <SettingField label="Signatory Father's Name" value={draft.signatoryFatherName ?? ''} onChange={v => setDraft(d => ({ ...d, signatoryFatherName: v }))} />
        </div>
      </div>
    </div>
  );
}

function EstablishmentIdPanel({ title, fieldLabel, value, onSave, saving }: {
  title: string; fieldLabel: string; value: string; onSave: (value: string) => void; saving: boolean;
}) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);

  return (
    <div>
      <SectionHead title={title} action={<BtnPrimary onClick={() => onSave(draft)} disabled={saving}>{saving ? 'Saving…' : 'Save'}</BtnPrimary>} />
      <div style={{ ...panelStyle, padding: '18px 22px', maxWidth: 360 }}>
        <SettingField label={fieldLabel} value={draft} onChange={setDraft} placeholder="e.g. HYD1234567000" />
      </div>
    </div>
  );
}

function LocationDrawer({ payGroupId, defaultState, initial, onClose, onSaved }: {
  payGroupId: string; defaultState?: string; initial?: PtRegisteredLocationResponse; onClose: () => void; onSaved: () => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [stateName, setStateName] = useState(initial?.stateName ?? defaultState ?? '');
  const [locationName, setLocationName] = useState(initial?.locationName ?? '');
  const [establishmentId, setEstablishmentId] = useState(initial?.establishmentId ?? '');
  const [registrationDate, setRegistrationDate] = useState(initial?.registrationDate ?? '');
  const [signatory, setSignatory] = useState(initial?.signatory ?? '');
  const [signatoryDesignation, setSignatoryDesignation] = useState(initial?.signatoryDesignation ?? '');
  const [signatoryFatherName, setSignatoryFatherName] = useState(initial?.signatoryFatherName ?? '');
  const [submitting, setSubmitting] = useState(false);

  const valid = !!stateName.trim() && !!locationName.trim();

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    const payload = {
      stateName: stateName.trim(), locationName: locationName.trim(),
      establishmentId: establishmentId.trim() || undefined, registrationDate: registrationDate || undefined,
      signatory: signatory.trim() || undefined, signatoryDesignation: signatoryDesignation.trim() || undefined,
      signatoryFatherName: signatoryFatherName.trim() || undefined,
    };
    try {
      if (initial) await payrollApi.updatePtLocation(payGroupId, initial.id, payload, token);
      else await payrollApi.createPtLocation(payGroupId, payload, token);
      showToast('success', initial ? 'PT location updated' : 'PT location added');
      onSaved();
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save PT location');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Drawer
      title={initial ? `Edit PT Location – ${initial.locationName}` : 'Add New PT Location'}
      onClose={onClose}
      footer={<>
        <BtnGhost onClick={onClose}>Discard</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : initial ? 'Update' : 'Create'}</BtnPrimary>
      </>}
    >
      <FormRow><label style={labelStyle}>State *</label><input value={stateName} onChange={e => setStateName(e.target.value)} style={inputStyle} placeholder="e.g. Telangana" /></FormRow>
      <FormRow><label style={labelStyle}>Location *</label><input value={locationName} onChange={e => setLocationName(e.target.value)} style={inputStyle} placeholder="e.g. Hyderabad" /></FormRow>
      <FormRow><label style={labelStyle}>Establishment ID <span style={{ color: 'var(--txt-dim)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(Optional)</span></label><input value={establishmentId} onChange={e => setEstablishmentId(e.target.value)} style={inputStyle} /></FormRow>
      <FormRow><label style={labelStyle}>Registration Date</label><input type="date" value={registrationDate} onChange={e => setRegistrationDate(e.target.value)} style={inputStyle} /></FormRow>
      <FormRow><label style={labelStyle}>Signatory</label><input value={signatory} onChange={e => setSignatory(e.target.value)} style={inputStyle} /></FormRow>
      <FormRow><label style={labelStyle}>Signatory Designation</label><input value={signatoryDesignation} onChange={e => setSignatoryDesignation(e.target.value)} style={inputStyle} /></FormRow>
      <FormRow><label style={labelStyle}>Signatory Father's Name</label><input value={signatoryFatherName} onChange={e => setSignatoryFatherName(e.target.value)} style={inputStyle} /></FormRow>
    </Drawer>
  );
}

/** Keka's "PT Registered states" screen (§3.8) — a left list of states (derived from the
 * locations themselves; there's no separate "states" record), a right panel of location cards for
 * whichever state is selected. */
function ProfessionalTaxPanel({ payGroupId }: { payGroupId: string }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [locations, setLocations] = useState<PtRegisteredLocationResponse[]>([]);
  const [selectedState, setSelectedState] = useState<string | null>(null);
  const [showAdd, setShowAdd] = useState(false);
  const [editing, setEditing] = useState<PtRegisteredLocationResponse | null>(null);
  const [deleting, setDeleting] = useState<PtRegisteredLocationResponse | null>(null);

  function reload() {
    payrollApi.listPtLocations(payGroupId, token).then(setLocations).catch(() => showToast('error', 'Failed to load PT locations'));
  }
  useEffect(reload, [payGroupId, token]);

  const states = [...new Set(locations.map(l => l.stateName))].sort();
  useEffect(() => {
    if (!selectedState && states.length > 0) setSelectedState(states[0]);
    if (selectedState && !states.includes(selectedState)) setSelectedState(states[0] ?? null);
  }, [states, selectedState]);

  const locationsForState = locations.filter(l => l.stateName === selectedState);

  async function remove(l: PtRegisteredLocationResponse) {
    try {
      await payrollApi.deletePtLocation(payGroupId, l.id, token);
      showToast('success', 'PT location removed');
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to remove PT location');
    }
  }

  return (
    <div>
      <SectionHead
        title="PT Registered States"
        action={<BtnPrimary onClick={() => setShowAdd(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add New PT State</BtnPrimary>}
      />
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(200px, 260px) 1fr', gap: 18, alignItems: 'start' }}>
        <div style={panelStyle}>
          {states.length === 0 && <div style={{ padding: 18, fontSize: 13, color: 'var(--txt-dim)' }}>No PT states registered yet.</div>}
          {states.map(s => {
            const count = locations.filter(l => l.stateName === s).length;
            const active = s === selectedState;
            return (
              <button
                key={s} type="button" onClick={() => setSelectedState(s)}
                style={{
                  display: 'block', width: '100%', textAlign: 'left', padding: '12px 16px', border: 'none',
                  borderBottom: '1px solid var(--line)', cursor: 'pointer', font: 'inherit',
                  background: active ? 'color-mix(in srgb, var(--brand) 10%, var(--panel))' : 'var(--panel)',
                  color: active ? 'var(--brand)' : 'var(--txt)',
                }}
              >
                <div style={{ fontWeight: 600, fontSize: 14 }}>{s}</div>
                <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>{count} location{count === 1 ? '' : 's'}</div>
              </button>
            );
          })}
        </div>

        <div>
          {selectedState ? (
            <>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Registered PT Locations for {selectedState}</div>
              {locationsForState.length === 0 && (
                <div style={{ ...panelStyle, padding: '24px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No locations for this state yet.</div>
              )}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {locationsForState.map(l => (
                  <div key={l.id} style={{ ...panelStyle, padding: '16px 18px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 }}>
                      <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)' }}>{l.locationName}</div>
                      <ActionDock items={[
                        { label: 'Edit', icon: Pencil, onClick: () => setEditing(l) },
                        { label: 'Remove', icon: Trash2, danger: true, onClick: () => setDeleting(l) },
                      ]} />
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
                      <div><div style={labelStyle}>Establishment ID</div><div style={{ fontSize: 13.5, color: 'var(--txt)' }}>{l.establishmentId || '—'}</div></div>
                      <div><div style={labelStyle}>Registration Date</div><div style={{ fontSize: 13.5, color: 'var(--txt)' }}>{l.registrationDate ? fmtDate(l.registrationDate) : '—'}</div></div>
                      <div><div style={labelStyle}>Signatory</div><div style={{ fontSize: 13.5, color: 'var(--txt)' }}>{l.signatory || '—'}</div></div>
                      <div><div style={labelStyle}>Signatory Designation</div><div style={{ fontSize: 13.5, color: 'var(--txt)' }}>{l.signatoryDesignation || '—'}</div></div>
                      <div><div style={labelStyle}>Signatory Father's Name</div><div style={{ fontSize: 13.5, color: 'var(--txt)' }}>{l.signatoryFatherName || '—'}</div></div>
                    </div>
                  </div>
                ))}
              </div>
              <div style={{ marginTop: 14 }}>
                <BtnGhost onClick={() => setShowAdd(true)}><Plus size={13} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Location to {selectedState}</BtnGhost>
              </div>
            </>
          ) : (
            <div style={{ ...panelStyle, padding: '24px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>Add a PT state to get started.</div>
          )}
        </div>
      </div>

      {showAdd && (
        <LocationDrawer payGroupId={payGroupId} defaultState={selectedState ?? undefined} onClose={() => setShowAdd(false)} onSaved={reload} />
      )}
      {editing && (
        <LocationDrawer payGroupId={payGroupId} initial={editing} onClose={() => setEditing(null)} onSaved={reload} />
      )}
      {deleting && (
        <ConfirmDialog
          title="Remove PT Location" confirmLabel="Remove" onClose={() => setDeleting(null)} onConfirm={() => remove(deleting)}
          message={`Remove the PT registration for "${deleting.locationName}" (${deleting.stateName})? This can't be undone.`}
        />
      )}
    </div>
  );
}

type FilingView = 'income-tax' | 'pf' | 'esi' | 'pt';
const FILING_VIEWS: TabDef<FilingView>[] = [
  { key: 'income-tax', label: 'Income Tax' },
  { key: 'pf', label: 'Provident Fund (PF)' },
  { key: 'esi', label: 'Employee State Insurance (ESI)' },
  { key: 'pt', label: 'Professional Tax (PT)' },
];

/** Keka's "Filing Details" screen (§3.8) — scoped to one pay group at a time, same convention as
 * Employee Compensation's own employee picker (this app doesn't have Keka's nested
 * "Pay Group detail page with tabs" shell, so the pay group is chosen here instead of via URL). */
export function FilingDetailsTab({ payGroups }: { payGroups: PayGroupResponse[] }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payGroupId, setPayGroupId] = useState(payGroups[0]?.id ?? '');
  const [view, setView] = useState<FilingView>('income-tax');
  const [details, setDetails] = useState<PayGroupFilingDetailsResponse | null>(null);
  const [saving, setSaving] = useState(false);

  function reloadDetails() {
    if (!payGroupId) { setDetails(null); return; }
    payrollApi.getFilingDetails(payGroupId, token).then(setDetails).catch(() => showToast('error', 'Failed to load filing details'));
  }
  useEffect(reloadDetails, [payGroupId, token]);

  async function save(patch: Partial<Draft>) {
    if (!details) return;
    setSaving(true);
    try {
      const merged = { ...toDraft(details), ...patch };
      const updated = await payrollApi.updateFilingDetails(payGroupId, {
        pan: merged.pan ?? undefined, tan: merged.tan ?? undefined, tanCircleNumber: merged.tanCircleNumber ?? undefined,
        citTdsLocation: merged.citTdsLocation ?? undefined, form130Signatory: merged.form130Signatory ?? undefined,
        signatoryDesignation: merged.signatoryDesignation ?? undefined, signatoryFatherName: merged.signatoryFatherName ?? undefined,
        pfEstablishmentId: merged.pfEstablishmentId ?? undefined, esiEstablishmentId: merged.esiEstablishmentId ?? undefined,
      }, token);
      setDetails(updated);
      showToast('success', 'Filing details saved');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save filing details');
    } finally {
      setSaving(false);
    }
  }

  if (payGroups.length === 0) {
    return <div style={{ padding: 24, color: 'var(--txt-dim)' }}>Create a pay group first — filing details are scoped to one.</div>;
  }

  return (
    <WithFaqPanel items={FAQ_ITEMS} title="Filing Details Help">
    <div>
      <div style={{ maxWidth: 320, marginBottom: 18 }}>
        <label style={labelStyle}>Pay Group</label>
        <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
          {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
        </select>
      </div>

      <TabBar tabs={FILING_VIEWS} active={view} onChange={setView} className="nf-boxedtabs" ariaLabel="Filing details views" style={{ marginBottom: 18 }} />

      {!details ? (
        <div style={{ padding: 24, color: 'var(--txt-dim)' }}>Loading…</div>
      ) : (
        <>
          {view === 'income-tax' && <IncomeTaxPanel details={details} onSave={save} saving={saving} />}
          {view === 'pf' && (
            <EstablishmentIdPanel
              title="Provident Fund (PF) Filing Details" fieldLabel="PF Establishment ID"
              value={details.pfEstablishmentId ?? ''} saving={saving}
              onSave={v => save({ pfEstablishmentId: v })}
            />
          )}
          {view === 'esi' && (
            <EstablishmentIdPanel
              title="Employee State Insurance (ESI) Filing Details" fieldLabel="ESI Establishment ID"
              value={details.esiEstablishmentId ?? ''} saving={saving}
              onSave={v => save({ esiEstablishmentId: v })}
            />
          )}
          {view === 'pt' && <ProfessionalTaxPanel payGroupId={payGroupId} />}
        </>
      )}
    </div>
    </WithFaqPanel>
  );
}
