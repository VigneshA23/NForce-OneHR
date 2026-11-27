/**
 * LV-04 Exits (Organization Masters > Leave > Exits): employees with a last working day within 60 days either
 * side of today, and their regular paid leave for that leave year — the entitlement recomputed up to the last
 * working day by their plan's proration, what was used, and the result: days to encash, or leave taken beyond the
 * entitlement to recover in the final settlement. Recomputed nightly; "Recompute" refreshes one employee now.
 */
import { useCallback, useEffect, useState } from 'react';
import { useToast } from '../../context/ToastContext';
import { leaveConfigApi, type ExitSettlement } from '../../api/leave';
import { Badge, Table, days, errMsg, fmtDate, hintS, secondaryBtn, tdS } from './leaveConfigUi';

export default function LeaveExitsArea({ token, canConfigure }: { token: string; canConfigure: boolean }) {
  const { showToast } = useToast();
  const [rows, setRows] = useState<ExitSettlement[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    leaveConfigApi.exitSettlements(token).then(r => { setRows(r); setError(''); }).catch(e => setError(errMsg(e)));
  }, [token]);
  useEffect(load, [load]);

  async function recompute(userId: string) {
    setBusy(userId);
    try {
      await leaveConfigApi.recomputeExitSettlement(userId, token);
      showToast('success', 'Recomputed');
      load();
    } catch (e) {
      showToast('error', errMsg(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div>
      <p style={hintS}>
        Leavers' paid leave for the leave year of their last working day: what they earned up to that day (by their plan's
        proration), what they used, and what is left to encash — or taken beyond it, to recover in the final settlement.
        Payroll's exit cycle uses the same figures. Refreshed nightly for last working days within 60 days.
      </p>
      {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5, marginBottom: 12 }}>{error}</div>}
      {rows === null && !error ? <p style={hintS}>Loading…</p> : rows && (
        <Table head={['Employee', 'Last working day', 'Leave type', 'Earned', 'Carried', 'Used', 'Result', ...(canConfigure ? [''] : [])]}
          empty={rows.length === 0 && 'No one has a last working day within 60 days.'}>
          {rows.map(r => (
            <tr key={`${r.employeeUserId}-${r.leaveTypeId}`}>
              <td style={tdS}>
                <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{r.employeeName}</span>
                {r.employeeCode && <span style={{ marginLeft: 6, color: 'var(--txt-dim)' }}>{r.employeeCode}</span>}
              </td>
              <td style={tdS}>{fmtDate(r.lastWorkingDay)}</td>
              <td style={tdS}>{r.leaveTypeName} <span style={{ color: 'var(--txt-dim)' }}>{r.year}</span></td>
              <td style={tdS}>{days(r.entitledDays)}</td>
              <td style={tdS}>{days(r.carriedForwardDays)}</td>
              <td style={tdS}>{days(r.usedDays)}</td>
              <td style={tdS}>
                {r.recoveryDays > 0 ? <Badge tone="warn">Recover {days(r.recoveryDays)}</Badge>
                  : r.encashableDays > 0 ? <Badge tone="ok">Encash {days(r.encashableDays)}</Badge>
                    : <Badge tone="dim">Settled</Badge>}
              </td>
              {canConfigure && (
                <td style={tdS}>
                  <button type="button" style={secondaryBtn} disabled={busy === r.employeeUserId} onClick={() => recompute(r.employeeUserId)}>
                    {busy === r.employeeUserId ? 'Recomputing…' : 'Recompute'}
                  </button>
                </td>
              )}
            </tr>
          ))}
        </Table>
      )}
    </div>
  );
}
