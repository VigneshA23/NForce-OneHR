import { useState } from 'react';
import { X } from 'lucide-react';

/**
 * Static policy content (Penalisation Policy / Time Tracking Policy) shown from the Attendance
 * page's Quick Actions. There's no backend-editable policy document behind this yet — the text
 * mirrors the org's actual attendance policy, entered directly here rather than left as a
 * placeholder link, per the story's own content. If HR needs to edit this without a code change
 * later, it should move into a real Policy document (see PoliciesPage.tsx's model) — not
 * attempted here to keep this change scoped to the Attendance page.
 */

type PolicyBlock =
  | { type: 'heading'; text: string }
  | { type: 'text'; text: string }
  | { type: 'callout'; text: string }
  | { type: 'bullets'; items: string[] };

const PENALISATION_POLICY_BLOCKS: PolicyBlock[] = [
  { type: 'text', text: 'Below are the details of your Penalisation Policy' },
  { type: 'text', text: 'Penalisation policy is effective 08 Jul 2024' },

  { type: 'heading', text: 'No Attendance' },
  { type: 'text', text: 'You will be penalized 1 day(s) of Paid Leave for every single missing attendance day' },
  { type: 'callout', text: 'You have a buffer period of 2 day(s) to regularize your attendance before the penalization happens.' },
  { type: 'text', text: 'The order of paid leave for deduction is:' },
  { type: 'bullets', items: ['Paid Leave'] },
  { type: 'text', text: 'In case no Paid Leave are left, Unpaid leave will be deducted.' },

  { type: 'heading', text: 'Late Arrival' },
  { type: 'callout', text: 'You have a grace period (tolerance) of 10 minutes beyond which your arrival will be considered as late.' },
  { type: 'text', text: 'You can come 2 time(s) late in a month, beyond which you will be penalized with 0.5 day(s) of Paid Leave for every 1 incident(s).' },
  { type: 'callout', text: 'If required 100% effective hours are met, the given day will not be considered for late arrival penalization.' },
  { type: 'callout', text: 'You have a buffer period of 2 day(s) to regularize your attendance before the penalization happens.' },
  { type: 'text', text: 'The order of paid leave for deduction is:' },
  { type: 'bullets', items: ['Paid Leave'] },
  { type: 'text', text: 'In case no Paid Leave are left, Unpaid leave will be deducted.' },

  { type: 'heading', text: 'Work Hours' },
  { type: 'text', text: 'You will be penalized, in following manner, based on the shortage of effective hours in a day:' },
  { type: 'bullets', items: [
    '0.5 day(s) of Paid Leave deduction if average effective hours in a day, is less than 90% of shift hours.',
    '1 day(s) of Paid Leave deduction if average effective hours in a day, is less than 50% of shift hours.',
  ] },
  { type: 'text', text: 'In case you have both Late Arrival and Work Hour penalization for the same day, penalization for only Shortage Of work hours will apply.' },
  { type: 'callout', text: 'You have a buffer period of 2 day(s) to regularize your attendance before the penalization happens.' },
  { type: 'text', text: 'The order of paid leave for deduction is:' },
  { type: 'bullets', items: ['Paid Leave'] },
  { type: 'text', text: 'In case no Paid Leave are left, Unpaid leave will be deducted.' },

  { type: 'heading', text: 'Missing Swipes' },
  { type: 'text', text: 'In case of missing swipes exceeding 5 working day(s) in a month, 0.25 day(s) of Paid Leave for every 1 subsequent incident(s) of missing swipe day' },
  { type: 'callout', text: 'You have a buffer period of 2 day(s) to regularize your attendance before the penalization happens.' },
  { type: 'text', text: 'The order of paid leave for deduction is:' },
  { type: 'bullets', items: ['Paid Leave'] },
  { type: 'text', text: 'In case no Paid Leave are left, Unpaid leave will be deducted.' },
];

