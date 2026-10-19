import { BENCH_REASON_LABELS, type BenchListRow } from '../../api/bench';

/**
 * US-A09: the spreadsheet rows for the bench list exactly as filtered on screen — same rows, same
 * order — so the export can never disagree with what the user was looking at.
 */
export function toExportRows(rows: BenchListRow[], selectedSkillCount: number) {
  return rows.map(r => ({
    Employee: r.employeeName,
    Reason: BENCH_REASON_LABELS[r.reasonCode],
    'Bench start': r.startDate,
    'Days on bench': r.daysOnBench,
    'Aging bucket': r.agingBucket ?? '',
    'Skill match': selectedSkillCount ? `${r.matchScore}/${selectedSkillCount}` : '',
    Skills: r.skills.join(', '),
  }));
}
