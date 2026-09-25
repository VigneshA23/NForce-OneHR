import { useMyDocumentsData } from '../../documents/useMyDocumentsData';
import { MyDocumentsSection } from '../../documents/MyDocumentsSection';

// Thin wrapper — all document list/upload/status behavior lives in MyDocumentsSection, the same
// component DocumentsPage's "My Documents" tab renders, so there is one implementation, not two.
export function DocumentsTab({ token }: { token: string }) {
  const { required, myDocs, loading, setRequired, setMyDocs } = useMyDocumentsData(token);

  if (loading) return <div style={{ padding: 20, color: 'var(--txt-mut)', fontSize: 13 }}>Loading documents…</div>;

  return (
    <MyDocumentsSection
      required={required} myDocs={myDocs}
      setRequired={setRequired} setMyDocs={setMyDocs}
    />
  );
}
