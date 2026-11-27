import { describe, expect, it } from 'vitest';
import { EMPTY_ELIGIBILITY, appliesToSummary, sameEligibility } from './AppliesTo';
import type { LeavePreview, LeaveRuleSummary, LeaveType, PlanTypeRule } from '../../api/leave';
import {
  EMPTY_LIMITS, EMPTY_RULE_ELIGIBILITY, attachmentProblem, firstOfNextMonth, formsFor, hourlyDays, limitsPayload, modeFor, previewNotes, ruleLines, toPayload,
} from './planRules';

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

describe('request rules (LV-02)', () => {
  const blank = formsFor(types, [paidRule])[0];

  it('sends no limits as nulls, and keeps half days allowed by default', () => {
    const { rules } = toPayload([blank], types);
    expect(rules[0].limits).toMatchObject({
      entitlementMode: 'QUOTA', maxDaysPerRequest: null, noticeDays: null, allowHalfDay: true, allowHourly: false,
      attachmentAllowedTypes: null, notNextToTypeIds: [],
    });
  });

  it('normalises file types, drops minimum hours without hourly leave, and a type blocking itself', () => {
    const l = limitsPayload(paid, {
      ...EMPTY_LIMITS, attachmentAllowedTypes: ' PDF, .jpg,pdf ', minHours: '2', notNextToTypeIds: ['p', 'u'],
    }, 'QUOTA');
    expect(l).toMatchObject({ attachmentAllowedTypes: 'pdf,jpg', minHours: null, notNextToTypeIds: ['u'] });
  });

  it('rejects limits that contradict each other, naming the type', () => {
    expect(limitsPayload(paid, { ...EMPTY_LIMITS, minDaysPerRequest: '3', maxDaysPerRequest: '2' }, 'QUOTA'))
      .toBe("Paid Leave: the minimum days per request can't be more than the maximum");
    expect(limitsPayload(paid, { ...EMPTY_LIMITS, noticeAppliesOverDays: '2' }, 'QUOTA')).toMatch(/notice days/);
    expect(limitsPayload(paid, { ...EMPTY_LIMITS, maxRequestsPerMonth: '1.5' }, 'QUOTA')).toMatch(/whole number/);
    expect(limitsPayload(paid, { ...EMPTY_LIMITS, attachmentAllowedTypes: 'pdf, ../exe' }, 'QUOTA')).toMatch(/isn't a file extension/);
  });

  it('earned-only and unlimited paid leave need no days; unlimited leave cannot carry forward', () => {
    const earned = { ...blank, days: '', carryForwardEnabled: false, limits: { ...EMPTY_LIMITS, entitlementMode: 'EARNED_ONLY' as const } };
    expect(toPayload([earned], types).rules[0]).toMatchObject({ days: 0, limits: { entitlementMode: 'EARNED_ONLY' } });
    const unlimited = { ...blank, carryForwardEnabled: true, limits: { ...EMPTY_LIMITS, entitlementMode: 'UNLIMITED' as const } };
    expect(toPayload([unlimited], types).error).toMatch(/no balance to carry forward/);
  });

  it('reads back a saved rule into the form', () => {
    const saved: PlanTypeRule = {
      ...paidRule,
      limits: {
        entitlementMode: 'QUOTA', minDaysPerRequest: null, maxDaysPerRequest: 3, maxRequestsPerMonth: 2, maxRequestsPerYear: null,
        maxConsecutiveDays: null, noticeDays: 7, noticeAppliesOverDays: 2, allowHalfDay: false, allowHourly: true, minHours: 2,
        attachmentRequiredOverDays: 2, attachmentAllowedTypes: 'pdf', attachmentMaxMb: 5, notNextToTypeIds: ['u'],
        sandwichEnabled: false, sandwichAppliesTo: 'BOTH', sandwichMinDays: null, sandwichAcrossRequests: false,
      },
    };
    expect(formsFor(types, [saved])[0].limits).toMatchObject({
      maxDaysPerRequest: '3', maxRequestsPerMonth: '2', noticeDays: '7', allowHalfDay: false, allowHourly: true, minHours: '2',
      attachmentAllowedTypes: 'pdf', notNextToTypeIds: ['u'],
    });
  });

  const summary: LeaveRuleSummary = {
    entitlementMode: 'QUOTA', minDaysPerRequest: null, maxDaysPerRequest: 3, maxRequestsPerMonth: 1, maxRequestsPerYear: null,
    maxConsecutiveDays: null, noticeDays: 7, noticeAppliesOverDays: 2, allowHalfDay: false, allowHourly: true, minHours: 2,
    attachmentRequiredOverDays: 2, attachmentAllowedTypes: ['pdf', 'jpg'], attachmentMaxMb: 5, notNextTo: ['Casual Leave'],
    hoursPerDay: 8, sandwichEnabled: false, sandwichAppliesTo: 'BOTH', sandwichMinDays: null, sandwichAcrossRequests: false,
  };

  it('describes the rules for the apply form', () => {
    expect(ruleLines(summary)).toEqual([
      'At most 3 days per request',
      'At most 1 request a month',
      'Apply 7 days ahead for more than 2 days',
      'No half days',
      'Can be taken by the hour (at least 2 hours)',
      'Attachment needed over 2 days (PDF, JPG, max 5 MB)',
      'Not next to Casual Leave',
    ]);
    expect(ruleLines({ ...summary, maxDaysPerRequest: null, maxRequestsPerMonth: null, noticeDays: null, allowHalfDay: true,
      allowHourly: false, attachmentRequiredOverDays: null, notNextTo: [] })).toEqual([]);
  });

  it('checks a file before upload and converts hours to days', () => {
    expect(attachmentProblem(summary, 'Sick Leave', 'scan.docx', 100)).toBe('Sick Leave attachments must be PDF, JPG.');
    expect(attachmentProblem(summary, 'Sick Leave', 'scan.PDF', 6 * 1024 * 1024)).toBe('Sick Leave attachments can be at most 5 MB.');
    expect(attachmentProblem(summary, 'Sick Leave', 'scan.pdf', 1024)).toBeNull();
    expect(hourlyDays(2, 8)).toBe(0.25);
    expect(hourlyDays(3, 8)).toBe(0.38);
  });
});

describe('entitlement mode (LV-02)', () => {
  it('is always earned-only for Comp-Off and only selectable for regular paid leave', () => {
    const compOff: LeaveType = { id: 'c', code: 'COMP_OFF', name: 'Comp-Off', classification: 'PAID', entitlementBasis: 'ANNUAL', active: true };
    expect(modeFor(compOff, { ...EMPTY_LIMITS, entitlementMode: 'QUOTA' })).toBe('EARNED_ONLY');
    expect(modeFor(maternity, { ...EMPTY_LIMITS, entitlementMode: 'UNLIMITED' })).toBe('QUOTA');
    expect(modeFor(paid, { ...EMPTY_LIMITS, entitlementMode: 'UNLIMITED' })).toBe('UNLIMITED');
  });
});

describe('sandwich rule (LV-07)', () => {
  it('sends the sandwich options, never for per-occurrence leave', () => {
    const f = { ...EMPTY_LIMITS, sandwichEnabled: true, sandwichAppliesTo: 'WEEK_OFFS' as const, sandwichMinDays: '3', sandwichAcrossRequests: true };
    expect(limitsPayload(paid, f, 'QUOTA')).toMatchObject({
      sandwichEnabled: true, sandwichAppliesTo: 'WEEK_OFFS', sandwichMinDays: 3, sandwichAcrossRequests: true,
    });
    expect(limitsPayload(maternity, f, 'QUOTA')).toMatchObject({ sandwichEnabled: false, sandwichMinDays: null, sandwichAcrossRequests: false });
    expect(limitsPayload(paid, { ...f, sandwichMinDays: '0' }, 'QUOTA')).toMatch(/sandwich minimum days/);
  });

  it('describes it on the apply form', () => {
    const base: LeaveRuleSummary = {
      entitlementMode: 'QUOTA', minDaysPerRequest: null, maxDaysPerRequest: null, maxRequestsPerMonth: null, maxRequestsPerYear: null,
      maxConsecutiveDays: null, noticeDays: null, noticeAppliesOverDays: null, allowHalfDay: true, allowHourly: false, minHours: null,
      attachmentRequiredOverDays: null, attachmentAllowedTypes: [], attachmentMaxMb: null, notNextTo: [], hoursPerDay: 8,
      sandwichEnabled: true, sandwichAppliesTo: 'BOTH', sandwichMinDays: 3, sandwichAcrossRequests: true,
    };
    expect(ruleLines(base)).toEqual(['Weekly offs and holidays between leave days count as leave (leave of 3 days or more), also between separate requests']);
    expect(ruleLines({ ...base, sandwichAppliesTo: 'HOLIDAYS', sandwichMinDays: null, sandwichAcrossRequests: false }))
      .toEqual(['Holidays between leave days count as leave']);
  });

  it('lists sandwiched and uncounted dates from the preview', () => {
    const p: LeavePreview = {
      totalDays: 4, workingDays: 2, sandwichedDays: 2, summary: '4 days: 2 working + 2 sandwiched', daysByYear: { 2027: 4 }, problem: null,
      days: [
        { date: '2027-03-05', kind: 'WORKING', days: 1, note: null },
        { date: '2027-03-06', kind: 'SANDWICHED', days: 1, note: 'Weekly off' },
        { date: '2027-03-07', kind: 'SANDWICHED', days: 1, note: 'Weekly off' },
        { date: '2027-03-08', kind: 'WORKING', days: 1, note: null },
        { date: '2027-03-10', kind: 'HOLIDAY', days: 0, note: 'Holi' },
      ],
    };
    expect(previewNotes(p)).toEqual([
      'Counted as leave (sandwich): Sat 6 Mar (Weekly off), Sun 7 Mar (Weekly off)',
      'Not counted: Wed 10 Mar (Holi)',
    ]);
  });
});

describe('applies to: LV-06 criteria', () => {
  it('summarises marital status, grade, tenure and probation', () => {
    const e = { ...EMPTY_ELIGIBILITY, maritalStatuses: ['Married'], grades: ['G3'], minTenureMonths: 6, allowedDuringProbation: false };
    expect(appliesToSummary({ eligibility: e }, [], [])).toBe('Married · grade G3 · after 6 months of service · not during probation');
  });

  it('treats a changed tenure or probation flag as a change, and missing fields as the defaults', () => {
    const legacy = { genders: [], departmentIds: [], locationIds: [], employmentTypes: [], includeEmployeeIds: [], excludeEmployeeIds: [] };
    expect(sameEligibility(legacy, EMPTY_ELIGIBILITY)).toBe(true);
    expect(sameEligibility(EMPTY_ELIGIBILITY, { ...EMPTY_ELIGIBILITY, minTenureMonths: 3 })).toBe(false);
    expect(sameEligibility(EMPTY_ELIGIBILITY, { ...EMPTY_ELIGIBILITY, allowedDuringProbation: false })).toBe(false);
  });
});
