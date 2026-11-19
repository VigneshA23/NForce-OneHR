import { useRef, useState } from 'react';
import * as XLSX from 'xlsx';
import { Download, Upload, AlertTriangle } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, EmptyRow, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, FullScreenTask, Stepper, useEmployeeDirectory,
} from './payrollShared';
import {
  FINANCIAL_REQUIRED_FIELDS, FINANCIAL_OPTIONAL_FIELDS, FINANCIAL_ALL_FIELDS, FINANCIAL_FIELD_LABEL,
  guessFinancialColumnMapping, validateFinancialRows, type FinancialField, type FinancialParsedRow,
} from './financialDetailsImportParsing';

const STEP_LABELS = ['Upload Template', 'Match Columns', 'Preview Data'];

function downloadTemplate() {
  const ws = XLSX.utils.aoa_to_sheet([FINANCIAL_ALL_FIELDS.map(f => FINANCIAL_FIELD_LABEL[f])]);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Financial Details');
  XLSX.writeFile(wb, 'financial-details-template.xlsx');
}

type RowResult = FinancialParsedRow & { outcome?: 'imported' | 'failed'; outcomeMessage?: string };

/** Keka's "Import Financial Information" wizard (§3.4) — every row becomes one
 * payrollApi.updateEmployeeFinancialDetails call (the admin write path that had to be built for
 * this; see EmployeeFinancialDetailsService's own doc comment for why none existed before). */
