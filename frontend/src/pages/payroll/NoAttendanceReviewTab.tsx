import { useEffect, useState } from 'react';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollRunReviewItemResponse } from '../../api/payroll';
import { leaveApi, type LeaveType } from '../../api/leave';
import {
  panelStyle, thStyle, tdStyle, fmtDate, StatusBadge, EmptyRow, Modal, FormRow, labelStyle,
  inputStyle, BtnPrimary, BtnGhost, SectionHead,
} from './payrollShared';

type Action = 'DEDUCT_FROM_LEAVE' | 'MARK_LOP' | 'NO_ACTION';

function DecideModal({ count, leaveTypes, onClose, onConfirm }: {
  count: number;
  leaveTypes: LeaveType[];
  onClose: () => void;
  onConfirm: (action: Action, selectedLeaveTypeId?: string, comment?: string) => Promise<void>;
}) {
  const [action, setAction] = useState<Action>('MARK_LOP');
  const [leaveTypeId, setLeaveTypeId] = useState(leaveTypes[0]?.id ?? '');
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const valid = action === 'DEDUCT_FROM_LEAVE' ? !!leaveTypeId : action === 'NO_ACTION' ? !!comment.trim() : true;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      await onConfirm(action, action === 'DEDUCT_FROM_LEAVE' ? leaveTypeId : undefined, action === 'NO_ACTION' ? comment.trim() : undefined);
      onClose();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title={count > 1 ? `Decide ${count} No-Attendance Days` : 'Decide No-Attendance Day'}
      subtitle="Deducting from leave is a payroll-side record only — it does not debit the employee's actual leave balance."
      onClose={onClose}
    >
      <FormRow>
        <label style={labelStyle}>Action *</label>
        <select value={action} onChange={e => setAction(e.target.value as Action)} style={inputStyle}>
          <option value="MARK_LOP">Mark LOP</option>
          <option value="DEDUCT_FROM_LEAVE">Deduct From Leave</option>
          <option value="NO_ACTION">No Action</option>
        </select>
      </FormRow>
      {action === 'DEDUCT_FROM_LEAVE' && (
        <FormRow>
          <label style={labelStyle}>Leave Type *</label>
          <select value={leaveTypeId} onChange={e => setLeaveTypeId(e.target.value)} style={inputStyle}>
            {leaveTypes.map(lt => <option key={lt.id} value={lt.id}>{lt.name}</option>)}
          </select>
        </FormRow>
      )}
      {action === 'NO_ACTION' && (
        <FormRow>
          <label style={labelStyle}>Comment *</label>
          <textarea value={comment} onChange={e => setComment(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
        </FormRow>
      )}
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : count > 1 ? `Save Decision (${count})` : 'Save Decision'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function NoAttendanceReviewTab({ runId, canConfigure }: { runId: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [items, setItems] = useState<PayrollRunReviewItemResponse[]>([]);
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[]>([]);
  const [deciding, setDeciding] = useState<PayrollRunReviewItemResponse | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [showBulkDecide, setShowBulkDecide] = useState(false);

  function reload() {
    payrollApi.listNoAttendanceReview(runId, token).then(setItems).catch(() => showToast('error', 'Failed to load review items'));
  }

  useEffect(reload, [runId, token]);
  useEffect(() => { leaveApi.listTypes(token).then(setLeaveTypes).catch(() => {}); }, [token]);

  const pendingIds = items.filter(i => i.decision === 'PENDING').map(i => i.id);
  const allPendingSelected = pendingIds.length > 0 && pendingIds.every(id => selected.has(id));

  function toggleAll() {
    setSelected(allPendingSelected ? new Set() : new Set(pendingIds));
  }
  function toggleOne(id: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function decideOne(action: Action, selectedLeaveTypeId?: string, comment?: string) {
    if (!deciding) return;
    try {
      await payrollApi.decideNoAttendance(runId, deciding.id, { action, selectedLeaveTypeId, comment }, token);
      showToast('success', 'Decision saved');
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Decision failed');
    }
  }

  async function decideBulk(action: Action, selectedLeaveTypeId?: string, comment?: string) {
    try {
      await payrollApi.bulkDecideNoAttendance(runId, { itemIds: Array.from(selected), action, selectedLeaveTypeId, comment }, token);
      showToast('success', `${selected.size} item(s) decided`);
      setSelected(new Set());
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Decision failed');
    }
  }

  return (
    <div>
      <SectionHead
        title="No Attendance Review"
        action={canConfigure && selected.size > 0 && (
          <BtnPrimary onClick={() => setShowBulkDecide(true)}>Decide Selected ({selected.size})</BtnPrimary>
        )}
      />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              {canConfigure && <th style={thStyle}><input type="checkbox" checked={allPendingSelected} onChange={toggleAll} disabled={pendingIds.length === 0} /></th>}
              {['Employee', 'Date', 'Decision', canConfigure ? 'Action' : ''].map(h => <th key={h} style={thStyle}>{h}</th>)}
            </tr>
          </thead>
          <tbody>
            {items.map(item => (
              <tr key={item.id}>
                {canConfigure && (
                  <td style={tdStyle}>
                    {item.decision === 'PENDING' && <input type="checkbox" checked={selected.has(item.id)} onChange={() => toggleOne(item.id)} />}
                  </td>
                )}
                <td style={tdStyle}><PersonCellById userId={item.employeeId} name={item.employeeName} /></td>
                <td style={tdStyle}>{fmtDate(item.itemDate)}</td>
                <td style={tdStyle}><StatusBadge status={item.decision} /></td>
                {canConfigure && (
                  <td style={tdStyle}>
                    <BtnGhost onClick={() => setDeciding(item)}>{item.decision === 'PENDING' ? 'Decide' : 'Change'}</BtnGhost>
                  </td>
                )}
              </tr>
            ))}
            {items.length === 0 && <EmptyRow cols={canConfigure ? 5 : 3} msg="No no-attendance items for this run." />}
          </tbody>
        </table>
      </div>
      {deciding && (
        <DecideModal count={1} leaveTypes={leaveTypes} onClose={() => setDeciding(null)} onConfirm={decideOne} />
      )}
      {showBulkDecide && (
        <DecideModal count={selected.size} leaveTypes={leaveTypes} onClose={() => setShowBulkDecide(false)} onConfirm={decideBulk} />
      )}
    </div>
  );
}
