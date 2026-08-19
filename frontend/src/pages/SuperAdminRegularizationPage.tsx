import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, X } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  regularizationApi,
  type RegularizationRecord,
  type RegularizationFilters,
  type RegularizationStatus,
} from '../api/attendance';
import { employeesApi, type EmployeeRecord } from '../api/employees';
import { orgApi, type DepartmentRow } from '../api/org';

const panelStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const selectStyle: React.CSSProperties = { background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 10px', fontSize: 13, minWidth: 160 };
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '88vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.5)' };

const STATUS_COLOR: Record<string, string> = { PENDING: '#E0A93B', PARTIALLY_APPROVED: '#3B82C4', APPROVED: '#2FB67C', REJECTED: '#E4373D' };

function StatusPill({ status }: { status: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: STATUS_COLOR[status] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {status}
    </span>
  );
}

function fmtDateTime(dt: string | null) {
  if (!dt) return '—';
  return dt.replace('T', ' ').slice(0, 16);
}

function formatDuration(minutes: number | null): string {
  if (minutes == null) return '—';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** Audit-trail detail drawer for a single request — read-only, no approve/reject here. */
function AuditDetailModal({ request, onClose }: { request: RegularizationRecord; onClose: () => void }) {
  return (
    <div style={overlayStyle}>
      <div style={modalStyle}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
            {request.employeeName} — {request.attendanceDate}
          </span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontSize: 13 }}>
            <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Requested Check-In</div>{fmtDateTime(request.requestedCheckIn)}</div>
            <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Requested Check-Out</div>{fmtDateTime(request.requestedCheckOut)}</div>
            <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Total Hours</div>{formatDuration(request.totalMinutes)}</div>
            <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Department</div>{request.departmentName ?? '—'}</div>
            <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Assigned Approver</div>{request.assignedApproverName ?? '—'}</div>
            <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Status</div><StatusPill status={request.status} /></div>
          </div>
          <div>
            <div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', marginBottom: 4 }}>Reason</div>
            <div style={{ fontSize: 13, color: 'var(--txt)' }}>{request.reason}</div>
          </div>

          {/* Two-stage approval summary — shown once each stage has actually happened. */}
          {(request.approvedByName || request.finalApprovedByName) && (
            <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontSize: 13 }}>
              {request.approvedByName && (
                <>
                  <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Approved By (Manager)</div>{request.approvedByName}</div>
                  <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Approved At</div>{fmtDateTime(request.approvedAt)}</div>
                </>
              )}
              {request.finalApprovedByName && (
                <>
                  <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Final Approved By</div>{request.finalApprovedByName}</div>
                  <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Final Approved At</div>{fmtDateTime(request.finalApprovedAt)}</div>
                </>
              )}
            </div>
          )}

          <div>
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8 }}>
              Approval / Rejection History
            </div>
            {request.approvalHistory.length === 0 ? (
              <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>No decisions recorded yet — still pending.</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {request.approvalHistory.map((h, i) => (
                  <div key={i} style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '10px 12px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                      <span style={{ fontWeight: 700, color: h.actionType === 'APPROVED' ? '#2FB67C' : '#E4373D' }}>{h.actionType}</span>
                      <span style={{ color: 'var(--txt-dim)' }}>{fmtDateTime(h.actionDate)}</span>
                    </div>
                    <div style={{ fontSize: 13, color: 'var(--txt)', marginTop: 4 }}>
                      {h.actorName}{h.actorRole && <span style={{ color: 'var(--txt-dim)' }}> ({h.actorRole})</span>}
                    </div>
                    {h.comments && <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 4 }}>{h.comments}</div>}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

const STATUSES: RegularizationStatus[] = ['PENDING', 'PARTIALLY_APPROVED', 'APPROVED', 'REJECTED'];

export default function SuperAdminRegularizationPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();

  const [rows, setRows] = useState<RegularizationRecord[]>([]);
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [departments, setDepartments] = useState<DepartmentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<RegularizationRecord | null>(null);

  const [employeeUserId, setEmployeeUserId] = useState('');
  const [approverUserId, setApproverUserId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [month, setMonth] = useState('');
  const [status, setStatus] = useState<RegularizationStatus | ''>('');

  useEffect(() => {
    Promise.all([employeesApi.list(token), orgApi.listDepartments(token)])
      .then(([emp, dept]) => { setEmployees(emp); setDepartments(dept); })
      .catch(() => { /* filters degrade to text-only if this fails */ });
  }, [token]);

  const managers = useMemo(
    () => employees.filter(e => e.role === 'MANAGER' || e.role === 'HR_ADMIN' || e.role === 'SUPER_ADMIN'),
    [employees],
  );

  useEffect(() => {
    const filters: RegularizationFilters = {
      employeeUserId: employeeUserId || undefined,
      approverUserId: approverUserId || undefined,
      departmentId: departmentId || undefined,
      month: month || undefined,
      status: status || undefined,
    };
    setLoading(true);
    regularizationApi.all(filters, token)
      .then(setRows)
      .catch((err) => showToast('error', err instanceof Error ? err.message : 'Failed to load regularization requests'))
      .finally(() => setLoading(false));
  }, [employeeUserId, approverUserId, departmentId, month, status, token, showToast]);

  return (
    <div>
      <div style={{ marginBottom: 18 }}>
        <Link to="/attendance" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-dim)', textDecoration: 'none', marginBottom: 8 }}>
          <ArrowLeft size={13} /> Back to Attendance
        </Link>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>
          Attendance Regularization — All Requests
        </h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>
          Org-wide history across every employee, manager, and status, with full approval/rejection audit trail.
        </p>
      </div>

      <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
        <select style={selectStyle} value={employeeUserId} onChange={e => setEmployeeUserId(e.target.value)}>
          <option value="">All Employees</option>
          {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName}</option>)}
        </select>
        <select style={selectStyle} value={approverUserId} onChange={e => setApproverUserId(e.target.value)}>
          <option value="">All Managers</option>
          {managers.map(m => <option key={m.userId} value={m.userId}>{m.fullName}</option>)}
        </select>
        <select style={selectStyle} value={departmentId} onChange={e => setDepartmentId(e.target.value)}>
          <option value="">All Departments</option>
          {departments.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
        <input type="month" style={selectStyle} value={month} onChange={e => setMonth(e.target.value)} />
        <select style={selectStyle} value={status} onChange={e => setStatus(e.target.value as RegularizationStatus | '')}>
          <option value="">All Statuses</option>
          {STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      <div style={panelStyle}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : rows.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No requests match these filters</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Employee', 'Department', 'Date', 'Total Hours', 'Status', 'Assigned Approver', 'Reviewed By', 'Submitted'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {rows.map(r => (
                  <tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => setDetail(r)}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>
                      {r.employeeName}
                      <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeEmail}</div>
                    </td>
                    <td style={tdStyle}>{r.departmentName ?? '—'}</td>
                    <td style={tdStyle}>{r.attendanceDate}</td>
                    <td style={tdStyle}>{formatDuration(r.totalMinutes)}</td>
                    <td style={tdStyle}><StatusPill status={r.status} /></td>
                    <td style={tdStyle}>{r.assignedApproverName ?? '—'}</td>
                    <td style={tdStyle}>{r.reviewedByName ?? '—'}</td>
                    <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDateTime(r.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {detail && <AuditDetailModal request={detail} onClose={() => setDetail(null)} />}
    </div>
  );
}
