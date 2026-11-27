import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { useToast } from '../../context/ToastContext';
import {
  leaveConfigApi, type CarryForwardTransfer, type YearEndRunDetail, type YearEndRunLine, type YearEndRunSummary,
} from '../../api/leave';
import {
  Badge, ConfirmDialog, CURRENT_YEAR, FormError, Table, days, errMsg, fmtDate, hintS, inputS, labelS, primaryBtn, secondaryBtn, tdS,
  type Confirm,
} from './leaveConfigUi';
import { canRollBack, checkSplit, currentRun, stepOf, totalsText } from './yearEnd';

/**
 * LV-05: Organization Masters > Leave > Year-End. HR closes a leave year in three steps: Preview (every employee's
 * closing balance split per their plan into carry / encash / lapse) → Adjust (per line) → Confirm (transfers, next
 * year's balances, encashment to payroll, a notice to each employee). A confirmed year can be rolled back for a few
 * days. Years closed before LV-05 by the nightly job show their transfers.
 */
export default function YearEndWizard({ token, canConfigure, lastRun }: {
  token: string; canConfigure: boolean; lastRun: React.ReactNode;
}) {
  const { showToast } = useToast();
  const [fromYear, setFromYear] = useState(CURRENT_YEAR - 1);
  const [runs, setRuns] = useState<YearEndRunSummary[] | null>(null);
  const [detail, setDetail] = useState<YearEndRunDetail | null>(null);
  const [legacy, setLegacy] = useState<CarryForwardTransfer[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [query, setQuery] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const rs = await leaveConfigApi.yearEndRuns(fromYear, token);
      setRuns(rs);
      const cur = currentRun(rs);
      setDetail(cur ? await leaveConfigApi.yearEndRun(cur.id, token) : null);
      setLegacy(cur ? null : await leaveConfigApi.listTransfers(fromYear, token));
    } catch (e) {
      setError(errMsg(e));
    }
  }, [fromYear, token]);
  useEffect(() => { void load(); }, [load]);

  async function preview() {
    setBusy(true); setError('');
    try {
      const d = await leaveConfigApi.previewYearEnd(fromYear, token);
      setDetail(d);
      setRuns(await leaveConfigApi.yearEndRuns(fromYear, token));
      setLegacy(null);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  }

  function askConfirm(d: YearEndRunDetail) {
    setConfirm({
      title: `Close leave year ${d.run.fromYear}?`,
      body: `${totalsText(d)}. Carried days move into ${d.run.fromYear + 1}, encashments go to the next payroll draft and `
        + `every employee is told their outcome. You can roll this back for a few days, as long as nothing has been used or paid.`,
      confirmLabel: 'Confirm year-end',
      onConfirm: async () => {
        const out = await leaveConfigApi.confirmYearEnd(d.run.id, token);
        setDetail(out);
        showToast('success', `Leave year ${d.run.fromYear} closed for ${out.run.lines} balance(s)`);
        await load();
      },
    });
  }

  function askRollback(d: YearEndRunDetail) {
    setConfirm({
      title: `Roll back the ${d.run.fromYear} year-end?`,
      body: 'Carried days are taken out of next year\'s balances and the encashments are cancelled (removed from draft payroll '
        + 'runs). Employees are not notified. You can then preview and confirm the year again.',
      confirmLabel: 'Roll back',
      danger: true,
      onConfirm: async () => {
        const out = await leaveConfigApi.rollbackYearEnd(d.run.id, token);
        setDetail(out);
        showToast('success', `The ${d.run.fromYear} year-end was rolled back`);
        await load();
      },
    });
  }

  const step = stepOf(detail?.run ?? null);
  const editable = canConfigure && detail?.run.status === 'PREVIEW';
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (detail?.lines ?? []).filter(l => !q || l.employeeName.toLowerCase().includes(q) || (l.employeeCode ?? '').toLowerCase().includes(q));
  }, [detail, query]);
  const paged = usePaged(shown, [detail?.run.id, query]);

  function replaceLine(line: YearEndRunLine) {
    setDetail(d => d && { ...d, lines: d.lines.map(l => (l.id === line.id ? line : l)) });
  }

  return (
    <div style={{ marginTop: 28 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Year-end</h2>
      <p style={hintS}>
        A leave year is closed only when you confirm it here. Each employee's unused days are split per their leave plan
        on 31 December: carried forward up to the plan's cap, then encashed up to its cap, the rest lapses. Leave still
        pending at year-end stays reserved in the old year. Leavers are settled by their exit instead (Exits tab).
        Carried-forward days expire on their date automatically every night.
      </p>
      <Steps step={step} />
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', margin: '12px 0 14px' }}>
        <div style={{ width: 140 }}>
          <label style={labelS} htmlFor="ye-year">Leave year</label>
          <select id="ye-year" style={inputS} value={fromYear} onChange={e => setFromYear(Number(e.target.value))}>
            {[CURRENT_YEAR - 1, CURRENT_YEAR - 2, CURRENT_YEAR - 3].map(y => <option key={y} value={y}>{y} → {y + 1}</option>)}
          </select>
        </div>
        {canConfigure && detail?.run.status !== 'CONFIRMED' && (legacy === null || legacy.length === 0) && (
          <button style={primaryBtn(busy)} disabled={busy} onClick={preview}>
            {busy ? 'Working…' : detail?.run.status === 'PREVIEW' ? 'Preview again' : 'Preview year-end'}
          </button>
        )}
        {canConfigure && detail?.run.status === 'PREVIEW' && (
          <button style={primaryBtn(false)} onClick={() => askConfirm(detail)}>Confirm year-end</button>
        )}
        {canConfigure && detail && canRollBack(detail.run, new Date()) && (
          <button style={secondaryBtn} onClick={() => askRollback(detail)}>Roll back</button>
        )}
      </div>
      {lastRun}
      <FormError error={error} />

      {detail && (
        <>
          <RunHeader detail={detail} />
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', margin: '8px 0' }}>
            <input style={{ ...inputS, maxWidth: 260 }} placeholder="Search employee" value={query} onChange={e => setQuery(e.target.value)}
              aria-label="Search employee" />
          </div>
          <Table head={['Employee', 'Leave type', 'Unused', 'Carry', 'Encash', 'Lapse', 'Carried expire', 'Encashment']}
            empty={shown.length === 0 && (query ? 'No employee matches.' : 'No balances to close.')}>
            {paged.pageRows.map(l => (
              <LineRow key={l.id} line={l} runId={detail.run.id} editable={editable} token={token} onSaved={replaceLine} />
            ))}
          </Table>
          {shown.length > 0 && <TablePagination {...paged.pagerProps} noun="balances" />}
        </>
      )}

      {!detail && runs !== null && legacy !== null && (
        legacy.length > 0 ? <LegacyTransfers rows={legacy} fromYear={fromYear} /> : (
          <p style={hintS}>Leave year {fromYear} hasn't been closed yet.{canConfigure ? ' Start with a preview: nothing changes until you confirm.' : ''}</p>
        )
      )}
      {confirm && <ConfirmDialog {...confirm} onClose={() => setConfirm(null)} />}
    </div>
  );
}

function Steps({ step }: { step: 1 | 2 | 3 }) {
  const items = ['Preview', 'Adjust', 'Confirm'];
  return (
    <ol style={{ display: 'flex', gap: 8, listStyle: 'none', padding: 0, margin: 0, flexWrap: 'wrap' }} aria-label="Year-end steps">
      {items.map((label, i) => {
        const n = i + 1;
        const active = n === step, done = n < step;
        return (
          <li key={label} aria-current={active ? 'step' : undefined} style={{
            display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: active ? 700 : 500,
            color: active ? 'var(--txt)' : done ? 'var(--ok)' : 'var(--txt-dim)',
          }}>
            <span style={{
              width: 20, height: 20, borderRadius: 10, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 11,
              border: `1px solid ${active ? 'var(--brand)' : done ? 'var(--ok)' : 'var(--line2)'}`,
              background: active ? 'var(--brand)' : 'transparent', color: active ? '#fff' : 'inherit',
            }}>{done ? '✓' : n}</span>
            {label}
            {n < items.length && <span style={{ color: 'var(--line2)', margin: '0 2px' }}>—</span>}
          </li>
        );
      })}
    </ol>
  );
}

function RunHeader({ detail }: { detail: YearEndRunDetail }) {
  const r = detail.run;
  const status = r.status === 'PREVIEW' ? <Badge tone="info">Preview: nothing changed yet</Badge>
    : r.status === 'CONFIRMED' ? <Badge tone="ok">Confirmed</Badge> : <Badge tone="dim">Rolled back</Badge>;
  return (
    <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.6 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        {status}
        <span>{totalsText(detail)}{r.adjustedLines > 0 ? ` · ${r.adjustedLines} adjusted` : ''}</span>
      </div>
      <div style={{ color: 'var(--txt-dim)', fontSize: 12 }}>
        Previewed {fmtDate(r.createdAt)}{r.createdByName ? ` by ${r.createdByName}` : ''}
        {r.confirmedAt && ` · confirmed ${fmtDate(r.confirmedAt)}${r.confirmedByName ? ` by ${r.confirmedByName}` : ''}`}
        {r.status === 'CONFIRMED' && r.rollbackUntil && ` · can be rolled back until ${fmtDate(r.rollbackUntil)}`}
        {r.rolledBackAt && ` · rolled back ${fmtDate(r.rolledBackAt)}${r.rolledBackByName ? ` by ${r.rolledBackByName}` : ''}`}
        {detail.leaversSkipped > 0 && ` · ${detail.leaversSkipped} leaver(s) settled by their exit`}
        {detail.alreadyClosed > 0 && ` · ${detail.alreadyClosed} balance(s) already closed by the nightly job`}
      </div>
    </div>
  );
}

function LineRow({ line, runId, editable, token, onSaved }: {
  line: YearEndRunLine; runId: string; editable: boolean; token: string; onSaved(line: YearEndRunLine): void;
}) {
  const [carry, setCarry] = useState(String(Number(line.carryDays)));
  const [encash, setEncash] = useState(String(Number(line.encashDays)));
  const [problem, setProblem] = useState('');
  useEffect(() => { setCarry(String(Number(line.carryDays))); setEncash(String(Number(line.encashDays))); }, [line]);
  const check = checkSplit(Number(line.closingDays), carry, encash);

  async function save() {
    if (typeof check === 'string') { setProblem(check); return; }
    if (check.carry === Number(line.carryDays) && check.encash === Number(line.encashDays)) { setProblem(''); return; }
    try {
      onSaved(await leaveConfigApi.adjustYearEndLine(runId, line.id, check.carry, check.encash, token));
      setProblem('');
    } catch (e) {
      setProblem(errMsg(e));
    }
  }

  const num: React.CSSProperties = { ...inputS, width: 72, padding: '5px 7px' };
  return (
    <tr>
      <td style={{ ...tdS, color: 'var(--txt)' }}>
        {line.employeeName}{line.employeeCode ? <span style={{ color: 'var(--txt-dim)' }}> · {line.employeeCode}</span> : null}
        {line.adjusted && <> <Badge tone="warn">Adjusted</Badge></>}
        {problem && <div role="alert" style={{ color: 'var(--risk)', fontSize: 11.5 }}>{problem}</div>}
      </td>
      <td style={tdS}>{line.leaveTypeName}</td>
      <td style={{ ...tdS, color: 'var(--txt)' }}>{days(line.closingDays)}</td>
      <td style={tdS}>
        {editable
          ? <input type="number" min={0} step={0.5} style={num} value={carry} onChange={e => setCarry(e.target.value)} onBlur={save}
              aria-label={`${line.employeeName} ${line.leaveTypeName} carry`} />
          : <b style={{ color: 'var(--txt)' }}>{days(line.carryDays)}</b>}
      </td>
      <td style={tdS}>
        {editable
          ? <input type="number" min={0} step={0.5} style={num} value={encash} onChange={e => setEncash(e.target.value)} onBlur={save}
              aria-label={`${line.employeeName} ${line.leaveTypeName} encash`} />
          : days(line.encashDays)}
      </td>
      <td style={tdS}>{typeof check === 'string' ? '–' : days(editable ? check.lapse : line.lapseDays)}</td>
      <td style={tdS}>{Number(line.carryDays) > 0 ? (line.expiresOn ? fmtDate(line.expiresOn) : 'Never') : '–'}</td>
      <td style={tdS}>
        {line.encashAmount != null && Number(line.encashAmount) > 0
          ? <>₹{Number(line.encashAmount).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
              {line.encashmentStatus && <span style={{ color: 'var(--txt-dim)' }}> · {line.encashmentStatus === 'PENDING'
                ? 'waiting for payroll' : line.encashmentStatus === 'ADDED' ? 'in payroll draft' : 'cancelled'}</span>}</>
          : line.note ? <span style={{ fontSize: 11.5, color: 'var(--warn, #b7791f)' }}>{line.note}</span> : '–'}
      </td>
    </tr>
  );
}

function LegacyTransfers({ rows, fromYear }: { rows: CarryForwardTransfer[]; fromYear: number }) {
  const paged = usePaged(rows, [fromYear]);
  return (
    <>
      <p style={hintS}>Leave year {fromYear} was closed by the nightly job before the year-end wizard existed.</p>
      <Table head={['Employee', 'Leave Type', 'Unused', 'Carried', 'Encashed', 'Lapsed', 'Expires', 'Processed']}>
        {paged.pageRows.map(t => (
          <tr key={t.id}>
            <td style={{ ...tdS, color: 'var(--txt)' }}>{t.employeeName}{t.employeeCode ? <span style={{ color: 'var(--txt-dim)' }}> · {t.employeeCode}</span> : null}</td>
            <td style={tdS}>{t.leaveTypeName}</td>
            <td style={tdS}>{days(t.unusedDays)}</td>
            <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{days(t.carriedDays)}</td>
            <td style={tdS}>{days(t.encashedDays ?? 0)}</td>
            <td style={tdS}>{days(t.lapsedDays)}</td>
            <td style={tdS}>{t.carriedDays > 0 ? (t.expiresOn ? fmtDate(t.expiresOn) : 'Never') : '–'}</td>
            <td style={tdS}>{fmtDate(t.updatedAt ?? t.processedAt)}</td>
          </tr>
        ))}
      </Table>
      <TablePagination {...paged.pagerProps} noun="transfers" />
    </>
  );
}
