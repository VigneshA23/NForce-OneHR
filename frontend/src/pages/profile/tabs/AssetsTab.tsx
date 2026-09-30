import { useEffect, useState } from 'react';
import { Paperclip, Plus } from 'lucide-react';
import { useToast } from '../../../context/ToastContext';
import { assetsApi, type AssetAssignmentResponse, type AssetRequestResponse } from '../../../api/assets';
import { expensesApi, type ExpenseCategory, type ExpenseClaimResponse } from '../../../api/expenses';
import { KebabMenu } from '../../../components/KebabMenu';
import { ReceiptViewerModal } from '../../../components/expenses/ReceiptViewerModal';
import { useAuthStore } from '../../../store/authStore';
import { toShellRole } from '../../../lib/nav.config';
import { panelStyle, thStyle, tdStyle, StatusBadge, fmtDate, fmtCurrency, EmptyRow } from '../../assets/shared';
import { useEmployeeAssetsData } from '../../assets/useEmployeeAssetsData';
import { RequestAssetModal } from '../../assets/RequestAssetModal';
import { ManagerView, HRView, SubmitExpenseModal } from '../../AssetsExpensesPage';
import { SectionHeader } from '../shared';

function AssetDetailsModal({ asset, onClose }: { asset: AssetAssignmentResponse; onClose: () => void }) {
  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600 }} onClick={onClose}>
      <div onClick={e => e.stopPropagation()} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 24, width: 420, maxWidth: '94vw' }}>
        <h3 style={{ margin: '0 0 16px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>Asset Details</h3>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px 20px', marginBottom: 20 }}>
          <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Asset Tag</div><div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{asset.assetTag ?? '—'}</div></div>
          <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Category</div><div style={{ fontSize: 13, color: 'var(--txt)' }}>{asset.categoryName ?? '—'}</div></div>
          <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Make / Model</div><div style={{ fontSize: 13, color: 'var(--txt)' }}>{[asset.brand, asset.model].filter(Boolean).join(' ') || '—'}</div></div>
          <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Condition</div><div>{asset.condition ? <StatusBadge status={asset.condition} /> : '—'}</div></div>
          <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Allocated Date</div><div style={{ fontSize: 13, color: 'var(--txt)' }}>{fmtDate(asset.effectiveFrom)}</div></div>
          <div><div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Acknowledged</div><div style={{ fontSize: 13, color: 'var(--txt)' }}>{asset.acknowledgedAt ? fmtDate(asset.acknowledgedAt) : 'Pending'}</div></div>
        </div>
        <button onClick={onClose} style={{ width: '100%', padding: '9px 0', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, color: 'var(--txt-mut)', cursor: 'pointer', fontSize: 13 }}>Close</button>
      </div>
    </div>
  );
}

function RequestTrackingModal({ request, onClose }: { request: AssetRequestResponse; onClose: () => void }) {
  const steps = [
    { label: 'Submitted', at: request.createdAt, done: true },
    { label: request.status === 'REJECTED' ? 'Rejected' : 'Manager Decision', at: request.managerDecidedAt, done: !!request.managerDecidedAt },
    { label: 'Fulfilled', at: request.fulfilledAt, done: !!request.fulfilledAt },
  ];
  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600 }} onClick={onClose}>
      <div onClick={e => e.stopPropagation()} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 24, width: 420, maxWidth: '94vw' }}>
        <h3 style={{ margin: '0 0 4px', fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>Track Fulfillment</h3>
        <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 18 }}>{request.categoryName} · Request #{request.id}</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14, marginBottom: 20 }}>
          {steps.map((s, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <div style={{ width: 10, height: 10, borderRadius: '50%', background: s.done ? 'var(--ok)' : 'var(--line2)', flexShrink: 0 }} />
              <div style={{ fontSize: 13, color: s.done ? 'var(--txt)' : 'var(--txt-dim)' }}>{s.label}{s.at ? ` — ${fmtDate(s.at)}` : ''}</div>
            </div>
          ))}
          {request.rejectionReason && (
            <div style={{ fontSize: 12, color: 'var(--risk)' }}>Reason: {request.rejectionReason}</div>
          )}
        </div>
        <button onClick={onClose} style={{ width: '100%', padding: '9px 0', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, color: 'var(--txt-mut)', cursor: 'pointer', fontSize: 13 }}>Close</button>
      </div>
    </div>
  );
}

/**
 * Role-aware entry point: mirrors the standalone Assets & Expenses page's own role branch
 * (AssetsExpensesPage.tsx), reusing its exported Manager/HR views as-is so My Profile always
 * shows the same sections a role is entitled to see there. The Employee case keeps this tab's
 * own layout/actions (Withdraw, View Details, Track Fulfillment, Request Replacement) since the
 * standalone EmployeeView doesn't offer them — only the missing "My Expense Claims" section is
 * added below, reusing the same expensesApi + SubmitExpenseModal used by that view.
 */
