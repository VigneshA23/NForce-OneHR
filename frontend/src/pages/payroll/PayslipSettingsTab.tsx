import { useEffect, useRef, useState } from 'react';
import { Upload, Trash2, ArrowUp, ArrowDown } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayGroupResponse, type PayslipSettingsResponse, type PayslipFieldKey } from '../../api/payroll';
import {
  panelStyle, FormRow, labelStyle, inputStyle, BtnPrimary, BtnGhost, SectionHead, ToggleSwitch,
  WithFaqPanel, type FaqItem,
} from './payrollShared';

const FAQ_ITEMS: FaqItem[] = [
  { q: 'How do I password-protect payslips?', a: 'Turn on "Enable password protection for payslips" under General Settings. Each employee\'s own payslip is then encrypted with their PAN number in uppercase as the password - no separate password to set or share.' },
  { q: 'How do I change the title, footer, or section headings on payslips?', a: 'Document Header and Footer Text set the title prefix and footer note. Section Headings (in Body Customisation) rename "Earnings"/"Deductions" to whatever your company calls them - e.g. "Income"/"Outgoings".' },
  { q: "How do I add my company's logo, and which side does it print on?", a: 'Use the Company Logo panel - upload a PNG or JPG under 500 KB, then choose Left or Right alignment, Square or Circle shape, and a size from 30 to 200pt. It prints next to the title on every generated payslip PDF for this pay group. Circle shows the whole logo, letterboxed inside the circle - it never crops part of it off.' },
  { q: 'What do the Payslip Fields and "Exclude N/A" options do?', a: 'Payslip Fields lets you choose which of Date of Joining, Department, Designation, UAN, ESI Number, PAN Number and Payment Mode print, and in what order - toggle one off or drag it with the arrows. "Exclude N/A Payslip Fields" drops a field entirely when it\'s blank, instead of printing a dash.' },
  { q: 'Where does Payment Mode come from?', a: 'It\'s not something you set directly - it reads "Bank Transfer" once an employee has both a bank account number and IFSC code on file, and "Cash" otherwise. Add or fix their bank details under Financial Details to change it.' },
  { q: 'What do Leave Summary and Arrear Breakup actually show?', a: "Leave Summary prints the employee's real leave balances for the year. Arrear Breakup lists each arrears record folded into this payslip's consolidated Arrears line, with its reason, period and amount - both pull from this app's real leave and arrears data, nothing fabricated." },
  { q: 'What does Include YTD actually add?', a: 'A Year-to-Date column next to every earning, deduction and contribution - the real cumulative total for this employee’s fiscal year through this payslip’s own cycle, not an estimate.' },
  { q: 'What are Employer Contributions, and why don’t they show by default?', a: 'Employer-side amounts like Employer PF - informational only, never subtracted from net pay. They only appear once a Statutory Rule has an employer rate configured and an "Employer PF" component exists in the catalog as type Contribution; until then this toggle has nothing to show.' },
  { q: 'Which components actually show on the payslip, and in what order?', a: "That's controlled on the Salary Components tab, not here - each component has its own \"shown on payslip\" setting and a position set from Order of Components." },
  { q: 'Do these settings apply to every pay group?', a: 'No. Everything on this page is scoped to whichever pay group is selected at the top. Switch the dropdown to configure another one.' },
];

const ALL_FIELD_KEYS: PayslipFieldKey[] = ['dateOfJoining', 'department', 'designation', 'uan', 'esi', 'pan', 'paymentMode'];
const FIELD_LABEL: Record<PayslipFieldKey, string> = {
  dateOfJoining: 'Date of Joining', department: 'Department', designation: 'Designation',
  uan: 'UAN', esi: 'ESI Number', pan: 'PAN Number', paymentMode: 'Payment Mode',
};
// Illustrative only - same masked shape a real payslip would show (see PayslipService#mask), not
// tied to any real employee.
const FIELD_SAMPLE_VALUE: Record<PayslipFieldKey, string> = {
  dateOfJoining: '12 Aug 2024', department: 'QA', designation: 'QA Engineer I',
  uan: 'XX5654', esi: '0987654323456789', pan: 'XXXXXUF34', paymentMode: 'Bank Transfer',
};
function sampleCyclePayPeriod(): string {
  const now = new Date();
  const end = new Date(now.getFullYear(), now.getMonth(), 25);
  const start = new Date(now.getFullYear(), now.getMonth() - 1, 26);
  const fmtDate = (d: Date) => d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
  return `${fmtDate(start)} - ${fmtDate(end)}`;
}
const SAMPLE_PAY_PERIOD = sampleCyclePayPeriod();

