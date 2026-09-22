import { useEffect, useState } from 'react';
import { useToast } from '../../context/ToastContext';
import { assetsApi, type AssetCategory, type AssetRequestResponse } from '../../api/assets';
import { Modal, FormRow, BtnPrimary, BtnGhost, inputStyle, labelStyle } from './shared';

export function RequestAssetModal({ categories: propCategories, token, onClose, onCreated, initialCategoryId, initialReason }: {
  categories: AssetCategory[];
  token: string;
  onClose: () => void;
  onCreated: (r: AssetRequestResponse) => void;
  initialCategoryId?: number;
  initialReason?: string;
}) {
  const [categoryId, setCategoryId] = useState(initialCategoryId != null ? String(initialCategoryId) : '');
  const [reason, setReason] = useState(initialReason ?? '');
  const [requiredByDate, setRequiredByDate] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [localCats, setLocalCats] = useState<AssetCategory[]>([]);
  const { showToast } = useToast();

  // Self-fetch as fallback when parent state hasn't propagated yet
  useEffect(() => {
    if (propCategories.length === 0) {
      assetsApi.categories(token).then(setLocalCats).catch(() => {});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const categories = propCategories.length > 0 ? propCategories : localCats;

  async function submit() {
    if (!categoryId || !reason.trim()) return;
    setSubmitting(true);
    try {
      const result = await assetsApi.submitRequest(
        { categoryId: Number(categoryId), reason: reason.trim(), requiredByDate: requiredByDate || undefined },
        token,
      );
      onCreated(result);
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Submit failed');
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Request Asset" onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Category *</label>
        <select value={categoryId} onChange={e => setCategoryId(e.target.value)} style={inputStyle}>
          <option value="">Select category…</option>
          {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'inherit' }} placeholder="Why do you need this asset?" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Required By (optional)</label>
        <input type="date" value={requiredByDate} onChange={e => setRequiredByDate(e.target.value)} style={inputStyle} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!categoryId || !reason.trim() || submitting}>{submitting ? 'Submitting…' : 'Submit Request'}</BtnPrimary>
      </div>
    </Modal>
  );
}
