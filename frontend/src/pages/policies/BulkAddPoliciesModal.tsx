import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Download, FileSpreadsheet, Upload, X, Info } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import { downloadPolicyBulkTemplate, validatePolicyBulk, importPolicyBulk, type PolicyBulkRow, type PolicyBulkValidation } from '../../api/policies';
import '../policyForms.css';

const MAX_BYTES = 5 * 1024 * 1024; // matches PolicyBulkImportService.MAX_FILE_BYTES

// [preview header, row field, column name the backend uses in its errors]
const COLUMNS: [string, keyof PolicyBulkRow, string][] = [
  ['Policy Title', 'title', 'Policy Title'], ['Version', 'version', 'Version'],
  ['Audience', 'audience', 'Audience'], ['Acknowledgment', 'required', 'Acknowledgment Required'],
];

const danger = 'var(--risk)';
const stepS: React.CSSProperties = { border: '1px solid var(--line)', borderRadius: 12, padding: '14px 16px', marginBottom: 14, background: 'var(--shell)' };
const stepLabelS: React.CSSProperties = { display: 'block', fontSize: 11.5, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-mut)', marginBottom: 10 };
const outlineBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, background: 'var(--panel)', color: 'var(--brand)', border: '1px solid color-mix(in srgb, var(--brand) 40%, var(--line2))', borderRadius: 8, padding: '8px 14px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
const thS: React.CSSProperties = { padding: '10px 12px', textAlign: 'left', fontSize: 11, fontWeight: 800, color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.08em', whiteSpace: 'nowrap', background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))', borderBottom: '1px solid var(--line)' };
const tdS: React.CSSProperties = { padding: '9px 12px', fontSize: 13, color: 'var(--txt)', borderBottom: '1px solid var(--line)', verticalAlign: 'top' };
const ghostBtn: React.CSSProperties = { padding: '8px 20px', background: 'var(--shell)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', cursor: 'pointer', fontSize: 13 };
const primaryBtn: React.CSSProperties = { padding: '8px 20px', background: '#A01418', border: 'none', borderRadius: 6, color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600 };

function show(r: PolicyBulkRow, key: keyof PolicyBulkRow): string {
  const v = r[key];
  if (key === 'required') return v === null ? '–' : v ? 'Required' : 'Not required';
  if (key === 'audience') return String(v || '–').split(',').join(', ');
  return (v as string) || '–';
}

