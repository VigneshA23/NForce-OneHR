import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileCertificatesApi, type CertificateEntry } from '../../../api/profile';
import { EditModal, EditField } from '../shared';

export function CertificateModal({ token, existing, onClose, onSaved }: {
  token: string;
  existing: CertificateEntry | null;
  onClose: () => void;
  onSaved: (c: CertificateEntry) => void;
}) {
  const { showToast } = useToast();
  const [name, setName] = useState(existing?.name ?? '');
  const [issuingOrganization, setIssuingOrganization] = useState(existing?.issuingOrganization ?? '');
  const [credentialId, setCredentialId] = useState(existing?.credentialId ?? '');
  const [credentialUrl, setCredentialUrl] = useState(existing?.credentialUrl ?? '');
  const [issueDate, setIssueDate] = useState(existing?.issueDate ?? '');
  const [expiryDate, setExpiryDate] = useState(existing?.expiryDate ?? '');
  const [saving, setSaving] = useState(false);

  const hasErrors = !name.trim();

  async function handleSave() {
    if (hasErrors) {
      showToast('error', 'Certificate name is required');
      return;
    }
    setSaving(true);
    try {
      const payload = {
        name: name.trim(),
        issuingOrganization: issuingOrganization.trim() || undefined,
        credentialId: credentialId.trim() || undefined,
        credentialUrl: credentialUrl.trim() || undefined,
        issueDate: issueDate || undefined,
        expiryDate: expiryDate || undefined,
      };
      const saved = existing
        ? await profileCertificatesApi.update(token, existing.id, payload)
        : await profileCertificatesApi.create(token, payload);
      onSaved(saved);
      showToast('success', existing ? 'Certificate updated' : 'Certificate added');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title={existing ? 'Edit Certificate' : 'Add Certificate'} onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors} width={480}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <EditField label="Certificate Name" value={name} onChange={setName} placeholder="e.g. AWS Certified Solutions Architect" />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <EditField label="Issuing Organization" value={issuingOrganization} onChange={setIssuingOrganization} placeholder="e.g. Amazon Web Services" />
          <EditField label="Credential ID" value={credentialId} onChange={setCredentialId} placeholder="Optional" />
        </div>
        <EditField label="Credential URL" value={credentialUrl} onChange={setCredentialUrl} placeholder="Link to certificate or badge" />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <EditField label="Issue Date" value={issueDate} onChange={setIssueDate} type="date" />
          <EditField label="Expiry Date" value={expiryDate} onChange={setExpiryDate} type="date" />
        </div>
      </div>
    </EditModal>
  );
}
