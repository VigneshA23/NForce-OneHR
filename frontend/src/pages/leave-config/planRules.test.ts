import { describe, expect, it } from 'vitest';
import type { LeaveType, PlanTypeRule } from '../../api/leave';
import { EMPTY_RULE_ELIGIBILITY, firstOfNextMonth, formsFor, toPayload } from './planRules';

const paid: LeaveType = { id: 'p', code: 'PAID', name: 'Paid Leave', classification: 'PAID', entitlementBasis: 'ANNUAL', active: true };
const unpaid: LeaveType = { id: 'u', code: 'UNPAID', name: 'Unpaid Leave', classification: 'UNPAID', entitlementBasis: 'UNLIMITED', active: true };
const maternity: LeaveType = { id: 'm', code: 'MAT', name: 'Maternity', classification: 'PAID', entitlementBasis: 'PER_OCCURRENCE', active: true };
const retired: LeaveType = { id: 'r', code: 'OLD', name: 'Old Leave', classification: 'PAID', entitlementBasis: 'ANNUAL', active: false };
const types = [paid, unpaid, maternity, retired];

const paidRule: PlanTypeRule = {
  leaveTypeId: 'p', leaveTypeCode: 'PAID', leaveTypeName: 'Paid Leave', classification: 'PAID', entitlementBasis: 'ANNUAL',
  leaveTypeActive: true, days: 15, creditFrequency: 'MONTHLY', carryForwardEnabled: true, carryForwardMaxDays: 5,
  carryForwardExpiryMonths: 3, carryForwardPriority: 'CURRENT_FIRST', eligibility: EMPTY_RULE_ELIGIBILITY,
};

describe('plan rule forms (LV-01)', () => {
  it('lists every active type, marking those the plan includes, and hides inactive types it does not include', () => {
    const forms = formsFor(types, [paidRule]);
    expect(forms.map(f => [f.leaveTypeId, f.included])).toEqual([['p', true], ['u', false], ['m', false]]);
    expect(forms[0]).toMatchObject({ days: '15', creditFrequency: 'MONTHLY', carryForwardMaxDays: '5', carryForwardExpiryMonths: '3' });
    expect(forms.find(f => f.leaveTypeId === 'm')?.days).toBe('182'); // prefill only
  });

  it('builds the payload: days only for paid types, carry-forward only for regular paid leave', () => {
    const forms = formsFor(types, [paidRule]).map(f => ({ ...f, included: true, carryForwardEnabled: true, carryForwardMaxDays: '4' }));
    const { rules, error } = toPayload(forms, types);
    expect(error).toBeUndefined();
    expect(rules.find(r => r.leaveTypeId === 'u')).toMatchObject({ days: null, creditFrequency: null, carryForwardEnabled: false });
    expect(rules.find(r => r.leaveTypeId === 'm')).toMatchObject({ days: 182, creditFrequency: null, carryForwardEnabled: false });
    expect(rules.find(r => r.leaveTypeId === 'p')).toMatchObject({ days: 15, carryForwardMaxDays: 4, carryForwardExpiryMonths: 3 });
  });

  it('names the leave type in validation errors', () => {
    const forms = formsFor(types, [paidRule]);
    expect(toPayload([{ ...forms[0], days: '' }], types).error).toBe('Paid Leave: days must be between 0 and 999');
    expect(toPayload([{ ...forms[0], carryForwardExpiryMonths: '30' }], types).error).toMatch(/^Paid Leave: carried-forward days/);
  });

  it('defaults a change to the 1st of next month, across a year end', () => {
    expect(firstOfNextMonth(new Date(2026, 10, 27))).toBe('2026-12-01');
    expect(firstOfNextMonth(new Date(2026, 11, 5))).toBe('2027-01-01');
  });
});