const SAMPLE_EARNINGS: [string, number][] = [
  ['Basic', 50000],
  ['House Rent Allowance', 20000],
  ['Leave Travel Allowance', 10000],
  ['Conveyance Allowance', 1600],
  ['Special Allowance', 8400],
];
const SAMPLE_DEDUCTIONS: [string, number][] = [
  ['Provident Fund', 3000],
  ['Professional Tax', 200],
];
const SAMPLE_GROSS = SAMPLE_EARNINGS.reduce((s, [, v]) => s + v, 0);
const SAMPLE_TOTAL_DEDUCTIONS = SAMPLE_DEDUCTIONS.reduce((s, [, v]) => s + v, 0);
const fmt = (n: number) => n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** A clearly-labeled stand-in payslip - not real employee data. Shows the full set of
 * components a real payslip carries (so HR can see what the header/footer/password settings
 * actually apply to), not just enough to prove the text fields work. Line items themselves
 * aren't configurable here - they come from the employee's own salary structure - so this
 * preview is illustrative, the same SAMPLE-watermarked framing as before. */
const CURRENT_MONTH_LABEL = new Date().toLocaleString('en-US', { month: 'long', year: 'numeric' });

function PayslipPreview({ documentHeaderText, footerText, logoUrl, earningsHeading, deductionsHeading, logoAlignment, logoSize, logoShape, payslipFields }: {
  documentHeaderText: string; footerText: string; logoUrl: string | null; earningsHeading: string; deductionsHeading: string;
  logoAlignment: 'LEFT' | 'RIGHT'; logoSize: number; logoShape: 'SQUARE' | 'CIRCLE'; payslipFields: PayslipFieldKey[];
}) {
  const title = `${documentHeaderText.trim() || 'Payslip for'} ${CURRENT_MONTH_LABEL}`;
  const earningsLabel = earningsHeading.trim() || 'Earnings';
  const deductionsLabel = deductionsHeading.trim() || 'Deductions';
  const footer = footerText.trim() || 'This is a computer-generated payslip and does not require a signature.';
  const netPay = SAMPLE_GROSS - SAMPLE_TOTAL_DEDUCTIONS;
  // Same fields (and order) the real PDF prints - Employee Name/No. always shown above, Pay
  // Period always last - so this preview actually answers "what will print," not just the title/logo.
  const fieldPairs: [string, string][] = [
    ...payslipFields.map((k): [string, string] => [FIELD_LABEL[k], FIELD_SAMPLE_VALUE[k]]),
    ['Pay Period', SAMPLE_PAY_PERIOD],
  ];
  // Scaled down from the PDF's point-based logoSize so a 200pt logo doesn't swallow this preview
  // card - the ratio stays the same, so bigger/smaller still reads as bigger/smaller here.
  const previewPx = Math.round(logoSize * 0.6);
  const logoImg = logoUrl && (
    logoShape === 'CIRCLE'
      ? (
        <div style={{ width: previewPx, height: previewPx, borderRadius: '50%', overflow: 'hidden', background: 'var(--raised)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
          <img src={logoUrl} alt="Company logo" style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
        </div>
      )
      : <img src={logoUrl} alt="Company logo" style={{ maxWidth: previewPx, maxHeight: previewPx, objectFit: 'contain' }} />
  );

  return (
    <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 14, padding: 20 }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Payslip Preview</div>
      <div style={{ position: 'relative', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: '20px 22px', overflow: 'hidden' }}>
        <div style={{
          position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 54, fontWeight: 800, color: 'var(--txt)', opacity: 0.05, transform: 'rotate(-20deg)', pointerEvents: 'none',
        }}>
          SAMPLE
        </div>
        {logoImg ? (
          <div style={{ position: 'relative', display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexDirection: logoAlignment === 'LEFT' ? 'row' : 'row-reverse' }}>
            {logoImg}
            <div style={{ flex: 1, textAlign: 'left' }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>{title}</div>
              <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>NForce One Pvt. Ltd. · Asha Rao · EMP001</div>
            </div>
          </div>
        ) : (
          <>
            <div style={{ position: 'relative', textAlign: 'center', fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 4 }}>{title}</div>
            <div style={{ position: 'relative', textAlign: 'center', fontSize: 11.5, color: 'var(--txt-dim)', marginBottom: 16 }}>NForce One Pvt. Ltd. · Asha Rao · EMP001</div>
          </>
        )}
        <div style={{ position: 'relative', display: 'grid', gridTemplateColumns: '1fr 1fr', columnGap: 16, rowGap: 6, marginBottom: 16, paddingBottom: 14, borderBottom: '1px solid var(--line)' }}>
          {fieldPairs.map(([label, value]) => (
            <div key={label} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 11.5 }}>
              <span style={{ color: 'var(--txt-dim)', fontWeight: 600 }}>{label}</span>
              <span style={{ color: 'var(--txt)' }}>{value}</span>
            </div>
          ))}
        </div>
        <div style={{ position: 'relative', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 4 }}>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginBottom: 6 }}>{earningsLabel}</div>
            {SAMPLE_EARNINGS.map(([n, v]) => (
              <div key={n} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 12, color: 'var(--txt-mut)', marginBottom: 4 }}><span>{n}</span><span>{fmt(v)}</span></div>
            ))}
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 12, fontWeight: 700, color: 'var(--txt)', marginTop: 6, paddingTop: 6, borderTop: '1px solid var(--line)' }}><span>Gross {earningsLabel}</span><span>{fmt(SAMPLE_GROSS)}</span></div>
          </div>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginBottom: 6 }}>{deductionsLabel}</div>
            {SAMPLE_DEDUCTIONS.map(([n, v]) => (
              <div key={n} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 12, color: 'var(--txt-mut)', marginBottom: 4 }}><span>{n}</span><span>{fmt(v)}</span></div>
            ))}
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 12, fontWeight: 700, color: 'var(--txt)', marginTop: 6, paddingTop: 6, borderTop: '1px solid var(--line)' }}><span>Total {deductionsLabel}</span><span>{fmt(SAMPLE_TOTAL_DEDUCTIONS)}</span></div>
          </div>
        </div>
        <div style={{ position: 'relative', display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'var(--raised)', borderRadius: 6, padding: '8px 12px', marginTop: 14 }}>
          <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt)' }}>Net Pay</span>
          <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--brand)' }}>₹{fmt(netPay)}</span>
        </div>
        <div style={{ position: 'relative', fontSize: 11, fontStyle: 'italic', color: 'var(--txt-dim)', borderTop: '1px solid var(--line)', paddingTop: 10, marginTop: 14 }}>{footer}</div>
      </div>
    </div>
  );
}

