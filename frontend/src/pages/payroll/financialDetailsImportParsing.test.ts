import { describe, expect, it } from 'vitest';
import { guessFinancialColumnMapping, validateFinancialRows, type EmployeeLookup } from './financialDetailsImportParsing';

describe('guessFinancialColumnMapping', () => {
  it('matches common header spellings', () => {
    const mapping = guessFinancialColumnMapping(['Employee Code', 'Bank Name', 'Bank Account Number', 'Bank IFSC', 'PAN Number', 'UAN Number', 'ESI Number']);
    expect(mapping).toEqual({ employeeCode: 0, bankName: 1, bankAccountNumber: 2, bankIfsc: 3, panNumber: 4, uanNumber: 5, esiNumber: 6 });
  });

  it('leaves unmatched fields undefined', () => {
    const mapping = guessFinancialColumnMapping(['Name', 'Department']);
    expect(Object.keys(mapping)).toHaveLength(0);
  });
});

describe('validateFinancialRows', () => {
  const employees = new Map<string, EmployeeLookup>([['nf-001', { userId: 'u1', fullName: 'Asha Rao' }]]);

  it('returns nothing when the employee-code column was never mapped', () => {
    const rows = validateFinancialRows([['NF-001', 'ABCDE1234F']], { panNumber: 1 }, employees);
    expect(rows).toHaveLength(0);
  });

  it('builds a patch from whichever optional columns were mapped and non-empty', () => {
    const mapping = { employeeCode: 0, panNumber: 1, bankName: 2 };
    const [row] = validateFinancialRows([['NF-001', 'abcde1234f', 'HDFC Bank']], mapping, employees);
    expect(row.error).toBeUndefined();
    expect(row.employeeId).toBe('u1');
    expect(row.patch).toEqual({ panNumber: 'abcde1234f', bankName: 'HDFC Bank' });
  });

  it('omits a mapped-but-blank cell from the patch rather than clearing it', () => {
    const mapping = { employeeCode: 0, panNumber: 1, bankName: 2 };
    const [row] = validateFinancialRows([['NF-001', '', 'HDFC Bank']], mapping, employees);
    expect(row.patch).toEqual({ bankName: 'HDFC Bank' });
  });

  it('flags an unknown employee code', () => {
    const mapping = { employeeCode: 0, panNumber: 1 };
    const [row] = validateFinancialRows([['NF-999', 'ABCDE1234F']], mapping, employees);
    expect(row.error).toContain('NF-999');
  });

  it('flags a row with an employee code but nothing to update', () => {
    const mapping = { employeeCode: 0, panNumber: 1 };
    const [row] = validateFinancialRows([['NF-001', '']], mapping, employees);
    expect(row.error).toContain('No mapped field');
  });

  it('silently skips a fully blank trailing row', () => {
    const mapping = { employeeCode: 0, panNumber: 1 };
    const rows = validateFinancialRows([['NF-001', 'ABCDE1234F'], ['', '']], mapping, employees);
    expect(rows).toHaveLength(1);
  });
});
