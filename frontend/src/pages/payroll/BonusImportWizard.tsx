import { useEffect, useRef, useState } from 'react';
import * as XLSX from 'xlsx';
import { Download, Upload, AlertTriangle } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayGroupResponse, type SalaryComponentCatalogResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, FullScreenTask, Stepper, useEmployeeDirectory,
} from './payrollShared';
import {
  BONUS_REQUIRED_FIELDS, BONUS_ALL_FIELDS, BONUS_FIELD_LABEL, guessBonusColumnMapping, validateBonusRows,
  type BonusField, type BonusParsedRow, type RunWindow,
} from './bonusImportParsing';

const STEP_LABELS = ['Upload Template', 'Match Columns', 'Preview Data'];

function downloadTemplate() {
  const ws = XLSX.utils.aoa_to_sheet([BONUS_ALL_FIELDS.map(f => BONUS_FIELD_LABEL[f])]);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Bonuses');
  XLSX.writeFile(wb, 'bonus-payout-template.xlsx');
}

type RowResult = BonusParsedRow & { outcome?: 'imported' | 'failed'; outcomeMessage?: string };

/** Keka's "Import Bonuses with Payout Date" wizard (§3.4) — each valid row becomes one
 * payrollApi.addAdHocPayment call against whichever run's cycle already covers that payout date
 * (see bonusImportParsing#findRunForDate's own doc comment for why a date with no matching run
 * is reported, not queued). No new backend endpoint — this is pure client-side orchestration over
 * GET /runs and the existing ad-hoc payment endpoint. */