/** Keka's "Configure Payslip Settings" (§3.9): password protection, document header/footer text,
 * and the company logo printed above the payslip title - all genuinely read by the real PDF
 * renderer (PayslipPdfService), not placeholders. Line items themselves (which components show,
 * in what order) come from the salary component catalog's own "Order of Components"/"shown on
 * payslip" settings, not from here. */
export function PayslipSettingsTab({ payGroups }: { payGroups: PayGroupResponse[] }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payGroupId, setPayGroupId] = useState(payGroups[0]?.id ?? '');
  const [settings, setSettings] = useState<PayslipSettingsResponse | null>(null);
  const [passwordProtected, setPasswordProtected] = useState(false);
  const [documentHeaderText, setDocumentHeaderText] = useState('');
  const [footerText, setFooterText] = useState('');
  const [saving, setSaving] = useState(false);
  const [logoUrl, setLogoUrl] = useState<string | null>(null);
  const [logoBusy, setLogoBusy] = useState(false);
  const logoInputRef = useRef<HTMLInputElement>(null);
  const [logoAlignment, setLogoAlignment] = useState<'LEFT' | 'RIGHT'>('RIGHT');
  const [logoSize, setLogoSize] = useState(70);
  const [logoShape, setLogoShape] = useState<'SQUARE' | 'CIRCLE'>('SQUARE');
  const [excludeNaFields, setExcludeNaFields] = useState(false);
  const [earningsHeading, setEarningsHeading] = useState('');
  const [deductionsHeading, setDeductionsHeading] = useState('');
  const [includeLeaveSummary, setIncludeLeaveSummary] = useState(false);
  const [includeArrearBreakup, setIncludeArrearBreakup] = useState(false);
  const [includeYtdColumn, setIncludeYtdColumn] = useState(false);
  const [includeEmployerContributions, setIncludeEmployerContributions] = useState(false);
  const [payslipFields, setPayslipFields] = useState<PayslipFieldKey[]>([]);

  useEffect(() => {
    if (!payGroupId) { setSettings(null); return; }
    payrollApi.getPayslipSettings(payGroupId, token).then(s => {
      setSettings(s);
      setPasswordProtected(s.passwordProtected);
      setDocumentHeaderText(s.documentHeaderText ?? '');
      setFooterText(s.footerText ?? '');
      setLogoAlignment(s.logoAlignment);
      setLogoSize(s.logoSize || 70);
      setLogoShape(s.logoShape || 'SQUARE');
      setExcludeNaFields(s.excludeNaFields);
      setEarningsHeading(s.earningsHeading ?? '');
      setDeductionsHeading(s.deductionsHeading ?? '');
      setIncludeLeaveSummary(s.includeLeaveSummary);
      setIncludeArrearBreakup(s.includeArrearBreakup);
      setIncludeYtdColumn(s.includeYtdColumn);
      setIncludeEmployerContributions(s.includeEmployerContributions);
      setPayslipFields(s.payslipFields);
      loadLogo(s.hasLogo);
    }).catch(() => showToast('error', 'Failed to load payslip settings'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payGroupId, token]);

  function toggleField(key: PayslipFieldKey, on: boolean) {
    setPayslipFields(prev => (on ? [...prev, key] : prev.filter(k => k !== key)));
  }

  function moveField(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= payslipFields.length) return;
    setPayslipFields(prev => {
      const next = [...prev];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  }

  // The logo is raw bytes behind an authenticated endpoint (same shape as an employee photo), so
  // it's fetched as a blob and turned into an object URL rather than linked as a plain <img src>.
  function loadLogo(hasLogo: boolean) {
    setLogoUrl(prev => { if (prev) URL.revokeObjectURL(prev); return null; });
    if (!hasLogo) return;
    fetch(payrollApi.payslipLogoUrl(payGroupId), { headers: { Authorization: `Bearer ${token}` } })
      .then(res => (res.ok ? res.blob() : null))
      .then(blob => { if (blob) setLogoUrl(URL.createObjectURL(blob)); })
      .catch(() => {});
  }

  async function uploadLogo(file: File) {
    setLogoBusy(true);
    try {
      const updated = await payrollApi.uploadPayslipLogo(payGroupId, file, token);
      setSettings(updated);
      loadLogo(updated.hasLogo);
      showToast('success', 'Logo uploaded');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to upload logo');
    } finally {
      setLogoBusy(false);
    }
  }

  async function removeLogo() {
    setLogoBusy(true);
    try {
      const updated = await payrollApi.removePayslipLogo(payGroupId, token);
      setSettings(updated);
      loadLogo(false);
      showToast('success', 'Logo removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to remove logo');
    } finally {
      setLogoBusy(false);
    }
  }

  async function save() {
    setSaving(true);
    try {
      const updated = await payrollApi.updatePayslipSettings(payGroupId, {
        passwordProtected, documentHeaderText: documentHeaderText.trim() || undefined, footerText: footerText.trim() || undefined,
        logoAlignment, logoSize, logoShape, excludeNaFields,
        earningsHeading: earningsHeading.trim() || undefined, deductionsHeading: deductionsHeading.trim() || undefined,
        includeLeaveSummary, includeArrearBreakup, includeYtdColumn, includeEmployerContributions, payslipFields,
      }, token);
      setSettings(updated);
      setPayslipFields(updated.payslipFields);
      setLogoSize(updated.logoSize || 70);
      setLogoShape(updated.logoShape || 'SQUARE');
      showToast('success', 'Payslip settings saved');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save payslip settings');
    } finally {
      setSaving(false);
    }
  }

  if (payGroups.length === 0) {
    return <div style={{ padding: 24, color: 'var(--txt-dim)' }}>Create a pay group first - payslip settings are scoped to one.</div>;
  }

  return (
    <WithFaqPanel items={FAQ_ITEMS} title="Payslip Settings Help">
    <div>
      <SectionHead title="Configure Payslip Settings" action={<BtnPrimary onClick={save} disabled={!settings || saving}>{saving ? 'Saving…' : 'Save Changes'}</BtnPrimary>} />
      <div style={{ maxWidth: 320, marginBottom: 18 }}>
        <label style={labelStyle}>Pay Group</label>
        <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
          {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
        </select>
      </div>

      {!settings ? (
        <div style={{ padding: 24, color: 'var(--txt-dim)' }}>Loading…</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(280px, 1fr) minmax(300px, 420px)', gap: 24, alignItems: 'start' }}>
          <div>
            <div style={{ ...panelStyle, padding: '18px 20px', marginBottom: 16 }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>General Settings</div>
              <ToggleSwitch
                checked={passwordProtected} onChange={setPasswordProtected}
                label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>Enable password protection for payslips</span>}
              />
              {passwordProtected && (
                <div style={{ marginTop: 12, background: 'var(--raised)', borderRadius: 8, padding: '10px 14px', fontSize: 12, color: 'var(--txt-mut)' }}>
                  The password to open a payslip will be the employee's own PAN number (in uppercase). For example, if the PAN on file is "abcde1234f", the password is "ABCDE1234F".
                </div>
              )}
            </div>

            <div style={{ ...panelStyle, padding: '18px 20px', marginBottom: 16 }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Company Logo</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div style={{
                  width: 72, height: 72, borderRadius: logoShape === 'CIRCLE' ? '50%' : 8, border: '1px dashed var(--line)', background: 'var(--raised)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', flexShrink: 0,
                }}>
                  {logoUrl
                    ? <img src={logoUrl} alt="Company logo" style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
                    : <span style={{ fontSize: 10.5, color: 'var(--txt-dim)' }}>No logo</span>}
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <BtnGhost onClick={() => logoInputRef.current?.click()} disabled={logoBusy}>
                    <Upload size={13} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />{logoUrl ? 'Replace' : 'Upload'}
                  </BtnGhost>
                  {logoUrl && <BtnGhost onClick={removeLogo} disabled={logoBusy}><Trash2 size={13} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Remove</BtnGhost>}
                </div>
                <input
                  ref={logoInputRef} type="file" accept="image/*" style={{ display: 'none' }}
                  onChange={e => { const f = e.target.files?.[0]; if (f) uploadLogo(f); e.target.value = ''; }}
                />
              </div>
              <div style={{ marginTop: 10, fontSize: 11.5, color: 'var(--txt-dim)' }}>PNG or JPG, under 500 KB. Printed next to the title on every payslip for this pay group.</div>

              <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
                <div style={{ ...labelStyle, marginBottom: 8 }}>Company Logo Alignment</div>
                <div style={{ display: 'flex', gap: 18 }}>
                  {(['RIGHT', 'LEFT'] as const).map(a => (
                    <label key={a} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, color: 'var(--txt)', cursor: 'pointer' }}>
                      <input type="radio" name="logoAlignment" checked={logoAlignment === a} onChange={() => setLogoAlignment(a)} />
                      {a === 'RIGHT' ? 'Right aligned' : 'Left aligned'}
                    </label>
                  ))}
                </div>
              </div>

              <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
                <div style={{ ...labelStyle, marginBottom: 8 }}>Company Logo Shape</div>
                <div style={{ display: 'flex', gap: 18 }}>
                  {(['SQUARE', 'CIRCLE'] as const).map(sh => (
                    <label key={sh} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, color: 'var(--txt)', cursor: 'pointer' }}>
                      <input type="radio" name="logoShape" checked={logoShape === sh} onChange={() => setLogoShape(sh)} />
                      {sh === 'SQUARE' ? 'Square / as uploaded' : 'Circle'}
                    </label>
                  ))}
                </div>
              </div>

              <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                  <div style={labelStyle}>Company Logo Size</div>
                  <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{logoSize}pt</div>
                </div>
                <input
                  type="range" min={30} max={200} step={5} value={logoSize}
                  onChange={e => setLogoSize(Number(e.target.value))}
                  style={{ width: '100%' }}
                />
              </div>
            </div>

            <div style={{ ...panelStyle, padding: '18px 20px', marginBottom: 16 }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Header Customisation</div>
              <FormRow>
                <label style={labelStyle}>Document Header</label>
                <input value={documentHeaderText} onChange={e => setDocumentHeaderText(e.target.value)} style={inputStyle} placeholder="PAYSLIP" />
              </FormRow>
            </div>

            <div style={{ ...panelStyle, padding: '18px 20px', marginBottom: 16 }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Footer Customisation</div>
              <FormRow>
                <label style={labelStyle}>Footer Text</label>
                <textarea value={footerText} onChange={e => setFooterText(e.target.value)} style={{ ...inputStyle, minHeight: 80, resize: 'vertical' }} placeholder="This is a computer-generated payslip and does not require a signature." />
              </FormRow>
            </div>

            <div style={{ ...panelStyle, padding: '18px 20px', marginBottom: 16 }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 4 }}>Body Customisation</div>
              <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 12 }}>What prints in the body of the payslip, beyond the earnings and deductions table.</div>

              <ToggleSwitch
                checked={excludeNaFields} onChange={setExcludeNaFields}
                label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>Exclude N/A Payslip Fields</span>}
              />
              <div style={{ marginTop: 14 }}>
                <ToggleSwitch
                  checked={includeLeaveSummary} onChange={setIncludeLeaveSummary}
                  label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>Include Leave Summary in payslip</span>}
                />
              </div>
              <div style={{ marginTop: 14 }}>
                <ToggleSwitch
                  checked={includeArrearBreakup} onChange={setIncludeArrearBreakup}
                  label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>Include Arrear Breakup in payslip</span>}
                />
              </div>
              <div style={{ marginTop: 14 }}>
                <ToggleSwitch
                  checked={includeYtdColumn} onChange={setIncludeYtdColumn}
                  label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>Include YTD (Year to Date) column</span>}
                />
              </div>
              <div style={{ marginTop: 14, marginBottom: 16 }}>
                <ToggleSwitch
                  checked={includeEmployerContributions} onChange={setIncludeEmployerContributions}
                  label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>Include employer contributions (e.g. Employer PF)</span>}
                />
              </div>

              <div style={{ paddingTop: 14, borderTop: '1px solid var(--line)' }}>
                <div style={{ ...labelStyle, marginBottom: 10 }}>Section Headings</div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <FormRow>
                    <label style={labelStyle}>Earnings Heading</label>
                    <input value={earningsHeading} onChange={e => setEarningsHeading(e.target.value)} style={inputStyle} placeholder="Earnings" />
                  </FormRow>
                  <FormRow>
                    <label style={labelStyle}>Deductions Heading</label>
                    <input value={deductionsHeading} onChange={e => setDeductionsHeading(e.target.value)} style={inputStyle} placeholder="Deductions" />
                  </FormRow>
                </div>
              </div>

              <div style={{ paddingTop: 14, borderTop: '1px solid var(--line)' }}>
                <div style={{ ...labelStyle, marginBottom: 4 }}>Payslip Fields</div>
                <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 10 }}>Employee Name, Employee Number and Pay Period always print. Toggle and reorder the rest.</div>
                <div style={{ border: '1px solid var(--line)', borderRadius: 8, overflow: 'hidden' }}>
                  {payslipFields.map((key, i) => (
                    <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 12px', borderBottom: '1px solid var(--line)', background: 'var(--panel)' }}>
                      <input type="checkbox" checked onChange={() => toggleField(key, false)} />
                      <span style={{ flex: 1, fontSize: 13, color: 'var(--txt)' }}>{FIELD_LABEL[key]}</span>
                      <button type="button" onClick={() => moveField(i, -1)} disabled={i === 0} aria-label="Move up"
                        style={{ display: 'inline-flex', alignItems: 'center', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: 4, cursor: i === 0 ? 'not-allowed' : 'pointer', color: i === 0 ? 'var(--txt-dim)' : 'var(--txt-mut)' }}>
                        <ArrowUp size={12} />
                      </button>
                      <button type="button" onClick={() => moveField(i, 1)} disabled={i === payslipFields.length - 1} aria-label="Move down"
                        style={{ display: 'inline-flex', alignItems: 'center', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: 4, cursor: i === payslipFields.length - 1 ? 'not-allowed' : 'pointer', color: i === payslipFields.length - 1 ? 'var(--txt-dim)' : 'var(--txt-mut)' }}>
                        <ArrowDown size={12} />
                      </button>
                    </div>
                  ))}
                  {ALL_FIELD_KEYS.filter(k => !payslipFields.includes(k)).map(key => (
                    <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 12px', borderBottom: '1px solid var(--line)', background: 'var(--raised)' }}>
                      <input type="checkbox" checked={false} onChange={() => toggleField(key, true)} />
                      <span style={{ flex: 1, fontSize: 13, color: 'var(--txt-dim)' }}>{FIELD_LABEL[key]}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>

          <PayslipPreview
            documentHeaderText={documentHeaderText} footerText={footerText} logoUrl={logoUrl}
            earningsHeading={earningsHeading} deductionsHeading={deductionsHeading} logoAlignment={logoAlignment}
            logoSize={logoSize} logoShape={logoShape} payslipFields={payslipFields}
          />
        </div>
      )}
    </div>
    </WithFaqPanel>
  );
}
