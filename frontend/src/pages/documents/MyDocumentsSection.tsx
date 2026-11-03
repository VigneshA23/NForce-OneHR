import { useState } from 'react';
import { Upload, AlertTriangle, Search, FileText } from 'lucide-react';
import { type EmployeeDocument, type RequiredDocument } from '../../api/documents';
import { card, thS, tdS, StatusBadge, UploadModal, ViewButton, WithdrawButton, bucketRequiredDocuments } from './shared';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { TabBar } from '../../components/TabBar';

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

  const sectionDocs = section === 'verified' ? verified : section === 'pending' ? pending : section === 'rejected' ? rejected : missing;
  const q = search.trim().toLowerCase();
  const filteredDocs = q ? sectionDocs.filter(r => r.documentTypeName.toLowerCase().includes(q)) : sectionDocs;
  const docsPaged = usePaged(filteredDocs, [q, section]);

  return (
    <>
      <TabBar
        className="nf-pilltabs"
        size="sm"
        ariaLabel="Document status"
        style={{ marginBottom: 14 }}
        active={section}
        onChange={setSection}
        tabs={[
          { key: 'pending', label: 'Pending Review', count: pending.length },
          { key: 'verified', label: 'Verified', count: verified.length },
          { key: 'rejected', label: 'Rejected', count: rejected.length },
          { key: 'missing', label: 'Not Submitted', count: missing.length },
        ]}
      />

      <div className="nf-filterbar nf-search-full-mobile">
        <label className="nf-filter-search nf-search-full-mobile-input">
          <Search size={15} />
          <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search documents…" />
        </label>
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
              ) : docsPaged.pageRows.map(r => {
                const doc = docForType(r.documentTypeId);
                return (
                  <tr key={r.documentTypeId}>
                    <td style={tdS}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                        <span className="nf-opp-ico"><FileText size={17} /></span>
                        <div>
                          <div style={{ fontWeight: 700, color: 'var(--txt)', fontSize: 14 }}>{r.documentTypeName}</div>
                          {r.requiresVerification && <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2 }}>Requires HR verification</div>}
                        </div>
                      </div>
                    </td>
                    <td style={tdS}><StatusBadge status={r.status} /></td>
                    <td style={tdS}>
                      {doc?.expiryDate ? (
                        <span style={{ color: r.expiringSoon ? 'var(--warn)' : 'var(--txt-mut)', fontSize: 13 }}>
                          {r.expiringSoon && <AlertTriangle size={12} style={{ marginRight: 4 }} />}
                          {new Date(doc.expiryDate).toLocaleDateString()}
                        </span>
                      ) : '–'}
                    </td>
                    <td style={tdS}>
                      {doc?.rejectionReason
                        ? <span style={{ color: 'var(--risk)', fontSize: 12 }}>{doc.rejectionReason}</span>
                        : '–'}
                    </td>
                    <td style={tdS}>
                      <div style={{ display: 'flex', gap: 8 }}>
                        {r.status === 'PENDING_VERIFICATION' ? (
                          doc && <WithdrawButton doc={doc} onWithdrawn={handleWithdrawn} />
                        ) : (
                          <button type="button" className="nf-act-btn nf-act-btn--primary" onClick={() => setUploadTarget({ type: r, existing: doc })}>
                            <Upload size={13} /> {doc ? 'Update' : 'Upload'}
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
        <TablePagination {...docsPaged.pagerProps} noun="documents" />
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
