import { describe, expect, it } from 'vitest';
import { guessColumnMapping, normalizeDate, validateRows, type EmployeeLookup } from './salaryImportParsing';

describe('guessColumnMapping', () => {
  it('matches common header spellings for each required field', () => {
    const mapping = guessColumnMapping(['Employee Code', 'New CTC', 'Effective From']);
    expect(mapping).toEqual({ employeeCode: 0, ctc: 1, effectiveFrom: 2 });
  });

  it('matches regardless of column order, case and punctuation', () => {
    const mapping = guessColumnMapping(['New Annual CTC', 'Effective Date', 'Emp. Code']);
    expect(mapping).toEqual({ ctc: 0, effectiveFrom: 1, employeeCode: 2 });
  });

  it('leaves a field unmapped when nothing matches', () => {
    const mapping = guessColumnMapping(['Name', 'Department']);
    expect(mapping.employeeCode).toBeUndefined();
    expect(mapping.ctc).toBeUndefined();
    expect(mapping.effectiveFrom).toBeUndefined();
  });
});

describe('normalizeDate', () => {
  it('accepts ISO yyyy-mm-dd as-is', () => {
    expect(normalizeDate('2026-11-16')).toBe('2026-11-16');
  });

  it('converts dd-mm-yyyy and dd/mm/yyyy to ISO', () => {
    expect(normalizeDate('16-11-2026')).toBe('2026-11-16');
    expect(normalizeDate('05/01/2026')).toBe('2026-01-05');
  });

  it('rejects an out-of-range day or month', () => {
    expect(normalizeDate('35-11-2026')).toBeNull();
    expect(normalizeDate('16-13-2026')).toBeNull();
  });

  it('rejects empty or unrecognized text', () => {
    expect(normalizeDate('')).toBeNull();
    expect(normalizeDate('next Monday')).toBeNull();
  });
});

describe('validateRows', () => {
  const mapping = { employeeCode: 0, ctc: 1, effectiveFrom: 2 } as const;
  const employees = new Map<string, EmployeeLookup>([
    ['nf-001', { userId: 'u1', fullName: 'Asha Rao' }],
    ['nf-002', { userId: 'u2', fullName: 'Vikram Shah' }],
  ]);

  it('resolves a valid row completely', () => {
    const [row] = validateRows([['NF-001', '15,00,000', '16-11-2026']], mapping, employees);
    expect(row.error).toBeUndefined();
    expect(row.employeeId).toBe('u1');
    expect(row.employeeName).toBe('Asha Rao');
    expect(row.ctc).toBe(1500000);
    expect(row.effectiveFrom).toBe('2026-11-16');
  });

  it('matches employee code case-insensitively', () => {
    const [row] = validateRows([['nf-002', '1200000', '2026-01-01']], mapping, employees);
    expect(row.employeeId).toBe('u2');
  });

  it('flags an unknown employee code without crashing', () => {
    const [row] = validateRows([['NF-999', '1200000', '2026-01-01']], mapping, employees);
    expect(row.error).toContain('NF-999');
    expect(row.employeeId).toBeUndefined();
  });

  it('flags a non-numeric or zero/negative CTC', () => {
    expect(validateRows([['NF-001', 'abc', '2026-01-01']], mapping, employees)[0].error).toContain('CTC');
    expect(validateRows([['NF-001', '0', '2026-01-01']], mapping, employees)[0].error).toContain('CTC');
    expect(validateRows([['NF-001', '-5000', '2026-01-01']], mapping, employees)[0].error).toContain('CTC');
  });

  it('flags an unparseable date', () => {
    const [row] = validateRows([['NF-001', '1200000', 'whenever']], mapping, employees);
    expect(row.error).toContain('date');
  });

  it('silently skips a fully blank trailing row instead of reporting it as an error', () => {
    const rows = validateRows([['NF-001', '1200000', '2026-01-01'], ['', '', '']], mapping, employees);
    expect(rows).toHaveLength(1);
  });

  it('numbers rows by their position in the data (1-based, header excluded)', () => {
    const rows = validateRows([
      ['NF-001', '1200000', '2026-01-01'],
      ['NF-002', '1300000', '2026-01-01'],
    ], mapping, employees);
    expect(rows[0].rowNumber).toBe(1);
    expect(rows[1].rowNumber).toBe(2);
  });
});
