import { useEffect, useState } from 'react';
import { Paperclip, Plus, Package, ClipboardList, Wallet, Check } from 'lucide-react';
import { useToast } from '../../../context/ToastContext';
import { assetsApi, type AssetAssignmentResponse, type AssetRequestResponse } from '../../../api/assets';
import { expensesApi, type ExpenseCategory, type ExpenseClaimResponse } from '../../../api/expenses';
import { KebabMenu } from '../../../components/KebabMenu';
import { ReceiptViewerModal } from '../../../components/expenses/ReceiptViewerModal';
import { useAuthStore } from '../../../store/authStore';
import { assetsExpensesView } from '../../../lib/permissions';
import { panelStyle, thStyle, tdStyle, StatusBadge, fmtDate, fmtCurrency, EmptyRow, Modal } from '../../assets/shared';
import { useEmployeeAssetsData } from '../../assets/useEmployeeAssetsData';
import { RequestAssetModal } from '../../assets/RequestAssetModal';
import { ManagerView, HRView, SubmitExpenseModal } from '../../AssetsExpensesPage';
import { SectionHeader } from '../shared';
import { TabBar } from '../../../components/TabBar';

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="nf-ps-field">
      <div className="nf-ps-label">{label}</div>
      <div className="nf-ps-value">{children}</div>
    </div>
  );
}

function AssetDetailsModal({ asset, onClose }: { asset: AssetAssignmentResponse; onClose: () => void }) {
  return (
    <Modal title="Asset Details" onClose={onClose}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }} className="nf-grid-2col-collapse">
        <Fact label="Asset Tag">{asset.assetTag ?? '-'}</Fact>
        <Fact label="Category">{asset.categoryName ?? '-'}</Fact>
        <Fact label="Make / Model">{[asset.brand, asset.model].filter(Boolean).join(' ') || '-'}</Fact>
        <Fact label="Condition">{asset.condition ? <StatusBadge status={asset.condition} /> : '-'}</Fact>
        <Fact label="Allocated Date">{fmtDate(asset.effectiveFrom)}</Fact>
        <Fact label="Acknowledged">{asset.acknowledgedAt ? fmtDate(asset.acknowledgedAt) : 'Pending'}</Fact>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 16 }}>
        <button type="button" onClick={onClose} style={{ padding: '9px 22px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 11, color: 'var(--txt)', cursor: 'pointer', fontSize: 13, fontWeight: 700 }}>Close</button>
      </div>
    </Modal>
  );
}

function RequestTrackingModal({ request, onClose }: { request: AssetRequestResponse; onClose: () => void }) {
  const steps = [
    { label: 'Submitted', at: request.createdAt, done: true },
    { label: request.status === 'REJECTED' ? 'Rejected' : 'Manager Decision', at: request.managerDecidedAt, done: !!request.managerDecidedAt },
    { label: 'Fulfilled', at: request.fulfilledAt, done: !!request.fulfilledAt },
  ];
  return (
    <Modal title="Track Fulfillment" onClose={onClose}>
      <div className="nf-ps-field" style={{ marginBottom: 14 }}>
        <div className="nf-ps-label">Request</div>
        <div className="nf-ps-value">{request.categoryName} · #{request.id}</div>
      </div>
      <div className="nf-trk">
        {steps.map((s, i) => (
          <div key={i} className={`nf-trk-step${s.done ? ' is-done' : ''}`}>
            <span className="nf-trk-dot">{s.done && <Check size={11} />}</span>
            <div>
              <div className="nf-trk-label">{s.label}</div>
              <div className="nf-trk-at">{s.at ? fmtDate(s.at) : 'Waiting'}</div>
            </div>
          </div>
        ))}
        {request.rejectionReason && (
          <div style={{ fontSize: 12.5, color: 'var(--risk)', marginTop: 4 }}>Reason: {request.rejectionReason}</div>
        )}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 16 }}>
        <button type="button" onClick={onClose} style={{ padding: '9px 22px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 11, color: 'var(--txt)', cursor: 'pointer', fontSize: 13, fontWeight: 700 }}>Close</button>
      </div>
    </Modal>
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
  const view = assetsExpensesView(useAuthStore(s => s.permissions), user?.role);

  if (view === 'manager') return <ManagerView token={token} hideTiles />;
  if (view === 'hr') return <HRView token={token} hideTiles />;
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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <TabBar
        size="sm"
        ariaLabel="Assets and expenses"
        active={subTab}
        onChange={setSubTab}
        tabs={[
          { key: 'assets', label: 'My Assets', count: assignments.length },
          { key: 'requests', label: 'My Asset Requests', count: requests.length },
          { key: 'claims', label: 'My Expense Claims', count: claims.length },
        ]}
      />

      {subTab === 'assets' && (
        <div style={panelStyle}>
          <div style={{ padding: '14px 18px 0' }}><SectionHeader title={`My Assets (${assignments.length})`} icon={Package} /></div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>{['Asset Tag', 'Type', 'Make / Model', 'Allocated Date', 'Condition', 'Status', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {assignments.length === 0 ? <EmptyRow cols={7} msg="No assets assigned." /> : assignments.map(a => (
                  <tr key={a.id}>
                    <td style={{ ...tdStyle, fontWeight: 600, color: 'var(--txt)' }}>{a.assetTag ?? '–'}</td>
                    <td style={tdStyle}>{a.categoryName ?? '–'}</td>
                    <td style={tdStyle}>{[a.brand, a.model].filter(Boolean).join(' ') || '–'}</td>
                    <td style={tdStyle}>{fmtDate(a.effectiveFrom)}</td>
                    <td style={tdStyle}>{a.condition ? <StatusBadge status={a.condition} /> : '–'}</td>
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
            <SectionHeader title={`My Asset Requests (${requests.length})`} icon={ClipboardList} />
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
                    <td style={tdStyle}>{r.managerDecidedByName ?? '–'}</td>
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
            <SectionHeader title={`My Expense Claims (${claims.length})`} icon={Wallet} />
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
                        <div style={{ fontSize: 10, color: 'var(--risk)', marginTop: 2 }}>Reason: {c.managerRejectionReason ?? c.finalRejectionReason}</div>
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
          onCreated={() => { reload(); showToast('success', 'Asset request submitted - pending manager approval'); }}
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