const TIME_TRACKING_POLICY_BLOCKS: PolicyBlock[] = [
  { type: 'text', text: 'Below are the details of the time tracking policy assigned to you' },

  { type: 'heading', text: 'Web Check-In' },
  { type: 'text', text: 'Your attendance is tracked via check-in/check-out on this page, or via Web Check-in when working remotely (subject to approval).' },

  { type: 'heading', text: 'Work from Home (WFH)' },
  { type: 'text', text: 'You are allowed to take 2 day(s) of WFH in a Month.' },
  { type: 'text', text: 'You can request for full day, half-day as well as hourly WFH' },
  { type: 'text', text: 'You are required to clock-in/out when doing WFH. In case of late clock-in, no clock-in, or less effective/gross hours clocked, the system will penalise based on the penalisation policy assigned to you.' },
  { type: 'text', text: 'WFH request requires 2 day(s) of prior notice, containing at least 0 working day(s)' },
  { type: 'callout', text: 'Approval is required for all WFH requests.' },

  { type: 'heading', text: 'Regularization & Partial Day' },
  { type: 'text', text: 'In case of penalisation due to attendance discrepancy, you are allowed to request regularisation, 3 time(s) in a Month.' },
  { type: 'text', text: 'You are allowed to raise a regularisation request for the past 5 day(s).' },
  { type: 'text', text: 'You are allowed to apply for below partial work day request(s), based on number of cumulative (total) hours in a period' },
  { type: 'callout', text: 'Partial day is allowed for a cumulative hours of 120 minutes in a Month.' },
  { type: 'bullets', items: [
    'Late Arrival: You are allowed to request for maximum 120 minutes (each instance) of late arrival (after shift start time)',
    'Early Leaving: You are allowed to request for maximum 120 minutes (each instance) of early leaving (before shift end time)',
    'Anytime during the shift: You are allowed to request for maximum 120 minutes (each instance) of partial day leave (during the shift)',
  ] },
  { type: 'text', text: 'Partial day request cannot be made sooner than 1 day(s).' },
  { type: 'callout', text: 'Approval is required for all Regularization / Partial Day requests.' },
];

function PolicyBlockView({ block }: { block: PolicyBlock }) {
  if (block.type === 'heading') {
    return <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)', marginTop: 18 }}>{block.text}</div>;
  }
  if (block.type === 'callout') {
    return (
      <div style={{ background: 'rgba(76,141,214,.10)', border: '1px solid rgba(76,141,214,.25)', borderRadius: 6, padding: '10px 14px', fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
        {block.text}
      </div>
    );
  }
  if (block.type === 'bullets') {
    return (
      <ul style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 6 }}>
        {block.items.map((item, i) => (
          <li key={i} style={{ fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>{item}</li>
        ))}
      </ul>
    );
  }
  return <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>{block.text}</div>;
}

type PolicyTab = 'PENALISATION' | 'TIME_TRACKING';

export function AttendancePolicyModal({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState<PolicyTab>('PENALISATION');
  const blocks = tab === 'PENALISATION' ? PENALISATION_POLICY_BLOCKS : TIME_TRACKING_POLICY_BLOCKS;

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 640, maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.5)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>Attendance Policy</span>
          <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex' }}><X size={16} /></button>
        </div>
        <div style={{ padding: '16px 20px 0' }}>
          <div style={{ display: 'flex', gap: 6 }}>
            {([
              { value: 'PENALISATION', label: 'Penalisation Policy' },
              { value: 'TIME_TRACKING', label: 'Time Tracking Policy' },
            ] as const).map((t) => {
              const active = t.value === tab;
              return (
                <button
                  key={t.value}
                  onClick={() => setTab(t.value)}
                  style={{
                    background: active ? 'rgba(47,182,124,.12)' : 'var(--raised)',
                    color: active ? '#2FB67C' : 'var(--txt-mut)',
                    border: `1px solid ${active ? 'rgba(47,182,124,.35)' : 'var(--line2)'}`,
                    borderRadius: 7, padding: '7px 14px', fontSize: 12.5, fontWeight: 600, cursor: 'pointer',
                  }}
                >
                  {t.label}
                </button>
              );
            })}
          </div>
        </div>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 10 }}>
          {blocks.map((block, i) => <PolicyBlockView key={i} block={block} />)}
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Close</button>
          </div>
        </div>
      </div>
    </div>
  );
}
