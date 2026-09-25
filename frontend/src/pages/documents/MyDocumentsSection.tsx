import { useState } from 'react';
import { Upload, AlertTriangle, Search } from 'lucide-react';
import { type EmployeeDocument, type RequiredDocument } from '../../api/documents';
import { card, thS, tdS, StatusBadge, UploadModal, ViewButton, WithdrawButton, bucketRequiredDocuments } from './shared';

type Section = 'verified' | 'pending' | 'rejected' | 'missing';

// The actual "My Documents" document list — sub-tabs (Pending Review / Verified / Not
// Submitted), search, table, upload/re-upload/view actions. Shared verbatim by DocumentsPage
// (the standalone "My Documents & Policies" page) and the My Profile Documents tab, so there is
// exactly one implementation of document upload/status behavior, not two. Data (required/myDocs)
// is owned by the caller via useMyDocumentsData — this component only renders it —
// so each page keeps a single fetch and its own KPI tiles (if any) never go stale after an
// upload here.
export function MyDocumentsSection({
  required, myDocs, setRequired, setMyDocs, search: controlledSearch, onSearchChange,
}: {
  required: RequiredDocument[];
  myDocs: EmployeeDocument[];
  setRequired: React.Dispatch<React.SetStateAction<RequiredDocument[]>>;
  setMyDocs: React.Dispatch<React.SetStateAction<EmployeeDocument[]>>;
  // Optional controlled search — DocumentsPage shares one search box across its Docs/
  // Announcements tabs and passes these in; an uncontrolled caller (e.g. the My Profile tab)
  // can omit them and get its own independent search state.
  search?: string;
  onSearchChange?: (v: string) => void;
}) {
  const [section, setSection] = useState<Section>('pending');
  const [localSearch, setLocalSearch] = useState('');
  const search = controlledSearch ?? localSearch;
  const setSearch = onSearchChange ?? setLocalSearch;
  const [uploadTarget, setUploadTarget] = useState<{ type: RequiredDocument; existing: EmployeeDocument | null } | null>(null);

  function docForType(typeId: number): EmployeeDocument | null {
    return myDocs.find(d => d.documentTypeId === typeId) ?? null;
  }

  // Mirrors UploadModal's onUploaded — withdrawing frees the (employee, documentType) slot, so
  // the type reverts to "Not Submitted" the same way it looks before any upload ever happened.
  function handleWithdrawn(documentTypeId: number) {
    setMyDocs(prev => prev.filter(d => d.documentTypeId !== documentTypeId));
    setRequired(prev => prev.map(r => r.documentTypeId === documentTypeId
      ? { ...r, uploaded: false, status: null } : r));
  }

  const { verified, pending, rejected, missing } = bucketRequiredDocuments(required);

  const secStyle = (s: Section): React.CSSProperties => ({
    padding: '6px 16px', borderRadius: 5, cursor: 'pointer', fontWeight: 600, fontSize: 12,
    background: section === s ? 'var(--txt)' : 'var(--shell)',
    color: section === s ? 'var(--panel)' : 'var(--txt-dim)',
    border: '1px solid var(--line)',
  });

  const sectionDocs = section === 'verified' ? verified : section === 'pending' ? pending : section === 'rejected' ? rejected : missing;
  const q = search.trim().toLowerCase();
  const filteredDocs = q ? sectionDocs.filter(r => r.documentTypeName.toLowerCase().includes(q)) : sectionDocs;

  return (
    <>
      <div className="nf-doc-tabs" style={{ display: 'flex', gap: 6, marginBottom: 14 }}>
        <button className="nf-doc-tab-btn" style={secStyle('pending')} onClick={() => setSection('pending')}>
          <span className="nf-doc-tab-label">Pending Review</span> {pending.length > 0 && <span style={{ marginLeft: 4, background: '#eab308', color: '#000', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>{pending.length}</span>}
        </button>
        <button className="nf-doc-tab-btn" style={secStyle('verified')} onClick={() => setSection('verified')}>
          <span className="nf-doc-tab-label">Verified</span> {verified.length > 0 && <span style={{ marginLeft: 4, background: '#22c55e', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>{verified.length}</span>}
        </button>
        <button className="nf-doc-tab-btn" style={secStyle('rejected')} onClick={() => setSection('rejected')}>
          <span className="nf-doc-tab-label">Rejected</span> {rejected.length > 0 && <span style={{ marginLeft: 4, background: '#ef4444', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>{rejected.length}</span>}
        </button>
        <button className="nf-doc-tab-btn" style={secStyle('missing')} onClick={() => setSection('missing')}>
          <span className="nf-doc-tab-label">Not Submitted</span> {missing.length > 0 && <span style={{ marginLeft: 4, background: '#ef4444', color: '#fff', borderRadius: 10, fontSize: 10, fontWeight: 700, padding: '1px 6px' }}>{missing.length}</span>}
        </button>
      </div>

      <div className="nf-search-full-mobile" style={{ position: 'relative', marginBottom: 14 }}>
        <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
        <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search documents…"
          className="nf-search-full-mobile-input"
          style={{ paddingLeft: 28, padding: '6px 10px 6px 28px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, color: 'var(--txt)', fontSize: 12, width: 200, outline: 'none' }} />
      </div>

      <div style={card}>
        <div className="nf-doc-table-scroll">
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={thS}>Document</th>
                <th style={thS}>Status</th>
                <th style={thS}>Expiry</th>
                <th style={thS}>Rejection Reason</th>
                <th style={thS}>Action</th>
              </tr>
            </thead>
            <tbody>
              {filteredDocs.length === 0 ? (
                <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center', padding: 28 }}>
                  {q ? 'No results.' : section === 'verified' ? 'No verified documents yet.' : section === 'pending' ? 'No documents pending review.' : section === 'rejected' ? 'No rejected documents.' : 'All required documents submitted!'}
                </td></tr>
              ) : filteredDocs.map(r => {
                const doc = docForType(r.documentTypeId);
                return (
                  <tr key={r.documentTypeId}>
                    <td style={tdS}>
                      <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{r.documentTypeName}</div>
                      {r.requiresVerification && <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Requires HR verification</div>}
                    </td>
                    <td style={tdS}><StatusBadge status={r.status} /></td>
                    <td style={tdS}>
                      {doc?.expiryDate ? (
                        <span style={{ color: r.expiringSoon ? '#eab308' : 'var(--txt-mut)', fontSize: 13 }}>
                          {r.expiringSoon && <AlertTriangle size={12} style={{ marginRight: 4 }} />}
                          {new Date(doc.expiryDate).toLocaleDateString()}
                        </span>
                      ) : '—'}
                    </td>
                    <td style={tdS}>
                      {doc?.rejectionReason
                        ? <span style={{ color: '#ef4444', fontSize: 12 }}>{doc.rejectionReason}</span>
                        : '—'}
                    </td>
                    <td style={tdS}>
                      <div style={{ display: 'flex', gap: 8 }}>
                        {r.status === 'PENDING_VERIFICATION' ? (
                          doc && <WithdrawButton doc={doc} onWithdrawn={handleWithdrawn} />
                        ) : (
                          <button onClick={() => setUploadTarget({ type: r, existing: doc })}
                            style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px', background: '#A01418', border: 'none', borderRadius: 5, color: '#fff', cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
                            <Upload size={12} /> {doc ? 'Update' : 'Upload'}
                          </button>
                        )}
                        {doc && <ViewButton docId={doc.id} />}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {uploadTarget && (
        <UploadModal
          key={`${uploadTarget.type.documentTypeId}-${Date.now()}`}
          docType={uploadTarget.type}
          existing={uploadTarget.existing}
          onClose={() => setUploadTarget(null)}
          onUploaded={doc => {
            setMyDocs(prev => {
              const idx = prev.findIndex(d => d.documentTypeId === doc.documentTypeId);
              return idx >= 0 ? prev.map((d, i) => i === idx ? doc : d) : [doc, ...prev];
            });
            setRequired(prev => prev.map(r => r.documentTypeId === doc.documentTypeId
              ? { ...r, uploaded: true, status: doc.status } : r));
          }}
        />
      )}
    </>
  );
}
