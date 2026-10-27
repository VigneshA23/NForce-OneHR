import { describe, expect, it } from 'vitest';
import { bucketRequiredDocuments } from './shared';
import type { RequiredDocument } from '../../api/documents';

function doc(overrides: Partial<RequiredDocument>): RequiredDocument {
  return {
    documentTypeId: 1,
    documentTypeName: 'PAN Card',
    requiresVerification: true,
    requiresExpiryDate: false,
    uploaded: true,
    status: 'PENDING_VERIFICATION',
    expiringSoon: false,
    ...overrides,
  };
}

describe('bucketRequiredDocuments', () => {
  it('puts a REJECTED document in the rejected bucket, never in pending', () => {
    const rejected = doc({ documentTypeId: 2, status: 'REJECTED' });
    const buckets = bucketRequiredDocuments([rejected]);

    expect(buckets.rejected).toEqual([rejected]);
    expect(buckets.pending).toEqual([]);
  });

  it('the Pending Review bucket only ever holds PENDING_VERIFICATION documents', () => {
    const pending = doc({ documentTypeId: 3, status: 'PENDING_VERIFICATION' });
    const rejected = doc({ documentTypeId: 4, status: 'REJECTED' });
    const verified = doc({ documentTypeId: 5, status: 'VERIFIED' });
    const buckets = bucketRequiredDocuments([pending, rejected, verified]);

    expect(buckets.pending).toEqual([pending]);
  });

  it('keeps Verified and Not Submitted counts accurate alongside a rejected document', () => {
    const verified = doc({ documentTypeId: 6, status: 'VERIFIED' });
    const missing = doc({ documentTypeId: 7, status: null, uploaded: false });
    const rejected = doc({ documentTypeId: 8, status: 'REJECTED' });
    const buckets = bucketRequiredDocuments([verified, missing, rejected]);

    expect(buckets.verified).toEqual([verified]);
    expect(buckets.missing).toEqual([missing]);
    expect(buckets.rejected).toEqual([rejected]);
  });

  it('a rejected document is uploaded, so it is never counted as Not Submitted either', () => {
    const rejected = doc({ documentTypeId: 9, status: 'REJECTED', uploaded: true });
    const buckets = bucketRequiredDocuments([rejected]);

    expect(buckets.missing).toEqual([]);
    expect(buckets.rejected).toEqual([rejected]);
  });

  it('returns empty buckets for an empty required-documents list', () => {
    const buckets = bucketRequiredDocuments([]);

    expect(buckets).toEqual({ verified: [], pending: [], rejected: [], missing: [] });
  });
});
