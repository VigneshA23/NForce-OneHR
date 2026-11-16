import { useRef, useState } from 'react';
import { Download, FileSpreadsheet, Upload } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import { assetsApi, type AssetBulkRow, type AssetBulkValidation } from '../../api/assets';
import { Modal, BtnPrimary, BtnGhost, thStyle, tdStyle, labelStyle } from './shared';

const MAX_BYTES = 5 * 1024 * 1024; // matches AssetBulkImportService.MAX_FILE_BYTES

// [header, row field, column name the backend uses in its errors]
const COLUMNS: [string, keyof AssetBulkRow, string][] = [
  ['Asset Tag', 'assetTag', 'Asset Tag'], ['Category', 'category', 'Category'], ['Brand', 'brand', 'Brand'],
  ['Model', 'model', 'Model'], ['Serial No.', 'serialNumber', 'Serial Number'], ['Purchase Date', 'purchaseDate', 'Purchase Date'],
  ['Cost', 'purchaseCost', 'Purchase Cost'], ['Warranty', 'warrantyExpiry', 'Warranty Expiry'], ['Condition', 'condition', 'Condition'],
  ['Location', 'location', 'Location'],
];

const danger = 'var(--risk)';
const stepStyle: React.CSSProperties = { border: '1px solid var(--line)', borderRadius: 12, padding: '14px 16px', marginBottom: 14, background: 'var(--shell)' };
const outlineBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, background: 'var(--panel)', color: 'var(--brand)', border: '1px solid color-mix(in srgb, var(--brand) 40%, var(--line2))', borderRadius: 8, padding: '8px 14px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };

export function BulkAddAssetsModal({ token, onClose, onImported }: { token: string; onClose: () => void; onImported: (count: number) => void }) {
  const { showToast } = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<AssetBulkValidation | null>(null);
  const [busy, setBusy] = useState<'template' | 'validate' | 'import' | null>(null);
  const [confirming, setConfirming] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  async function downloadTemplate() {
    setBusy('template');
    try {
      const url = URL.createObjectURL(await assetsApi.downloadBulkTemplate(token));
      const a = document.createElement('a');
      a.href = url;
      a.download = 'asset-bulk-upload-template.xlsx';
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
    try { setResult(await assetsApi.validateBulk(f, token)); }
    catch (err) { showToast('error', err instanceof Error ? err.message : 'Could not validate the file'); }
    setBusy(null);
  }

  async function doImport() {
    if (!file) return;
    setBusy('import');
    try {
      const res = await assetsApi.importBulk(file, token);
      showToast('success', res.message);
      onImported(res.imported);
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Import failed');
      setConfirming(false);
      setBusy(null);
    }
  }

  const canImport = !!file && !!result && result.invalidRows === 0 && result.validRows > 0 && !busy;

  return (
    <Modal title="Bulk Add Assets" onClose={onClose} wide>
      <div style={stepStyle}>
        <label style={labelStyle}>Step 1 · Download template</label>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <button type="button" style={outlineBtn} onClick={downloadTemplate} disabled={busy === 'template'}>
            <Download size={14} /> {busy === 'template' ? 'Downloading…' : 'Download Excel Template'}
          </button>
          <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>Fill the “Assets” sheet. The “Instructions” sheet lists valid categories, locations and formats.</span>
        </div>
      </div>

      <div style={stepStyle}>
        <label style={labelStyle}>Step 2 · Upload completed file</label>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <button type="button" style={outlineBtn} onClick={() => fileInput.current?.click()} disabled={!!busy}>
            <Upload size={14} /> {file ? 'Replace file' : 'Choose .xlsx file'}
          </button>
          <input ref={fileInput} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={chooseFile} style={{ display: 'none' }} />
          {file && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--txt)' }}><FileSpreadsheet size={14} /> {file.name}</span>}
          {busy === 'validate' && <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>Validating…</span>}
        </div>
      </div>

      {result && (
        <div style={stepStyle}>
          <label style={labelStyle}>Step 3 · Preview &amp; validation</label>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
            <Chip label="Total" value={result.totalRows} />
            <Chip label="Valid" value={result.validRows} color="var(--ok)" />
            <Chip label="Invalid" value={result.invalidRows} color={result.invalidRows ? danger : undefined} />
          </div>
          {result.invalidRows > 0 && (
            <div role="alert" style={{ fontSize: 12.5, color: danger, marginBottom: 10 }}>
              {result.invalidRows} row(s) have errors. Correct them in Excel and upload the file again — nothing is imported until every row is valid.
            </div>
          )}
          <div style={{ overflow: 'auto', maxHeight: 340, border: '1px solid var(--line)', borderRadius: 10 }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead style={{ position: 'sticky', top: 0, zIndex: 1 }}>
                <tr>
                  <th style={thStyle}>Row</th>
                  {COLUMNS.map(([h]) => <th key={h} style={thStyle}>{h}</th>)}
                  <th style={thStyle}>Errors</th>
                </tr>
              </thead>
              <tbody>
                {result.rows.map(r => {
                  const badCols = new Set(r.errors.map(e => e.column));
                  const invalid = r.errors.length > 0;
                  return (
                    <tr key={r.rowNumber} style={invalid ? { background: 'color-mix(in srgb, var(--risk) 7%, transparent)' } : undefined}>
                      <td style={{ ...tdStyle, padding: '9px 12px', fontWeight: 600 }}>{r.rowNumber}</td>
                      {COLUMNS.map(([h, key, col]) => (
                        <td key={h} style={{ ...tdStyle, padding: '9px 12px', whiteSpace: 'nowrap', ...(badCols.has(col) ? { color: danger, fontWeight: 600, boxShadow: `inset 0 0 0 1px ${danger}` } : {}) }}>
                          {(r[key] as string | null) || '–'}
                        </td>
                      ))}
                      <td style={{ ...tdStyle, padding: '9px 12px', minWidth: 220, color: invalid ? danger : 'var(--ok)', fontSize: 12.5 }}>
                        {invalid ? r.errors.map((e, i) => <div key={i}><b>{e.column}:</b> {e.message}</div>) : 'OK'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', alignItems: 'center', flexWrap: 'wrap', marginTop: 4 }}>
        {confirming ? (
          <>
            <span style={{ fontSize: 13, color: 'var(--txt)', marginRight: 'auto' }}>
              Import <b>{result?.validRows}</b> asset(s) into the inventory as <b>AVAILABLE</b>?
            </span>
            <BtnGhost onClick={() => setConfirming(false)}>Back</BtnGhost>
            <BtnPrimary onClick={doImport} disabled={busy === 'import'}>{busy === 'import' ? 'Importing…' : 'Confirm Import'}</BtnPrimary>
          </>
        ) : (
          <>
            <BtnGhost onClick={onClose}>Cancel</BtnGhost>
            <BtnPrimary onClick={() => setConfirming(true)} disabled={!canImport}>
              {result && result.invalidRows === 0 ? `Import ${result.validRows} Asset(s)` : 'Import'}
            </BtnPrimary>
          </>
        )}
      </div>
    </Modal>
  );
}

function Chip({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <span style={{ display: 'inline-flex', gap: 6, alignItems: 'baseline', padding: '6px 12px', borderRadius: 999, border: '1px solid var(--line2)', background: 'var(--panel)', fontSize: 12.5, color: 'var(--txt-mut)' }}>
      {label} <b style={{ fontSize: 15, color: color ?? 'var(--txt)' }}>{value}</b>
    </span>
  );
}
