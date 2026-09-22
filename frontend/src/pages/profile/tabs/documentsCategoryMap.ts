export type DocSubTabKey = 'identity' | 'education' | 'employment' | 'statutory' | 'payslips';

export const DOC_SUB_TABS: { key: DocSubTabKey; label: string }[] = [
  { key: 'identity', label: 'Identity & Compliance Proofs' },
  { key: 'education', label: 'Educational Certificates' },
  { key: 'employment', label: 'Previous Employment' },
  { key: 'statutory', label: 'Statutory Documents' },
  { key: 'payslips', label: 'Payslips & Tax Documents' },
];

// Checked in this fixed priority order — first keyword match wins. PLACEHOLDER pending a real
// backend DocumentType.category field (deferred per product decision) — revisit once that exists
// instead of matching on the human-readable document type name.
const CATEGORY_KEYWORDS: { key: DocSubTabKey; keywords: string[] }[] = [
  { key: 'identity', keywords: ['aadhaar', 'pan', 'passport', 'voter', 'driving', 'identity', 'address proof', 'national id'] },
  { key: 'education', keywords: ['degree', 'diploma', 'marksheet', 'transcript', '10th', '12th', 'graduation', 'certificate'] },
  { key: 'employment', keywords: ['relieving', 'experience letter', 'offer letter', 'previous employer', 'service letter'] },
  { key: 'statutory', keywords: ['pf', 'uan', 'esi', 'form 12b', 'nomination', 'gratuity'] },
  { key: 'payslips', keywords: ['payslip', 'salary slip', 'form 16', 'tax'] },
];

// Unmatched types default into 'identity' (the most general-purpose bucket) rather than a 6th
// UI tab, since the confirmed spec is exactly 5 sub-tabs.
export function categorizeDocument(documentTypeName: string): DocSubTabKey {
  const n = documentTypeName.toLowerCase();
  for (const { key, keywords } of CATEGORY_KEYWORDS) {
    if (keywords.some(k => n.includes(k))) return key;
  }
  return 'identity';
}
