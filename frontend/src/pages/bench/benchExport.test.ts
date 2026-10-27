import { describe, expect, it } from 'vitest';
import * as XLSX from 'xlsx';
import type { BenchListRow } from '../../api/bench';
import { toExportRows } from './benchExport';

const row = (over: Partial<BenchListRow> = {}): BenchListRow => ({
  id: '1', employeeId: 'e1', employeeName: 'Ann', reasonCode: 'PROJECT_ENDED', startDate: '2026-09-02',
  daysOnBench: 40, agingBucket: '31-60 days', matchScore: 2, skills: ['Java (4/5)', 'SQL (3/5)'], ...over,
});

describe('toExportRows', () => {
  it('maps a bench row to the spreadsheet columns, keeping order', () => {
    const out = toExportRows([row(), row({ employeeName: 'Bob', daysOnBench: 10 })], 0);

    expect(out.map(r => r.Employee)).toEqual(['Ann', 'Bob']);
    expect(out[0]).toMatchObject({
      Reason: 'Project ended', 'Bench start': '2026-09-02', 'Days on bench': 40,
      'Aging bucket': '31-60 days', Skills: 'Java (4/5), SQL (3/5)',
    });
  });

  it('shows the skill match only when skills were searched', () => {
    expect(toExportRows([row()], 0)[0]['Skill match']).toBe('');
    expect(toExportRows([row()], 3)[0]['Skill match']).toBe('2/3');
  });

  it('blanks a missing bucket and produces a valid worksheet', () => {
    const out = toExportRows([row({ agingBucket: null, skills: [] })], 0);
    expect(out[0]['Aging bucket']).toBe('');

    const ws = XLSX.utils.json_to_sheet(out);
    expect(XLSX.utils.sheet_to_json(ws)).toHaveLength(1);
  });
});
