import { describe, expect, it } from 'vitest';
import { availableActions } from './PayrollRunDetailPage';

describe('availableActions', () => {
  it('DRAFT offers regenerate and submit, never approve/disburse/rollback', () => {
    expect(availableActions('DRAFT')).toEqual(['regenerate', 'submit']);
  });

  it('SUBMITTED offers approve and rollback (a run can be rejected back to DRAFT while SUBMITTED)', () => {
    expect(availableActions('SUBMITTED')).toEqual(['approve', 'rollback']);
  });

  it('APPROVED offers disburse and rollback', () => {
    expect(availableActions('APPROVED')).toEqual(['disburse', 'rollback']);
  });

  it('DISBURSED offers only rollback', () => {
    expect(availableActions('DISBURSED')).toEqual(['rollback']);
  });
});
