import { useEffect, useState } from 'react';
import { Clock, CalendarOff, CalendarDays } from 'lucide-react';
import { Field, Section, inputStyle } from '../formPrimitives';
import { ShiftSelect } from '../SharedControls';
import { getShiftOptions, fmtEffectiveDate, type OrgOptions } from '../orgOptionsHelpers';
import { businessTodayIsoDate } from '../../../utils/businessDate';
import { holidaysApi, type HolidayRow } from '../../../api/holidays';
import type { CreateUserPayload } from '../../../api/employees';

interface WorkPolicyStepProps {
  form: CreateUserPayload;
  setForm: React.Dispatch<React.SetStateAction<CreateUserPayload>>;
  opts: OrgOptions;
  setOpts: React.Dispatch<React.SetStateAction<OrgOptions>>;
  token: string;
  startOnboarding: boolean;
  setStartOnboarding: (v: boolean) => void;
}

export function WorkPolicyStep({ form, setForm, opts, setOpts, token, startOnboarding, setStartOnboarding }: WorkPolicyStepProps) {
  const todayIso = businessTodayIsoDate();
  const [holidays, setHolidays] = useState<HolidayRow[] | null>(null);
  const [holidayError, setHolidayError] = useState<string | null>(null);

  // Read-only preview only — a failure here never sets the wizard-level `error` state and never
  // blocks Continue/Create Employee (see the plan's explicit requirement). holidaysApi.listByLocation
  // already accepts an arbitrary locationId (not just the viewer's own), so the admin's selected
  // Location from Step 2 is all that's needed here.
  useEffect(() => {
    if (!form.locationId) { setHolidays(null); setHolidayError(null); return; }
    let cancelled = false;
    holidaysApi.listByLocation(token, form.locationId)
      .then(rows => { if (!cancelled) { setHolidays(rows); setHolidayError(null); } })
      .catch(err => { if (!cancelled) { setHolidayError(err instanceof Error ? err.message : 'Could not load the holiday calendar preview'); } });
    return () => { cancelled = true; };
  }, [form.locationId, token]);

  const weeklyOffPolicies = opts.weeklyOffPolicies ?? [];
  const penalisationPolicies = opts.penalisationPolicies ?? [];

  return (
    <div className="nf-ce-stack">
      <Section icon={Clock} title="Shift" hint="Optional. The shift becomes active from the date you choose.">
      <div style={{ gridColumn: '1/-1' }}>
        <Field label="Shift">
          <ShiftSelect
            shifts={getShiftOptions(opts.shifts, form.shiftId)}
            value={form.shiftId}
            onChange={id => setForm(f => ({ ...f, shiftId: id }))}
            onCreated={s => setOpts(o => ({ ...o, shifts: [...o.shifts, s] }))}
            token={token}
          />
        </Field>
        {form.shiftId && (
          <div style={{ marginTop: 10 }}>
            <Field label="Effective From *">
              <input
                type="date"
                style={inputStyle}
                value={form.effectiveFrom ?? todayIso}
                min={todayIso}
                required
                onChange={e => setForm(f => ({ ...f, effectiveFrom: e.target.value }))}
              />
            </Field>
            {form.effectiveFrom && (
              <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 5 }}>
                This shift will become active for the employee from {fmtEffectiveDate(form.effectiveFrom)}.
              </div>
            )}
          </div>
        )}
      </div>

      </Section>
      <Section icon={CalendarOff} title="Weekly off and attendance policy" hint="Optional. Each policy has its own effective date.">
      <div>
        <Field label="Weekly-Off Policy">
          <select style={inputStyle} value={form.weeklyOffPolicyId ?? ''} onChange={e => setForm(f => ({ ...f, weeklyOffPolicyId: e.target.value || undefined }))}>
            <option value="">None</option>
            {weeklyOffPolicies.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </Field>
        {form.weeklyOffPolicyId && (
          <div style={{ marginTop: 10 }}>
            <Field label="Effective From *">
              <input type="date" style={inputStyle} value={form.weeklyOffEffectiveFrom ?? todayIso} min={todayIso} required
                onChange={e => setForm(f => ({ ...f, weeklyOffEffectiveFrom: e.target.value }))} />
            </Field>
            {form.weeklyOffEffectiveFrom && (
              <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 5 }}>
                This Weekly-Off Policy will become active for the employee from {fmtEffectiveDate(form.weeklyOffEffectiveFrom)}.
              </div>
            )}
          </div>
        )}
      </div>

      <div>
        <Field label="Attendance (Penalisation) Policy">
          <select style={inputStyle} value={form.penalisationPolicyId ?? ''} onChange={e => setForm(f => ({ ...f, penalisationPolicyId: e.target.value || undefined }))}>
            <option value="">None</option>
            {penalisationPolicies.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </Field>
        {form.penalisationPolicyId && (
          <div style={{ marginTop: 10 }}>
            <Field label="Effective From *">
              <input type="date" style={inputStyle} value={form.penalisationEffectiveFrom ?? todayIso} min={todayIso} required
                onChange={e => setForm(f => ({ ...f, penalisationEffectiveFrom: e.target.value }))} />
            </Field>
            {form.penalisationEffectiveFrom && (
              <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 5 }}>
                This Attendance Policy will become active for the employee from {fmtEffectiveDate(form.penalisationEffectiveFrom)}.
              </div>
            )}
          </div>
        )}
        <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 10 }}>
          Default leave balances are set up automatically — there's no separate Leave Policy to assign here.
        </div>
      </div>

      </Section>
      <Section icon={CalendarDays} title="Holiday calendar" hint="A read-only preview for the location you chose.">
      <div style={{ gridColumn: '1/-1' }}>
        <Field label="Holiday Calendar">
          {!form.locationId && (
            <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Select a Location in the previous step to preview its holiday calendar.</div>
          )}
          {form.locationId && holidayError && (
            <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Couldn't load the holiday calendar preview ({holidayError}) , this won't stop employee creation.</div>
          )}
          {form.locationId && !holidayError && holidays === null && (
            <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Loading holiday calendar…</div>
          )}
          {form.locationId && !holidayError && holidays !== null && (
            holidays.length === 0 ? (
              <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>No holidays configured for this location yet.</div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 8, maxHeight: 180, overflowY: 'auto' }}>
                {holidays.map(h => (
                  <div key={h.id} style={{ fontSize: 12.5, color: 'var(--txt-mut)', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, padding: '7px 10px' }}>
                    <div style={{ color: 'var(--txt)', fontWeight: 600 }}>{h.holidayName}</div>
                    <div>{fmtEffectiveDate(h.holidayDate)}</div>
                  </div>
                ))}
              </div>
            )
          )}
        </Field>
      </div>

      </Section>
      <div className="nf-ce-optin">
        <label className="nf-um-check" style={{ display: 'flex', alignItems: 'flex-start', gap: 10, background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: '13px 14px', cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={startOnboarding}
            onChange={e => setStartOnboarding(e.target.checked)}
            style={{ marginTop: 2, width: 16, height: 16, flexShrink: 0, accentColor: 'var(--brand)' }}
          />
          <span>
            <span style={{ display: 'block', fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>Start onboarding for this employee</span>
            <span style={{ display: 'block', fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>
              Creates their pre-boarding, document and setup checklist right away. Leave unchecked to add the account only and start onboarding later.
            </span>
          </span>
        </label>
      </div>
    </div>
  );
}
