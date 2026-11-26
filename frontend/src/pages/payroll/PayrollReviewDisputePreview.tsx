/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW - NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  PN-02: renders the REAL payroll "Leave Applied Review" tab with canned review items so the
 *  disputed-penalty flag can be reviewed without a login or a payroll run. The tab's own API call
 *  is intercepted here; nothing is sent anywhere.
 *    /payroll-review-dispute-preview
 */
import { useEffect, useState } from 'react';
import { LeaveAppliedReviewTab } from './LeaveAppliedReviewTab';
import type { PayrollRunReviewItemResponse } from '../../api/payroll';

const RUN = '00000000-0000-0000-0000-0000000000aa';
const iso = (ago: number) => new Date(Date.now() - ago * 864e5).toISOString().slice(0, 10);
const item = (n: number, name: string, ago: number, days: number, source: string, dispute: PayrollRunReviewItemResponse['disputeStatus'], decision = 'PENDING'): PayrollRunReviewItemResponse => ({
  id: 'ri-' + n, payrollRunId: RUN, employeeId: 'emp-' + n, employeeName: name, stepType: 'LEAVE_APPLIED', sourceType: source, sourceId: 'src-' + n,
  itemDate: iso(ago), days, leaveTypeName: source === 'PENALTY' ? null : 'Unpaid Leave', decision: decision as PayrollRunReviewItemResponse['decision'],
  selectedLeaveTypeId: null, comment: null, decidedByName: null, decidedAt: null, disputeStatus: dispute,
});
const ITEMS: PayrollRunReviewItemResponse[] = [
  item(1, 'Rahul Verma', 6, 0.5, 'PENALTY', 'PENDING_MANAGER'),
  item(2, 'Meera Nair', 9, 1, 'PENALTY', 'PENDING_HR'),
  item(3, 'Karthik Reddy', 12, 0.5, 'PENALTY', 'UPHELD'),
  item(4, 'Divya Iyer', 14, 0.5, 'PENALTY', null),
  item(5, 'Arjun Mehta', 15, 2, 'LEAVE_UNPAID', null),
];

export default function PayrollReviewDisputePreview() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const prev = window.fetch;
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).split('?')[0].endsWith('/review/leave-applied')) {
        return Promise.resolve(new Response(JSON.stringify(ITEMS), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return prev(input, init);
    }) as typeof window.fetch;
    setReady(true);
    return () => { window.fetch = prev; };
  }, []);
  return (
    <div style={{ minHeight: '100vh', background: 'var(--shell)', color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
      <main style={{ maxWidth: 1200, margin: '0 auto', padding: '20px 28px 48px' }}>
        <div style={{ display: 'inline-flex', marginBottom: 14, padding: '5px 10px', borderRadius: 6, background: 'rgba(99,102,241,.10)', border: '1px solid rgba(99,102,241,.25)', fontSize: 11.5, fontWeight: 600, color: '#8183f4' }}>
          UI PREVIEW · Mock Data (Not Real) · Payroll run, Leave Applied Review
        </div>
        {ready && <LeaveAppliedReviewTab runId={RUN} canConfigure={false} />}
      </main>
    </div>
  );
}