export function FinancialDetailsImportWizard({ onClose, onImported }: { onClose: () => void; onImported: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees } = useEmployeeDirectory();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [step, setStep] = useState(0);
  const [fileName, setFileName] = useState('');
  const [headers, setHeaders] = useState<string[]>([]);
  const [dataRows, setDataRows] = useState<string[][]>([]);
  const [mapping, setMapping] = useState<Partial<Record<FinancialField, number>>>({});
  const [results, setResults] = useState<RowResult[] | null>(null);
  const [importing, setImporting] = useState(false);

  async function handleFile(file: File) {
    try {
      const buffer = await file.arrayBuffer();
      const wb = XLSX.read(buffer, { type: 'array' });
      const sheet = wb.Sheets[wb.SheetNames[0]];
      const rows = XLSX.utils.sheet_to_json<string[]>(sheet, { header: 1, raw: false });
      if (rows.length === 0) { showToast('error', 'That file has no rows'); return; }
      const [headerRow, ...rest] = rows;
      setFileName(file.name);
      setHeaders(headerRow.map(h => String(h ?? '').trim()));
      setDataRows(rest);
      setMapping(guessFinancialColumnMapping(headerRow.map(h => String(h ?? ''))));
      setResults(null);
    } catch (e) {
      showToast('error', e instanceof Error ? `Could not read that file: ${e.message}` : 'Could not read that file');
    }
  }

  const employeesByCode = new Map(employees.filter(e => e.employeeCode).map(e => [e.employeeCode.toLowerCase(), { userId: e.userId, fullName: e.fullName }]));
  const mappingHasRequired = FINANCIAL_REQUIRED_FIELDS.every(f => mapping[f] !== undefined);
  const parsedRows: RowResult[] = mappingHasRequired ? validateFinancialRows(dataRows, mapping, employeesByCode) : [];
  const validCount = parsedRows.filter(r => !r.error).length;

  async function runImport() {
    setImporting(true);
    const rows = [...parsedRows];
    for (let i = 0; i < rows.length; i++) {
      if (rows[i].error) continue;
      try {
        await payrollApi.updateEmployeeFinancialDetails(rows[i].employeeId!, rows[i].patch, token);
        rows[i] = { ...rows[i], outcome: 'imported' };
      } catch (e) {
        rows[i] = { ...rows[i], outcome: 'failed', outcomeMessage: e instanceof Error ? e.message : 'Failed' };
      }
    }
    setResults(rows);
    setImporting(false);
    onImported();
  }

  const imported = results?.filter(r => r.outcome === 'imported').length ?? 0;
  const failed = results?.filter(r => r.outcome === 'failed').length ?? 0;

  return (
    <FullScreenTask
      title="Import Financial Information"
      onClose={onClose}
      actions={(
        <>
          {step > 0 && !results && <BtnGhost onClick={() => setStep(s => s - 1)} disabled={importing}>Back</BtnGhost>}
          {step < 2 && (
            <BtnPrimary onClick={() => setStep(s => s + 1)} disabled={(step === 0 && dataRows.length === 0) || (step === 1 && !mappingHasRequired)}>
              Next
            </BtnPrimary>
          )}
          {step === 2 && !results && (
            <BtnPrimary onClick={runImport} disabled={validCount === 0 || importing}>{importing ? 'Importing…' : `Confirm Import (${validCount})`}</BtnPrimary>
          )}
          {results && <BtnPrimary onClick={onClose}>Done</BtnPrimary>}
        </>
      )}
    >
      <Stepper steps={STEP_LABELS} activeIndex={step} />

      {step === 0 && (
        <div style={{ maxWidth: 480, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'flex-start' }}>
          <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 6 }}>
            Updates bank and statutory details (PAN, UAN, ESI number) for multiple employees at once. Include only the columns you need to update — anything left out of your file is left unchanged.
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, fontWeight: 700, color: 'var(--brand)', background: 'var(--raised)', borderRadius: 20, padding: '4px 10px', cursor: 'pointer' }} onClick={downloadTemplate}>
            <Download size={13} /> Download Excel Template
          </div>
          <BtnPrimary onClick={() => fileInputRef.current?.click()}><Upload size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Upload Excel File</BtnPrimary>
          <input ref={fileInputRef} type="file" accept=".xlsx,.xls,.csv" style={{ display: 'none' }} onChange={e => { const f = e.target.files?.[0]; if (f) handleFile(f); }} />
          {fileName && <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{fileName} — {dataRows.length} row{dataRows.length === 1 ? '' : 's'} found</div>}
        </div>
      )}

      {step === 1 && (
        <div style={{ maxWidth: 480, margin: '0 auto' }}>
          <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 16 }}>Match each field to one of the columns found in "{fileName}". Leave a field as "Not in this file" to skip it entirely.</div>
          {FINANCIAL_REQUIRED_FIELDS.map(f => (
            <FormRow key={f}>
              <label style={labelStyle}>{FINANCIAL_FIELD_LABEL[f]} *</label>
              <select value={mapping[f] ?? ''} onChange={e => setMapping(m => ({ ...m, [f]: e.target.value === '' ? undefined : Number(e.target.value) }))} style={inputStyle}>
                <option value="">Select a column…</option>
                {headers.map((h, i) => <option key={i} value={i}>{h || `Column ${i + 1}`}</option>)}
              </select>
            </FormRow>
          ))}
          {FINANCIAL_OPTIONAL_FIELDS.map(f => (
            <FormRow key={f}>
              <label style={labelStyle}>{FINANCIAL_FIELD_LABEL[f]}</label>
              <select value={mapping[f] ?? ''} onChange={e => setMapping(m => ({ ...m, [f]: e.target.value === '' ? undefined : Number(e.target.value) }))} style={inputStyle}>
                <option value="">Not in this file</option>
                {headers.map((h, i) => <option key={i} value={i}>{h || `Column ${i + 1}`}</option>)}
              </select>
            </FormRow>
          ))}
        </div>
      )}

      {step === 2 && (
        <div>
          {!results && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, fontSize: 13, color: 'var(--txt-mut)' }}>
              <span>{validCount} of {parsedRows.length} row{parsedRows.length === 1 ? '' : 's'} are ready to import.</span>
              {parsedRows.length - validCount > 0 && (
                <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--warn)' }}><AlertTriangle size={13} />{parsedRows.length - validCount} will be skipped.</span>
              )}
            </div>
          )}
          {results && (
            <div style={{ marginBottom: 14, fontSize: 13, color: 'var(--txt-mut)' }}>
              {imported} imported{failed > 0 ? `, ${failed} failed` : ''}.
            </div>
          )}
          <div style={panelStyle}>
            <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr>{['Row', 'Employee Code', 'Employee', 'Fields to Update', 'Status'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
              <tbody>
                {(results ?? parsedRows).map(r => (
                  <tr key={r.rowNumber}>
                    <td style={tdStyle}>{r.rowNumber}</td>
                    <td style={tdStyle}>{r.employeeCodeRaw}</td>
                    <td style={tdStyle}>{r.employeeName ?? '—'}</td>
                    <td style={tdStyle}>{Object.keys(r.patch).map(k => FINANCIAL_FIELD_LABEL[k as FinancialField]).join(', ') || '—'}</td>
                    <td style={tdStyle}>
                      {r.outcome === 'imported' && <span style={{ color: 'var(--ok)' }}>Imported</span>}
                      {r.outcome === 'failed' && <span style={{ color: 'var(--risk)' }}>{r.outcomeMessage}</span>}
                      {!r.outcome && r.error && <span style={{ color: 'var(--risk)' }}>{r.error}</span>}
                      {!r.outcome && !r.error && <span style={{ color: 'var(--ok)' }}>Ready</span>}
                    </td>
                  </tr>
                ))}
                {parsedRows.length === 0 && <EmptyRow cols={5} msg="No rows found." />}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </FullScreenTask>
  );
}
