import { useState, type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';
import type { ProfileData } from '../api/profile';
import type { EmployeeDocument } from '../api/documents';
import type { EmployeeCompensationResponse } from '../api/payroll';
import type { RegimeView } from '../api/taxProfile';
import { ReadField } from './profile/shared';
import { panelStyle, SectionHead, BtnGhost, fmtDate } from './payroll/payrollShared';

const grid = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16 } as const;

function Card({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <div style={{ marginBottom: 24 }}>
      <SectionHead title={title} action={action} />
      <div style={{ ...panelStyle, padding: '18px 22px' }}>{children}</div>
    </div>
  );
}

function Warn({ children }: { children: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13, color: 'var(--warn, #b45309)', marginBottom: 14 }}>
      <AlertTriangle size={16} /> {children}
    </div>
  );
}

/** US-F03 — read-only; the profile API already masks the account number to its last 4 digits. */
export function BankCard({ profile, onRequestChange }: { profile: ProfileData; onRequestChange: () => void }) {
  const missing = !profile.sensitiveRestricted && !(profile.bankAccountNumber && profile.bankName && profile.bankIfsc);
  return (
    <Card title="Payment Information" action={<BtnGhost onClick={onRequestChange}>Request change</BtnGhost>}>
      {missing && <Warn>Information required for payroll processing</Warn>}
      {profile.pendingSensitiveChange && (
        <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>A change request is waiting for approval. Details stay as they are until it is approved.</div>
      )}
      <div style={grid}>
        <ReadField label="Payment Mode" value="Bank Transfer" />
        <ReadField label="Bank Name" value={profile.bankName} />
        <ReadField label="Account Number" value={profile.bankAccountNumber} />
        <ReadField label="IFSC" value={profile.bankIfsc} />
        <ReadField label="Name on Account" value={profile.fullName} />
        {/* ponytail: branch isn't stored anywhere yet, so it is always N/A; add a column + request field when needed */}
        <ReadField label="Branch" value="N/A" />
      </div>
    </Card>
  );
}

/** US-F04 — PF is "Enabled" when a UAN is on file; ESI eligibility compares monthly CTC to the current ESI wage ceiling. */
export function StatutoryCard({ profile, current, esiCeiling }: { profile: ProfileData; current: EmployeeCompensationResponse | null; esiCeiling: number | null }) {
  const pfOn = !!profile.uanNumber;
  // ponytail: CTC/12 stands in for monthly gross; switch to the structure's gross once My Finances exposes it
  const notEligible = esiCeiling != null && current != null && current.ctc / 12 > esiCeiling;
  return (
    <Card title="Statutory Information">
      <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 8, color: 'var(--txt)' }}>Provident Fund (PF)</div>
      <div style={{ ...grid, marginBottom: 18 }}>
        <ReadField label="Status" value={pfOn ? 'Enabled' : 'Disabled'} />
        {pfOn && <>
          <ReadField label="PF Number" value="N/A" />
          <ReadField label="UAN" value={profile.uanNumber} />
          <ReadField label="PF Join Date" value={fmtDate(profile.joiningDate)} />
          <ReadField label="Name on Account" value={profile.fullName} />
        </>}
      </div>
      <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 8, color: 'var(--txt)' }}>Employee State Insurance (ESI)</div>
      <div style={{ ...grid, marginBottom: 18 }}>
        {notEligible
          ? <ReadField label="Status" value="Not Eligible" />
          : <ReadField label="ESI Number" value={profile.esiNumber ?? 'N/A'} />}
      </div>
      <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 8, color: 'var(--txt)' }}>Professional Tax (PT)</div>
      <div style={grid}>
        <ReadField label="Registered Location" value={profile.locationName ?? 'N/A'} />
      </div>
    </Card>
  );
}