export function BulkAddPoliciesModal({ token, onClose, onImported }: { token: string; onClose(): void; onImported(count: number): void }) {
  const { showToast } = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<PolicyBulkValidation | null>(null);
  const [busy, setBusy] = useState<'template' | 'validate' | 'import' | null>(null);
  const [confirming, setConfirming] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  async function downloadTemplate() {
    setBusy('template');
    try {
      const url = URL.createObjectURL(await downloadPolicyBulkTemplate(token));
      const a = document.createElement('a');
      a.href = url;
      a.download = 'policy-bulk-upload-template.xlsx';
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not download the template'); }
    setBusy(null);
  }

  async function chooseFile(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    e.target.value = ''; // so re-selecting the same (corrected) file fires onChange again
    if (!f) return;
    setResult(null);
    setConfirming(false);
    if (!f.name.toLowerCase().endsWith('.xlsx')) { setFile(null); showToast('error', 'Only .xlsx files are supported'); return; }
    if (f.size === 0) { setFile(null); showToast('error', 'The selected file is empty'); return; }
    if (f.size > MAX_BYTES) { setFile(null); showToast('error', 'The file is larger than 5 MB'); return; }
    setFile(f);
    setBusy('validate');
    try { setResult(await validatePolicyBulk(token, f)); }
    catch (err) { showToast('error', err instanceof Error ? err.message : 'Could not validate the file'); }
    setBusy(null);
  }

  async function doImport() {
    if (!file) return;
    setBusy('import');
    try {
      const res = await importPolicyBulk(token, file);
      showToast('success', res.message);
      onImported(res.published);
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Import failed');
      setConfirming(false);
      setBusy(null);
    }
  }

  const canImport = !!file && !!result && result.invalidRows === 0 && result.validRows > 0 && !busy;
  const supersedeCount = result?.rows.filter(r => r.supersedesExisting).length ?? 0;

  return createPortal(
    <div className="nf-pf-dlg nf-modal-overlay-in">
      <div className="nf-pf-card nf-pf-card--wide nf-modal-panel-in" role="dialog" aria-label="Bulk Add Policies">
        <div className="nf-pf-head">
          <span className="nf-pf-ico"><FileSpreadsheet size={20} /></span>
          <div className="nf-pf-titles">
            <div className="nf-pf-title">Bulk Add Policies</div>
            <div className="nf-pf-sub">Publish many policies at once from an Excel file.</div>
          </div>
          <button type="button" onClick={onClose} className="nf-pf-x" aria-label="Close" disabled={busy === 'import'}><X size={16} /></button>
        </div>

        <div className="nf-pf-body">
          <div style={stepS}>
            <span style={stepLabelS}>Step 1 · Download template</span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <button type="button" style={outlineBtn} onClick={downloadTemplate} disabled={busy === 'template'}>
                <Download size={14} /> {busy === 'template' ? 'Downloading…' : 'Download Excel Template'}
              </button>
              <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>Fill the “Policies” sheet. The “Instructions” sheet explains each column and has an example row.</span>
            </div>
          </div>

          <div style={stepS}>
            <span style={stepLabelS}>Step 2 · Upload completed file</span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <button type="button" style={outlineBtn} onClick={() => fileInput.current?.click()} disabled={!!busy}>
                <Upload size={14} /> {file ? 'Replace file' : 'Choose .xlsx file'}
              </button>
              <input ref={fileInput} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={chooseFile} style={{ display: 'none' }} />
              {file && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--txt)', overflowWrap: 'anywhere' }}><FileSpreadsheet size={14} /> {file.name}</span>}
              {busy === 'validate' && <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>Validating…</span>}
            </div>
          </div>

          {result && (
            <div style={stepS}>
              <span style={stepLabelS}>Step 3 · Preview &amp; validation</span>
              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
                <Chip label="Total" value={result.totalRows} />
                <Chip label="Valid" value={result.validRows} color="var(--ok)" />
                <Chip label="Invalid" value={result.invalidRows} color={result.invalidRows ? danger : undefined} />
              </div>
              {result.invalidRows > 0 && (
                <div role="alert" style={{ fontSize: 12.5, color: danger, marginBottom: 10 }}>
                  {result.invalidRows} row(s) have errors. Correct them in Excel and upload the file again — nothing is published until every row is valid.
                </div>
              )}
              <div style={{ overflow: 'auto', maxHeight: 340, border: '1px solid var(--line)', borderRadius: 10 }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead style={{ position: 'sticky', top: 0, zIndex: 1 }}>
                    <tr>
                      <th style={thS}>Row</th>
                      {COLUMNS.map(([h]) => <th key={h} style={thS}>{h}</th>)}
                      <th style={thS}>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.rows.map(r => {
                      const badCols = new Set(r.errors.map(e => e.column));
                      const invalid = r.errors.length > 0;
                      return (
                        <tr key={r.rowNumber} style={invalid ? { background: 'color-mix(in srgb, var(--risk) 7%, transparent)' } : undefined}>
                          <td style={{ ...tdS, fontWeight: 600 }}>{r.rowNumber}</td>
                          {COLUMNS.map(([h, key, col]) => (
                            <td key={h} title={key === 'title' ? r.description : undefined}
                              style={{ ...tdS, ...(key === 'title' ? { minWidth: 180, overflowWrap: 'anywhere' } : { whiteSpace: 'nowrap' }), ...(badCols.has(col) ? { color: danger, fontWeight: 600, boxShadow: `inset 0 0 0 1px ${danger}` } : {}) }}>
                              {show(r, key)}
                            </td>
                          ))}
                          <td style={{ ...tdS, minWidth: 220, fontSize: 12.5, color: invalid ? danger : 'var(--ok)' }}>
                            {invalid
                              ? r.errors.map((e, i) => <div key={i}><b>{e.column}:</b> {e.message}</div>)
                              : r.supersedesExisting
                                ? <span style={{ color: 'var(--warn)' }}>OK · replaces the active policy with this title</span>
                                : 'OK'}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {confirming && result && (
            <div className="nf-pf-callout" role="status">
              <Info size={15} />
              <div>
                <b>Step 4 · Confirm.</b> All <b>{result.validRows}</b> valid polic{result.validRows === 1 ? 'y' : 'ies'} will be published.
                {' '}{supersedeCount > 0
                  ? <><b>{supersedeCount}</b> existing active polic{supersedeCount === 1 ? 'y has' : 'ies have'} the same title and will be deactivated (superseded), as when publishing individually.</>
                  : 'Existing policies with the same title are superseded, as when publishing individually.'}
                {' '}Acknowledgment records and notifications are created for every employee in each policy’s audience.
              </div>
            </div>
          )}
        </div>

        <div className="nf-pf-foot" style={{ flexWrap: 'wrap' }}>
          {confirming ? (
            <>
              <button type="button" style={ghostBtn} onClick={() => setConfirming(false)} disabled={busy === 'import'}>Back</button>
              <button type="button" style={primaryBtn} onClick={doImport} disabled={busy === 'import'}>
                {busy === 'import' ? 'Publishing…' : 'Publish Policies'}
              </button>
            </>
          ) : (
            <>
              <button type="button" style={ghostBtn} onClick={onClose}>Cancel</button>
              <button type="button" style={{ ...primaryBtn, ...(canImport ? {} : { opacity: .5, cursor: 'not-allowed' }) }} onClick={() => setConfirming(true)} disabled={!canImport}>
                {result && result.invalidRows === 0 ? `Continue with ${result.validRows} Polic${result.validRows === 1 ? 'y' : 'ies'}` : 'Continue'}
              </button>
            </>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}

function Chip({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <span style={{ display: 'inline-flex', gap: 6, alignItems: 'baseline', padding: '6px 12px', borderRadius: 999, border: '1px solid var(--line2)', background: 'var(--panel)', fontSize: 12.5, color: 'var(--txt-mut)' }}>
      {label} <b style={{ fontSize: 15, color: color ?? 'var(--txt)' }}>{value}</b>
    </span>
  );
}
