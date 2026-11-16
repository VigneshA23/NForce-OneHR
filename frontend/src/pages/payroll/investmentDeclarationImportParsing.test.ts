import { describe, expect, it } from 'vitest';
import { guessInvestmentColumnMapping, validateInvestmentRows, type EmployeeLookup } from './investmentDeclarationImportParsing';

describe('guessInvestmentColumnMapping', () => {
  it('matches common header spellings', () => {
    const mapping = guessInvestmentColumnMapping(['Employee Code', 'Financial Year', 'Section', 'Declared Amount']);
    expect(mapping).toEqual({ employeeCode: 0, financialYear: 1, section: 2, declaredAmount: 3 });
  });
});

describe('validateInvestmentRows', () => {
  const mapping = { employeeCode: 0, financialYear: 1, section: 2, declaredAmount: 3 };
  const employees = new Map<string, EmployeeLookup>([['nf-001', { userId: 'u1', fullName: 'Asha Rao' }]]);

  it('resolves a valid row completely', () => {
    const [row] = validateInvestmentRows([['NF-001', '2026-27', '80C', '1,50,000']], mapping, employees);
    expect(row.error).toBeUndefined();
    expect(row.employeeId).toBe('u1');
    expect(row.financialYear).toBe('2026-27');
    expect(row.section).toBe('80C');
    expect(row.declaredAmount).toBe(150000);
  });

  it('flags an unknown employee code', () => {
    const [row] = validateInvestmentRows([['NF-999', '2026-27', '80C', '150000']], mapping, employees);
    expect(row.error).toContain('NF-999');
  });

  it('flags a negative or non-numeric declared amount', () => {
    expect(validateInvestmentRows([['NF-001', '2026-27', '80C', '-5']], mapping, employees)[0].error).toContain('amount');
    expect(validateInvestmentRows([['NF-001', '2026-27', '80C', 'abc']], mapping, employees)[0].error).toContain('amount');
  });

  it('allows a declared amount of zero (e.g. clearing a prior declaration)', () => {
    const [row] = validateInvestmentRows([['NF-001', '2026-27', '80C', '0']], mapping, employees);
    expect(row.error).toBeUndefined();
    expect(row.declaredAmount).toBe(0);
  });

  it('silently skips a fully blank trailing row', () => {
    const rows = validateInvestmentRows([['NF-001', '2026-27', '80C', '150000'], ['', '', '', '']], mapping, employees);
    expect(rows).toHaveLength(1);
  });
});
