import { useState } from 'react';
import { Upload } from 'lucide-react';
import { type RequiredDocument, type DocumentType, type EmployeeDocument } from '../../../api/documents';
import { card, thS, tdS, StatusBadge, UploadModal, ViewButton } from '../../documents/shared';
import { useMyDocumentsData } from '../../documents/useMyDocumentsData';
import { DOC_SUB_TABS, categorizeDocument, type DocSubTabKey } from './documentsCategoryMap';

export function DocumentsTab({ token }: { token: string }) {
  const { required, myDocs, loading, setRequired, setMyDocs } = useMyDocumentsData(token);
  const [subTab, setSubTab] = useState<DocSubTabKey>('identity');
  const [uploadTarget, setUploadTarget] = useState<{ type: RequiredDocument | DocumentType; existing: EmployeeDocument | null } | null>(null);

  function docForType(typeId: number): EmployeeDocument | null {
    return myDocs.find(d => d.documentTypeId === typeId) ?? null;
  }

  if (loading) return <div style={{ padding: 20, color: 'var(--txt-mut)', fontSize: 13 }}>Loading documents…</div>;

  const bucketed = required.filter(r => categorizeDocument(r.documentTypeName) === subTab);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {DOC_SUB_TABS.map(t => (
          <button key={t.key} onClick={() => setSubTab(t.key)} style={{
            background: subTab === t.key ? 'rgba(177,17,22,.12)' : 'var(--raised)',
            border: `1px solid ${subTab === t.key ? 'var(--brand)' : 'var(--line2)'}`,
            color: subTab === t.key ? 'var(--brand-bright)' : 'var(--txt-mut)',
            fontSize: 12, fontWeight: 600, padding: '5px 14px', borderRadius: 20, cursor: 'pointer',
          }}>
            {t.label}
          </button>
        ))}
      </div>

      {subTab === 'payslips' ? (
        <div style={card}>
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>
            Payslips will appear here once available.
          </div>
        </div>
      ) : (
        <div style={card}>
          <div style={{ overflowX: 'auto' }}>
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
                {bucketed.length === 0 ? (
                  <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center', padding: 28 }}>No documents in this category.</td></tr>
                ) : bucketed.map(r => {
                  const doc = docForType(r.documentTypeId);
                  return (
                    <tr key={r.documentTypeId}>
                      <td style={tdS}>
                        <div style={{ fontWeight: 600, color: 'var(--txt)', fontSize: 13 }}>{r.documentTypeName}</div>
                        {r.requiresVerification && <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Requires HR verification</div>}
                      </td>
                      <td style={tdS}><StatusBadge status={r.status} /></td>
                      <td style={tdS}>{doc?.expiryDate ? new Date(doc.expiryDate).toLocaleDateString() : '—'}</td>
                      <td style={tdS}>{doc?.rejectionReason ? <span style={{ color: '#ef4444', fontSize: 12 }}>{doc.rejectionReason}</span> : '—'}</td>
                      <td style={tdS}>
                        <div style={{ display: 'flex', gap: 8 }}>
                          <button onClick={() => setUploadTarget({ type: r, existing: doc })}
                            style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px', background: '#A01418', border: 'none', borderRadius: 5, color: '#fff', cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
                            <Upload size={12} /> {doc ? 'Re-upload' : 'Upload'}
                          </button>
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
      )}

      {uploadTarget && (
        <UploadModal
          key={`${(uploadTarget.type as any).id ?? (uploadTarget.type as any).documentTypeId}-${Date.now()}`}
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
    </div>
  );
}
