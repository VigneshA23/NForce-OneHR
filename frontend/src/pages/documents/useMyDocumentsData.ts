import { useEffect, useState } from 'react';
import {
  myDocuments, myRequiredDocuments, listActiveDocTypes,
  type EmployeeDocument, type RequiredDocument, type DocumentType,
} from '../../api/documents';

// Shared "my documents" data (required + uploaded + active types) — used by both DocumentsPage's
// "My Documents" tab and the My Profile Documents tab, so the two never drift out of sync or
// duplicate the fetch logic. Deliberately excludes policies/announcements, which are specific to
// the standalone DocumentsPage and out of scope for the profile embed.
export function useMyDocumentsData(token: string) {
  const [required, setRequired] = useState<RequiredDocument[]>([]);
  const [myDocs, setMyDocs] = useState<EmployeeDocument[]>([]);
  const [docTypes, setDocTypes] = useState<DocumentType[]>([]);
  const [loading, setLoading] = useState(true);

  function reload() {
    setLoading(true);
    return Promise.all([
      myRequiredDocuments(token),
      myDocuments(token),
      listActiveDocTypes(token),
    ]).then(([req, docs, types]) => {
      setRequired(req);
      setMyDocs(docs);
      setDocTypes(types);
    }).finally(() => setLoading(false));
  }

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  return { required, myDocs, docTypes, loading, reload, setRequired, setMyDocs };
}
