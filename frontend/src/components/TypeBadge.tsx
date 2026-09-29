import type { ApprovalItem, RequestType } from '../api/approvalCenter';

// Same labels/colors as ApprovalsPage.tsx's own TYPE_LABELS/COLORS/TEXT (kept in sync by hand —
// this component only covers the request types that ever reach My Team's "Needs your attention",
// a subset of ApprovalsPage's full RequestType list).
export const TYPE_LABELS: Record<RequestType, string> = {
  LEAVE: 'Leave',
  REGULARIZATION: 'Attendance Reg.',
  EXPENSE: 'Expense',
  ASSET_REQUEST: 'Asset Request',
  WFH: 'Work From Home',
  PARTIAL_DAY: 'Partial Day',
  OVERTIME: 'Overtime',
  HELP_CONTENT: 'FAQs & Guides',
};

export const TYPE_COLORS: Record<RequestType, string> = {
  LEAVE: 'rgba(99,102,241,.18)',
  REGULARIZATION: 'rgba(245,158,11,.18)',
  EXPENSE: 'rgba(16,185,129,.18)',
  ASSET_REQUEST: 'rgba(139,92,246,.18)',
  WFH: 'rgba(76,141,214,.18)',
  PARTIAL_DAY: 'rgba(224,169,59,.18)',
  OVERTIME: 'rgba(236,72,153,.18)',
  HELP_CONTENT: 'rgba(20,184,166,.18)',
};

export const TYPE_TEXT: Record<RequestType, string> = {
  LEAVE: '#818CF8',
  REGULARIZATION: '#F59E0B',
  EXPENSE: '#10B981',
  ASSET_REQUEST: '#8B5CF6',
  WFH: '#4C8DD6',
  PARTIAL_DAY: '#E0A93B',
  OVERTIME: '#EC4899',
  HELP_CONTENT: '#14B8A6',
};

export function groupRequestsByType(requests: ApprovalItem[]): Array<{ type: RequestType; count: number }> {
  const counts = new Map<RequestType, number>();
  for (const r of requests) {
    counts.set(r.requestType, (counts.get(r.requestType) ?? 0) + 1);
  }
  const preferredOrder: RequestType[] = ['LEAVE', 'REGULARIZATION', 'EXPENSE', 'ASSET_REQUEST'];
  const orderedKeys = [
    ...preferredOrder.filter(t => counts.has(t)),
    ...Array.from(counts.keys()).filter(t => !preferredOrder.includes(t)),
  ];
  return orderedKeys.map(type => ({ type, count: counts.get(type)! }));
}

export function TypeBadge({ type, count }: { type: RequestType; count?: number }) {
  const label = TYPE_LABELS[type] ?? type;
  const suffix = count && count > 1 ? ` +${count - 1}` : '';
  return (
    <span
      title={count && count > 1 ? `${count} pending ${label.toLowerCase()} requests` : undefined}
      style={{
        fontSize: 10.5,
        fontWeight: 700,
        padding: '3px 8px',
        borderRadius: 20,
        background: TYPE_COLORS[type] ?? 'rgba(99,102,241,.18)',
        color: TYPE_TEXT[type] ?? '#818CF8',
        whiteSpace: 'nowrap',
      }}
    >
      {label}{suffix}
    </span>
  );
}