const STATUS_LABEL: Record<string, string> = { VERIFIED: 'VERIFIED', PENDING_VERIFICATION: 'Pending Verification', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn' };

/** US-F05 — uploads and verification live in the Documents module; this card shows the latest status per document. */
export function IdentityCard({ profile, docs, onUpload }: { profile: ProfileData; docs: EmployeeDocument[]; onUpload: () => void }) {
  const latest = (re: RegExp) => docs.filter(d => !d.superseded && re.test(d.documentTypeName))
    .sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt))[0];
  const pan = latest(/\bpan\b/i);
  const sections: [string, EmployeeDocument | undefined][] = [['Photo ID', latest(/photo/i)], ['Address Proof', latest(/address/i)]];
  const count = (re: RegExp) => docs.filter(d => !d.superseded && re.test(d.documentTypeName)).length;
  return (
    <Card title="Identity Information" action={<BtnGhost onClick={onUpload}>Upload documents</BtnGhost>}>
      {pan?.status !== 'VERIFIED' && <Warn>No verified PAN on file. Tax may be deducted at a higher rate (Section 206AA).</Warn>}
      <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 8, color: 'var(--txt)' }}>PAN Card</div>
      <div style={{ ...grid, marginBottom: 18 }}>
        <ReadField label="Verification" value={pan ? STATUS_LABEL[pan.status] ?? pan.status : 'Not uploaded'} />
        <ReadField label="Files" value={String(count(/\bpan\b/i))} />
        <ReadField label="PAN" value={profile.panNumber} />
        <ReadField label="Name" value={profile.fullName} />
        <ReadField label="Date of Birth" value={profile.dateOfBirth ? fmtDate(profile.dateOfBirth) : null} />
        {/* ponytail: parent's name isn't stored yet */}
        <ReadField label="Parent's Name" value="N/A" />
      </div>
      {pan?.status === 'REJECTED' && pan.rejectionReason && <Warn>PAN rejected: {pan.rejectionReason}</Warn>}
      {sections.map(([label, d]) => (
        <div key={label} style={{ marginBottom: 14 }}>
          <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 8, color: 'var(--txt)' }}>{label}</div>
          <div style={grid}>
            <ReadField label="Status" value={d ? STATUS_LABEL[d.status] ?? d.status : 'Not uploaded'} />
            {d?.status === 'REJECTED' && <ReadField label="Reason" value={d.rejectionReason} />}
          </div>
        </div>
      ))}
    </Card>
  );
}

/** US-F06 — Old/New switch, only while the HR-set window is open. No tax recalculation yet (no tax engine). */
export function RegimeCard({ regime, onSave }: { regime: RegimeView | null; onSave: (r: 'OLD' | 'NEW') => Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const [choice, setChoice] = useState<'OLD' | 'NEW'>('NEW');
  const [saving, setSaving] = useState(false);
  if (!regime) return null;
  const name = (r: string) => (r === 'OLD' ? 'Old Regime' : 'New Regime');
  return (
    <Card title="Change in tax regime"
      action={regime.windowOpen && !editing ? <BtnGhost onClick={() => { setChoice(regime.regime); setEditing(true); }}>View &amp; Update</BtnGhost> : undefined}>
      <div style={{ fontSize: 14, color: 'var(--txt)' }}>
        {name(regime.regime)}{regime.isDefault ? ' (default, no choice made yet)' : ''}
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 6 }}>
        {regime.windowOpen
          ? `You can change your regime until ${fmtDate(regime.closesOn!)}.`
          : 'The window for changing your tax regime is closed. This is read-only.'}
      </div>
      {editing && (
        <div style={{ marginTop: 14 }}>
          {(['NEW', 'OLD'] as const).map(r => (
            <label key={r} style={{ display: 'block', fontSize: 13, color: 'var(--txt)', marginBottom: 6 }}>
              <input type="radio" name="regime" checked={choice === r} onChange={() => setChoice(r)} /> {name(r)}
            </label>
          ))}
          <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
            <BtnGhost disabled={saving} onClick={async () => { setSaving(true); try { await onSave(choice); setEditing(false); } finally { setSaving(false); } }}>
              {saving ? 'Saving…' : 'Save'}
            </BtnGhost>
            <BtnGhost onClick={() => setEditing(false)}>Cancel</BtnGhost>
          </div>
        </div>
      )}
    </Card>
  );
}

/** Shown on Salary, Income Tax and Declaration pages. */
export function RegimeBanner({ regime, onChange }: { regime: RegimeView | null; onChange: () => void }) {
  if (!regime) return null;
  return (
    <div style={{ ...panelStyle, padding: '10px 16px', marginBottom: 16, fontSize: 13, color: 'var(--txt)', display: 'flex', justifyContent: 'space-between', gap: 12 }}>
      <span>{regime.regime === 'OLD' ? 'Old Regime' : 'New Regime'} is in use{regime.isDefault ? ' (default)' : ''}.</span>
      <a href="#" onClick={e => { e.preventDefault(); onChange(); }}>{regime.windowOpen ? 'Switch regime' : 'View regime'}</a>
    </div>
  );
}