export function BonusImportWizard({ payGroups, onClose, onImported }: {
  payGroups: PayGroupResponse[]; onClose: () => void; onImported: () => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees } = useEmployeeDirectory();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [step, setStep] = useState(0);
  const [payGroupId, setPayGroupId] = useState(payGroups[0]?.id ?? '');
  const [components, setComponents] = useState<SalaryComponentCatalogResponse[]>([]);
  const [componentName, setComponentName] = useState('');
  const [defaultReason, setDefaultReason] = useState('');
  const [runs, setRuns] = useState<RunWindow[]>([]);
  const [fileName, setFileName] = useState('');
  const [headers, setHeaders] = useState<string[]>([]);
  const [dataRows, setDataRows] = useState<string[][]>([]);
  const [mapping, setMapping] = useState<Partial<Record<BonusField, number>>>({});
  const [results, setResults] = useState<RowResult[] | null>(null);
  const [importing, setImporting] = useState(false);

  useEffect(() => {
    payrollApi.listSalaryComponents(token)
      .then(all => {
        const earnings = all.filter(c => c.type === 'AD_HOC_EARNING' && c.active);
        setComponents(earnings);
        setComponentName(prev => prev || earnings[0]?.name || '');
      })
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!payGroupId) { setRuns([]); return; }
    payrollApi.listRuns(payGroupId, token)
      .then(all => setRuns(all.map(r => ({ runId: r.id, cycleStart: r.cycleStart, cycleEnd: r.cycleEnd, status: r.status }))))
      .catch(() => setRuns([]));
  }, [payGroupId, token]);

  async function handleFile(file: File) {
    try {
      const buffer = await file.arrayBuffer();
      const wb = XLSX.read(buffer, { type: 'array', cellDates: true });
      const sheet = wb.Sheets[wb.SheetNames[0]];
      const rows = XLSX.utils.sheet_to_json<string[]>(sheet, { header: 1, raw: false, dateNF: 'yyyy-mm-dd' });
      if (rows.length === 0) { showToast('error', 'That file has no rows'); return; }
      const [headerRow, ...rest] = rows;
      setFileName(file.name);
      setHeaders(headerRow.map(h => String(h ?? '').trim()));
      setDataRows(rest);
      setMapping(guessBonusColumnMapping(headerRow.map(h => String(h ?? ''))));
      setResults(null);
    } catch (e) {
      showToast('error', e instanceof Error ? `Could not read that file: ${e.message}` : 'Could not read that file');
    }
  }

  const employeesByCode = new Map(employees.filter(e => e.employeeCode).map(e => [e.employeeCode.toLowerCase(), { userId: e.userId, fullName: e.fullName }]));
  const mappingComplete = BONUS_REQUIRED_FIELDS.every(f => mapping[f] !== undefined);
  const parsedRows: RowResult[] = mappingComplete
    ? validateBonusRows(dataRows, mapping as { employeeCode: number; amount: number; payoutDate: number; reason?: number }, employeesByCode, runs, defaultReason)
    : [];
  const validCount = parsedRows.filter(r => !r.error).length;

  async function runImport() {
    setImporting(true);
    const rows = [...parsedRows];
    for (let i = 0; i < rows.length; i++) {
      if (rows[i].error) continue;
      try {
        await payrollApi.addAdHocPayment(rows[i].runId!, {
          employeeId: rows[i].employeeId!, componentName, amount: rows[i].amount!, reason: rows[i].reason!,
        }, token);
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
      title="Import Bonuses with Payout Date"
      onClose={onClose}
      actions={(
        <>
          {step > 0 && !results && <BtnGhost onClick={() => setStep(s => s - 1)} disabled={importing}>Back</BtnGhost>}
          {step < 2 && (
            <BtnPrimary onClick={() => setStep(s => s + 1)} disabled={(step === 0 && (!payGroupId || !componentName || dataRows.length === 0)) || (step === 1 && !mappingComplete)}>
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
        <div style={{ maxWidth: 480, margin: '0 auto' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14 }}>
            <FormRow>
              <label style={labelStyle}>Pay Group *</label>
              <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
                {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
              </select>
            </FormRow>
            <FormRow>
              <label style={labelStyle}>Bonus Component *</label>
              <select value={componentName} onChange={e => setComponentName(e.target.value)} style={inputStyle}>
                {components.map(c => <option key={c.id} value={c.name}>{c.name}</option>)}
              </select>
            </FormRow>
          </div>
          <FormRow>
            <label style={labelStyle}>Default Reason <span style={{ color: 'var(--txt-dim)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(used when a row's own Reason column is blank)</span></label>
            <input value={defaultReason} onChange={e => setDefaultReason(e.target.value)} style={inputStyle} placeholder="e.g. Annual bonus FY 2026-27" />
          </FormRow>
          <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', marginBottom: 14 }}>
            Each row's own payout date must fall inside an already-generated DRAFT cycle for this pay group — {runs.length === 0 ? 'no runs found for this pay group yet.' : `${runs.length} run${runs.length === 1 ? '' : 's'} found.`}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'flex-start' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, fontWeight: 700, color: 'var(--brand)', background: 'var(--raised)', borderRadius: 20, padding: '4px 10px', cursor: 'pointer' }} onClick={downloadTemplate}>
              <Download size={13} /> Download Excel Template
            </div>
            <BtnPrimary onClick={() => fileInputRef.current?.click()}><Upload size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Upload Excel File</BtnPrimary>
            <input ref={fileInputRef} type="file" accept=".xlsx,.xls,.csv" style={{ display: 'none' }} onChange={e => { const f = e.target.files?.[0]; if (f) handleFile(f); }} />
            {fileName && <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{fileName} — {dataRows.length} row{dataRows.length === 1 ? '' : 's'} found</div>}
          </div>
        </div>
      )}

      {step === 1 && (
        <div style={{ maxWidth: 480, margin: '0 auto' }}>
          <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 16 }}>Match each field to one of the columns found in "{fileName}".</div>
          {BONUS_REQUIRED_FIELDS.map(f => (
            <FormRow key={f}>
              <label style={labelStyle}>{BONUS_FIELD_LABEL[f]} *</label>
              <select value={mapping[f] ?? ''} onChange={e => setMapping(m => ({ ...m, [f]: e.target.value === '' ? undefined : Number(e.target.value) }))} style={inputStyle}>
                <option value="">Select a column…</option>
                {headers.map((h, i) => <option key={i} value={i}>{h || `Column ${i + 1}`}</option>)}
              </select>
            </FormRow>
          ))}
          <FormRow>
            <label style={labelStyle}>{BONUS_FIELD_LABEL.reason} <span style={{ color: 'var(--txt-dim)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(optional)</span></label>
            <select value={mapping.reason ?? ''} onChange={e => setMapping(m => ({ ...m, reason: e.target.value === '' ? undefined : Number(e.target.value) }))} style={inputStyle}>
              <option value="">Not in this file — use the default reason</option>
              {headers.map((h, i) => <option key={i} value={i}>{h || `Column ${i + 1}`}</option>)}
            </select>
          </FormRow>
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
              <thead><tr>{['Row', 'Employee Code', 'Employee', 'Amount', 'Payout Date', 'Reason', 'Status'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
              <tbody>
                {(results ?? parsedRows).map(r => (
                  <tr key={r.rowNumber}>
                    <td style={tdStyle}>{r.rowNumber}</td>
                    <td style={tdStyle}>{r.employeeCodeRaw}</td>
                    <td style={tdStyle}>{r.employeeName ?? '—'}</td>
                    <td style={tdStyle}>{r.amount != null ? fmtCurrency(r.amount) : r.amountRaw}</td>
                    <td style={tdStyle}>{r.payoutDate ? fmtDate(r.payoutDate) : r.payoutDateRaw}</td>
                    <td style={tdStyle}>{r.reason ?? '—'}</td>
                    <td style={tdStyle}>
                      {r.outcome === 'imported' && <span style={{ color: 'var(--ok)' }}>Imported</span>}
                      {r.outcome === 'failed' && <span style={{ color: 'var(--risk)' }}>{r.outcomeMessage}</span>}
                      {!r.outcome && r.error && <span style={{ color: 'var(--risk)' }}>{r.error}</span>}
                      {!r.outcome && !r.error && <span style={{ color: 'var(--ok)' }}>Ready</span>}
                    </td>
                  </tr>
                ))}
                {parsedRows.length === 0 && <EmptyRow cols={7} msg="No rows found." />}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </FullScreenTask>
  );
}
