import { describe, expect, it } from 'vitest';
import {
  evaluateConditionLocally, validateRuleForm, stagesFromForm, EMPTY_FORM, type RuleFormValues,
} from './WorkflowStudioPage';
import type { ApprovalRuleMetadata } from '../api/workflowRules';

const metadata: ApprovalRuleMetadata = {
  requestTypes: ['EXPENSE'],
  conditionFieldsByRequestType: { EXPENSE: ['AMOUNT'] },
  operators: ['>', '>=', '<', '<=', '='],
  approvalRoles: ['MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'],
};

describe('evaluateConditionLocally', () => {
  it('below threshold is false', () => {
    expect(evaluateConditionLocally(300, '>', 500)).toBe(false);
  });
  it('exactly at threshold with > is false', () => {
    expect(evaluateConditionLocally(500, '>', 500)).toBe(false);
  });
  it('just above threshold with > is true', () => {
    expect(evaluateConditionLocally(501, '>', 500)).toBe(true);
  });
  it('well above threshold with > is true', () => {
    expect(evaluateConditionLocally(750, '>', 500)).toBe(true);
  });
  it('>= includes the boundary', () => {
    expect(evaluateConditionLocally(500, '>=', 500)).toBe(true);
    expect(evaluateConditionLocally(499.99, '>=', 500)).toBe(false);
  });
  it('handles decimal amounts', () => {
    expect(evaluateConditionLocally(500.01, '>', 500)).toBe(true);
    expect(evaluateConditionLocally(499.99, '>', 500)).toBe(false);
  });
  it('handles zero and negative amounts', () => {
    expect(evaluateConditionLocally(0, '>', 500)).toBe(false);
    expect(evaluateConditionLocally(-50, '>', 500)).toBe(false);
  });
  it('unrecognized operator is false, never throws', () => {
    expect(evaluateConditionLocally(1000, '!=', 500)).toBe(false);
  });
});

describe('stagesFromForm', () => {
  it('always includes MANAGER first', () => {
    const values: RuleFormValues = { ...EMPTY_FORM, secondApprovalRoles: [] };
    expect(stagesFromForm(values)).toEqual(['MANAGER']);
  });
  it('appends selected second-approval roles after MANAGER', () => {
    const values: RuleFormValues = { ...EMPTY_FORM, secondApprovalRoles: ['HR_ADMIN'] };
    expect(stagesFromForm(values)).toEqual(['MANAGER', 'HR_ADMIN']);
  });
  it('never duplicates MANAGER even if present in the selection', () => {
    const values: RuleFormValues = { ...EMPTY_FORM, secondApprovalRoles: ['MANAGER', 'HR_ADMIN'] };
    expect(stagesFromForm(values)).toEqual(['MANAGER', 'HR_ADMIN']);
  });
});

describe('validateRuleForm', () => {
  const valid: RuleFormValues = {
    ruleName: 'Second approval above $500', requestType: 'EXPENSE', conditionField: 'AMOUNT',
    operator: '>', conditionValue: '500', secondApprovalRoles: ['HR_ADMIN'],
  };

  it('accepts a fully valid rule', () => {
    expect(validateRuleForm(valid, metadata)).toEqual([]);
  });

  it('rejects a missing rule name', () => {
    const errors = validateRuleForm({ ...valid, ruleName: '' }, metadata);
    expect(errors).toContain('Rule name is required');
  });

  it('rejects an unsupported request type', () => {
    const errors = validateRuleForm({ ...valid, requestType: 'LEAVE' }, metadata);
    expect(errors.some(e => e.includes('Unsupported request type'))).toBe(true);
  });

  it('rejects an invalid condition field for the request type', () => {
    const errors = validateRuleForm({ ...valid, conditionField: 'InvalidCategoryName' }, metadata);
    expect(errors.some(e => e.includes('Invalid condition field'))).toBe(true);
  });

  it('rejects an invalid operator', () => {
    const errors = validateRuleForm({ ...valid, operator: '!=' }, metadata);
    expect(errors.some(e => e.includes('Invalid operator'))).toBe(true);
  });

  it('rejects an empty condition value', () => {
    const errors = validateRuleForm({ ...valid, conditionValue: '' }, metadata);
    expect(errors).toContain('Condition value is required');
  });

  it('rejects a non-numeric condition value', () => {
    const errors = validateRuleForm({ ...valid, conditionValue: 'abc' }, metadata);
    expect(errors.some(e => e.includes('valid number'))).toBe(true);
  });

  it('rejects a negative condition value', () => {
    const errors = validateRuleForm({ ...valid, conditionValue: '-100' }, metadata);
    expect(errors).toContain('Condition value cannot be negative');
  });

  it('rejects MANAGER selected as a second-approval role (it is always implicit)', () => {
    const errors = validateRuleForm({ ...valid, secondApprovalRoles: ['MANAGER'] }, metadata);
    expect(errors.length).toBeGreaterThan(0);
  });
});
