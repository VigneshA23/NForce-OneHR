import { useEffect, useState } from 'react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayGroupResponse, type PayslipSettingsResponse } from '../../api/payroll';
import {
  panelStyle, FormRow, labelStyle, inputStyle, BtnPrimary, SectionHead, ToggleSwitch,
} from './payrollShared';

/** A simplified, clearly-labeled stand-in payslip — not real employee data, just enough
 * structure to show what the title/footer settings actually change. Keka's own preview (§3.9)
 * shows a full real payslip layout with a SAMPLE watermark; this keeps the same "it's a mock"
 * framing without fabricating line items this app has no reason to invent here. */
function PayslipPreview({ documentHeaderText, footerText }: { documentHeaderText: string; footerText: string }) {
  const title = `${documentHeaderText.trim() || 'Payslip for'} November 2026`;
  const footer = footerText.trim() || 'This is a computer-generated payslip and does not require a signature.';

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
        <div style={{ position: 'relative', textAlign: 'center', fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 4 }}>{title}</div>
        <div style={{ position: 'relative', textAlign: 'center', fontSize: 11.5, color: 'var(--txt-dim)', marginBottom: 16 }}>NForce One Pvt. Ltd. · Asha Rao · EMP001</div>
        <div style={{ position: 'relative', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 16 }}>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginBottom: 6 }}>Earnings</div>
            {[['Basic', '50,000.00'], ['HRA', '20,000.00']].map(([n, v]) => (
              <div key={n} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--txt-mut)', marginBottom: 4 }}><span>{n}</span><span>{v}</span></div>
            ))}
          </div>
          <div>
            <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginBottom: 6 }}>Deductions</div>
            {[['Professional Tax', '200.00']].map(([n, v]) => (
              <div key={n} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--txt-mut)', marginBottom: 4 }}><span>{n}</span><span>{v}</span></div>
            ))}
          </div>
        </div>
        <div style={{ position: 'relative', fontSize: 11, fontStyle: 'italic', color: 'var(--txt-dim)', borderTop: '1px solid var(--line)', paddingTop: 10 }}>{footer}</div>
      </div>
    </div>
  );
}

/** Keka's "Configure Payslip Settings" (§3.9) — scoped to just what the real PDF renderer
 * (PayslipPdfService) actually reads: password protection, the document header word, and the
 * footer note. Keka's own fuller spec (logo, body/field customization, section headings) has no
 * backing data here — there's no logo-upload system and the payslip layout's field set isn't
 * configurable in the renderer, so adding those toggles would be settings that quietly do
 * nothing. */
export function PayslipSettingsTab({ payGroups }: { payGroups: PayGroupResponse[] }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payGroupId, setPayGroupId] = useState(payGroups[0]?.id ?? '');
  const [settings, setSettings] = useState<PayslipSettingsResponse | null>(null);
  const [passwordProtected, setPasswordProtected] = useState(false);
  const [documentHeaderText, setDocumentHeaderText] = useState('');
  const [footerText, setFooterText] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!payGroupId) { setSettings(null); return; }
    payrollApi.getPayslipSettings(payGroupId, token).then(s => {
      setSettings(s);
      setPasswordProtected(s.passwordProtected);
      setDocumentHeaderText(s.documentHeaderText ?? '');
      setFooterText(s.footerText ?? '');
    }).catch(() => showToast('error', 'Failed to load payslip settings'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payGroupId, token]);

  async function save() {
    setSaving(true);
    try {
      const updated = await payrollApi.updatePayslipSettings(payGroupId, {
        passwordProtected, documentHeaderText: documentHeaderText.trim() || undefined, footerText: footerText.trim() || undefined,
      }, token);
      setSettings(updated);
      showToast('success', 'Payslip settings saved');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save payslip settings');
    } finally {
      setSaving(false);
    }
  }

  if (payGroups.length === 0) {
    return <div style={{ padding: 24, color: 'var(--txt-dim)' }}>Create a pay group first — payslip settings are scoped to one.</div>;
  }

  return (
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
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Header Customisation</div>
              <FormRow>
                <label style={labelStyle}>Document Header</label>
                <input value={documentHeaderText} onChange={e => setDocumentHeaderText(e.target.value)} style={inputStyle} placeholder="PAYSLIP" />
              </FormRow>
            </div>

            <div style={{ ...panelStyle, padding: '18px 20px' }}>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>Footer Customisation</div>
              <FormRow>
                <label style={labelStyle}>Footer Text</label>
                <textarea value={footerText} onChange={e => setFooterText(e.target.value)} style={{ ...inputStyle, minHeight: 80, resize: 'vertical' }} placeholder="This is a computer-generated payslip and does not require a signature." />
              </FormRow>
            </div>
          </div>

          <PayslipPreview documentHeaderText={documentHeaderText} footerText={footerText} />
        </div>
      )}
    </div>
  );
}
