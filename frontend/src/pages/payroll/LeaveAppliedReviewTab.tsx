import { useEffect, useState } from 'react';
import { PersonCellById } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayrollRunReviewItemResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtDate, StatusBadge, EmptyRow, Modal, FormRow, labelStyle,
  inputStyle, BtnPrimary, BtnGhost, SectionHead, FilterBar,
} from './payrollShared';

function RejectReasonModal({ onClose, onConfirm }: { onClose: () => void; onConfirm: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  async function submit() {
    if (!reason.trim()) return;
    setSubmitting(true);
    try { await onConfirm(reason.trim()); onClose(); } finally { setSubmitting(false); }
  }
  return (
    <Modal title="Reject Leave-Applied Items" subtitle="Rejecting a penalty-sourced item cancels the penalty. Rejecting an unpaid-leave item only excludes it from this run's LOP - it is not a durable decision and resets on regenerate." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Reason</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!reason.trim() || submitting}>{submitting ? 'Rejecting…' : 'Reject Selected'}</BtnPrimary>
      </div>
    </Modal>
  );
}

const DISPUTE_LABEL: Record<string, string> = {
  PENDING_MANAGER: 'Disputed · with manager',
  PENDING_HR: 'Disputed · with HR',
  UPHELD: 'Dispute upheld',
  REVERSED: 'Dispute reversed',
};

/** PN-02: flags a penalty item the employee has disputed, so the reviewer sees it before deciding. */
function DisputeFlag({ status }: { status?: string | null }) {
  if (!status) return null;
  const open = status === 'PENDING_MANAGER' || status === 'PENDING_HR';
  const color = open ? 'var(--warn)' : status === 'REVERSED' ? 'var(--ok)' : 'var(--txt-mut)';
  return (
    <span data-testid="dispute-flag" title={open ? 'The employee disputed this penalty - consider waiting for the outcome before deciding.' : undefined}
      style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10.5, fontWeight: 700, color, padding: '2px 7px', borderRadius: 5,
        background: 'color-mix(in srgb, ' + color + ' 14%, var(--raised))', border: '1px solid color-mix(in srgb, ' + color + ' 30%, var(--line))', whiteSpace: 'nowrap' }}>
      {DISPUTE_LABEL[status] ?? status}
    </span>
  );
}

export function LeaveAppliedReviewTab({ runId, canConfigure }: { runId: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [items, setItems] = useState<PayrollRunReviewItemResponse[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [showReject, setShowReject] = useState(false);
  const [search, setSearch] = useState('');

  function reload() {
    payrollApi.listLeaveAppliedReview(runId, token).then(setItems).catch(() => showToast('error', 'Failed to load review items'));
  }

  useEffect(reload, [runId, token]);

  const filtered = items.filter(i => i.employeeName.toLowerCase().includes(search.trim().toLowerCase()));
  const pendingIds = filtered.filter(i => i.decision === 'PENDING').map(i => i.id);
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

  async function decide(decision: 'APPROVED' | 'REJECTED', reason?: string) {
    try {
      const result = await payrollApi.bulkDecideLeaveApplied(runId, { itemIds: Array.from(selected), decision, reason }, token);
      if (result.failed.length > 0) {
        showToast('error', `${result.succeededIds.length} decided, ${result.failed.length} failed: ${result.failed[0].reason}`);
      } else {
        showToast('success', `${result.succeededIds.length} item(s) ${decision.toLowerCase()}`);
      }
      setSelected(new Set());
      reload();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Decision failed');
    }
  }

  return (
    <div>
      <SectionHead
        title="Leave Applied Review"
        action={canConfigure && selected.size > 0 && (
          <div style={{ display: 'flex', gap: 10 }}>
            <BtnGhost onClick={() => setShowReject(true)}>Reject Selected ({selected.size})</BtnGhost>
            <BtnPrimary onClick={() => decide('APPROVED')}>Approve Selected ({selected.size})</BtnPrimary>
          </div>
        )}
      />
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              {canConfigure && <th style={thStyle}><input type="checkbox" checked={allPendingSelected} onChange={toggleAll} disabled={pendingIds.length === 0} /></th>}
              {['Employee', 'Date', 'Days', 'Leave Type', 'Decision'].map(h => <th key={h} style={thStyle}>{h}</th>)}
            </tr>
          </thead>
          <tbody>
            {filtered.map(item => (
              <tr key={item.id}>
                {canConfigure && (
                  <td style={tdStyle}>
                    {item.decision === 'PENDING' && <input type="checkbox" checked={selected.has(item.id)} onChange={() => toggleOne(item.id)} />}
                  </td>
                )}
                <td style={tdStyle}><PersonCellById userId={item.employeeId} name={item.employeeName} /></td>
                <td style={tdStyle}>{fmtDate(item.itemDate)}</td>
                <td style={tdStyle}>{item.days}</td>
                <td style={tdStyle}>{item.leaveTypeName ?? '-'}</td>
                <td style={tdStyle}><div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5 }}><StatusBadge status={item.decision} /><DisputeFlag status={item.disputeStatus} /></div></td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <EmptyRow cols={canConfigure ? 6 : 5} msg={items.length === 0 ? 'No leave-applied items for this run.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
      </div>
      {showReject && (
        <RejectReasonModal onClose={() => setShowReject(false)} onConfirm={(reason) => decide('REJECTED', reason)} />
      )}
    </div>
  );
}