export function AssetsTab({ token }: { token: string }) {
  const user = useAuthStore(s => s.user);
  const role = toShellRole(user?.role);

  if (role === 'Manager') return <ManagerView token={token} hideTiles />;
  if (role === 'HR Admin' || role === 'Super Admin') return <HRView token={token} hideTiles />;
  return <EmployeeAssetsTab token={token} />;
}

function EmployeeAssetsTab({ token }: { token: string }) {
  const { showToast } = useToast();
  const { assignments, requests, categories, reload, setRequests } = useEmployeeAssetsData(token);
  const [subTab, setSubTab] = useState<'assets' | 'requests' | 'claims'>('assets');
  const [viewingAsset, setViewingAsset] = useState<AssetAssignmentResponse | null>(null);
  const [trackingRequest, setTrackingRequest] = useState<AssetRequestResponse | null>(null);
  const [replacementFor, setReplacementFor] = useState<AssetAssignmentResponse | null>(null);
  const [showRequestModal, setShowRequestModal] = useState(false);
  const [withdrawing, setWithdrawing] = useState<number | null>(null);

  const [claims, setClaims] = useState<ExpenseClaimResponse[]>([]);
  const [expCategories, setExpCategories] = useState<ExpenseCategory[]>([]);
  const [claimStatusFilter, setClaimStatusFilter] = useState('');
  const [claimCatFilter, setClaimCatFilter] = useState('');
  const [showExpModal, setShowExpModal] = useState(false);
  const [viewingReceiptClaimId, setViewingReceiptClaimId] = useState<string | null>(null);

  function reloadClaims() {
    expensesApi.myClaims(token).then(setClaims).catch(() => {});
  }

  useEffect(() => {
    reloadClaims();
    expensesApi.categories(token).then(setExpCategories).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const filteredClaims = claims
    .filter(c => !claimStatusFilter || c.status === claimStatusFilter)
    .filter(c => !claimCatFilter || String(c.categoryId) === claimCatFilter);
  const claimStatusOptions = Array.from(new Set(claims.map(c => c.status)));

  async function handleWithdraw(id: number) {
    setWithdrawing(id);
    try {
      const updated = await assetsApi.withdrawRequest(id, token);
      setRequests(prev => prev.map(r => r.id === updated.id ? updated : r));
      showToast('success', 'Request withdrawn');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Withdraw failed');
    } finally {
      setWithdrawing(null);
    }
  }

  const subTabStyle = (t: typeof subTab): React.CSSProperties => ({
    background: t === subTab ? 'rgba(177,17,22,.12)' : 'var(--raised)',
    border: `1px solid ${t === subTab ? 'var(--brand)' : 'var(--line2)'}`,
    color: t === subTab ? 'var(--brand-bright)' : 'var(--txt-mut)',
    fontSize: 12, fontWeight: 600, padding: '5px 14px', borderRadius: 20, cursor: 'pointer',
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', gap: 8 }}>
        <button style={subTabStyle('assets')} onClick={() => setSubTab('assets')}>My Assets</button>
        <button style={subTabStyle('requests')} onClick={() => setSubTab('requests')}>My Asset Requests</button>
        <button style={subTabStyle('claims')} onClick={() => setSubTab('claims')}>My Expense Claims</button>
      </div>

      {subTab === 'assets' && (
        <div style={panelStyle}>
          <div style={{ padding: '14px 18px 0' }}><SectionHeader title={`My Assets (${assignments.length})`} /></div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Asset Tag', 'Type', 'Make / Model', 'Allocated Date', 'Condition', 'Status', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {assignments.length === 0 ? <EmptyRow cols={7} msg="No assets assigned." /> : assignments.map(a => (
                  <tr key={a.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{a.assetTag ?? '—'}</td>
                    <td style={tdStyle}>{a.categoryName ?? '—'}</td>
                    <td style={tdStyle}>{[a.brand, a.model].filter(Boolean).join(' ') || '—'}</td>
                    <td style={tdStyle}>{fmtDate(a.effectiveFrom)}</td>
                    <td style={tdStyle}>{a.condition ? <StatusBadge status={a.condition} /> : '—'}</td>
                    <td style={tdStyle}>{a.effectiveTo ? <StatusBadge status="RETIRED" /> : <StatusBadge status="ASSIGNED" />}</td>
                    <td style={{ ...tdStyle, padding: '8px 12px', textAlign: 'right' }}>
                      <KebabMenu items={[
                        { label: 'View Details', onClick: () => setViewingAsset(a) },
                        { label: 'Request Replacement', onClick: () => setReplacementFor(a) },
                      ]} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {subTab === 'requests' && (
        <div style={panelStyle}>
          <div style={{ padding: '14px 18px 0', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <SectionHeader title={`My Asset Requests (${requests.length})`} />
            <button onClick={() => setShowRequestModal(true)}
              style={{ marginBottom: 14, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
              + New Asset Request
            </button>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Request ID', 'Asset Type Requested', 'Requested Date', 'Required By Date', 'Status', 'Approver', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {requests.length === 0 ? <EmptyRow cols={7} msg="No asset requests yet." /> : requests.map(r => (
                  <tr key={r.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>#{r.id}</td>
                    <td style={tdStyle}>{r.categoryName}</td>
                    <td style={tdStyle}>{fmtDate(r.createdAt)}</td>
                    <td style={tdStyle}>{fmtDate(r.requiredByDate)}</td>
                    <td style={tdStyle}><StatusBadge status={r.status} /></td>
                    {/* Approver falls back to the employee's own reporting manager pre-decision —
                        AssetRequestResponse has no distinct "assigned approver" field until a
                        decision is made, only who *did* decide. */}
                    <td style={tdStyle}>{r.managerDecidedByName ?? '—'}</td>
                    <td style={{ ...tdStyle, padding: '8px 12px', textAlign: 'right' }}>
                      <KebabMenu items={[
                        { label: 'View', onClick: () => setTrackingRequest(r) },
                        { label: 'Track Fulfillment', onClick: () => setTrackingRequest(r) },
                        ...(r.status === 'PENDING' ? [{
                          label: withdrawing === r.id ? 'Withdrawing…' : 'Withdraw',
                          onClick: () => handleWithdraw(r.id),
                          danger: true,
                          dividerBefore: true,
                        }] : []),
                      ]} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {subTab === 'claims' && (
        <div style={panelStyle}>
          <div style={{ padding: '14px 18px 0', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
            <SectionHeader title={`My Expense Claims (${claims.length})`} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
              <select value={claimStatusFilter} onChange={e => setClaimStatusFilter(e.target.value)} style={{ background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 12, color: 'var(--txt)' }}>
                <option value="">All Statuses</option>
                {claimStatusOptions.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
              </select>
              <select value={claimCatFilter} onChange={e => setClaimCatFilter(e.target.value)} style={{ background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 12, color: 'var(--txt)' }}>
                <option value="">All Categories</option>
                {expCategories.map(c => <option key={c.id} value={String(c.id)}>{c.name}</option>)}
              </select>
              <button onClick={() => setShowExpModal(true)} style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 14px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
                <Plus size={13} /> Submit Expense
              </button>
            </div>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Category', 'Amount', 'Expense Date', 'Purpose', 'Status', 'Submitted', 'Receipt'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {filteredClaims.length === 0 ? <EmptyRow cols={7} msg="No expense claims yet." /> : filteredClaims.map(c => (
                  <tr key={c.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{c.categoryName}</td>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{fmtCurrency(c.amount)}</td>
                    <td style={tdStyle}>{fmtDate(c.expenseDate)}</td>
                    <td style={{ ...tdStyle, maxWidth: 180 }}><div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }} title={c.businessPurpose}>{c.businessPurpose}</div></td>
                    <td style={tdStyle}>
                      <StatusBadge status={c.status} />
                      {(c.managerRejectionReason || c.finalRejectionReason) && (
                        <div style={{ fontSize: 10, color: '#E4373D', marginTop: 2 }}>Reason: {c.managerRejectionReason ?? c.finalRejectionReason}</div>
                      )}
                    </td>
                    <td style={tdStyle}>{fmtDate(c.createdAt)}</td>
                    <td style={tdStyle}>
                      <button
                        onClick={() => setViewingReceiptClaimId(c.id)}
                        style={{ display: 'flex', alignItems: 'center', gap: 5, background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 10px', fontSize: 11.5, color: 'var(--brand)', cursor: 'pointer' }}
                      >
                        <Paperclip size={12} /> View Receipt
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {viewingAsset && <AssetDetailsModal asset={viewingAsset} onClose={() => setViewingAsset(null)} />}
      {trackingRequest && <RequestTrackingModal request={trackingRequest} onClose={() => setTrackingRequest(null)} />}
      {replacementFor && (
        <RequestAssetModal
          categories={categories}
          token={token}
          onClose={() => setReplacementFor(null)}
          initialCategoryId={categories.find(c => c.name === replacementFor.categoryName)?.id}
          initialReason={`Replacement request for ${replacementFor.assetTag ?? 'assigned asset'} (${replacementFor.categoryName ?? ''})`}
          onCreated={() => { reload(); showToast('success', 'Replacement request submitted'); }}
        />
      )}
      {showRequestModal && (
        <RequestAssetModal
          categories={categories}
          token={token}
          onClose={() => setShowRequestModal(false)}
          onCreated={() => { reload(); showToast('success', 'Asset request submitted — pending manager approval'); }}
        />
      )}
      {showExpModal && (
        <SubmitExpenseModal
          categories={expCategories}
          token={token}
          onClose={() => setShowExpModal(false)}
          onCreated={c => { setClaims(prev => [c, ...prev]); showToast('success', 'Expense claim submitted'); }}
        />
      )}
      {viewingReceiptClaimId && (
        <ReceiptViewerModal
          claimId={viewingReceiptClaimId}
          token={token}
          onClose={() => setViewingReceiptClaimId(null)}
        />
      )}
    </div>
  );
}
