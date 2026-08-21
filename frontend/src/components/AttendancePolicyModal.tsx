import { useState } from 'react';
import { Info, X } from 'lucide-react';

/**
 * Static policy content (Penalisation Policy / Time Tracking Policy) shown from the Attendance
 * page's Quick Actions. There's no backend-editable policy document behind this yet — the text
 * mirrors the org's actual attendance policy, entered directly here rather than left as a
 * placeholder link, per the story's own content. If HR needs to edit this without a code change
 * later, it should move into a real Policy document (see PoliciesPage.tsx's model) — not
 * attempted here to keep this change scoped to the Attendance page.
 *
 * Layout is a full-page takeover (not a small centered dialog) matching Keka's reference —
 * see nf-attpolicy-* rules in index.css for the mobile breakpoint.
 */

type PolicyBlock =
  | { type: 'heading'; text: string }
  | { type: 'text'; text: string }
  | { type: 'callout'; text: string }
  | { type: 'bullets'; items: string[] };

const PENALISATION_POLICY_BLOCKS: PolicyBlock[] = [
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

  { type: 'heading', text: 'Check-in' },
  { type: 'text', text: 'Your attendance is tracked via check-in/check-out on this page, or via Check-in when working remotely (subject to approval).' },

  { type: 'heading', text: 'Work from Home (WFH)' },
  { type: 'text', text: 'You are allowed to take 2 day(s) of WFH in a Month.' },
  { type: 'text', text: 'You can request for full day, half-day' },
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
    return (
      <h3
        className="nf-attpolicy-heading"
        style={{
          fontFamily: '"Space Grotesk", sans-serif', fontSize: 17, fontWeight: 700, color: 'var(--txt)',
          margin: '32px 0 12px', paddingBottom: 10, borderBottom: '1px solid var(--line)',
        }}
      >
        {block.text}
      </h3>
    );
  }
  if (block.type === 'callout') {
    return (
      <div
        className="nf-attpolicy-callout"
        style={{
          display: 'flex', alignItems: 'flex-start', gap: 10,
          background: 'rgba(76,141,214,.10)', border: '1px solid rgba(76,141,214,.28)',
          borderLeft: '3px solid #4C8DD6', borderRadius: 8, padding: '12px 16px',
          fontSize: 13.5, color: 'var(--txt)', lineHeight: 1.6, margin: '12px 0',
        }}
      >
        <Info size={15} style={{ color: '#4C8DD6', flexShrink: 0, marginTop: 2 }} />
        <span>{block.text}</span>
      </div>
    );
  }
  if (block.type === 'bullets') {
    return (
      <ul style={{ listStyle: 'none', margin: '8px 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {block.items.map((item, i) => (
          <li key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 13.5, color: 'var(--txt-mut)', lineHeight: 1.65 }}>
            <span style={{ flexShrink: 0, marginTop: 8, width: 6, height: 6, borderRadius: '50%', border: '1.5px solid var(--txt-dim)' }} />
            <span>{item}</span>
          </li>
        ))}
      </ul>
    );
  }
  return <p style={{ fontSize: 13.5, color: 'var(--txt-mut)', lineHeight: 1.7, margin: '10px 0' }}>{block.text}</p>;
}

type PolicyTab = 'PENALISATION' | 'TIME_TRACKING';

export function AttendancePolicyModal({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState<PolicyTab>('PENALISATION');
  const blocks = tab === 'PENALISATION' ? PENALISATION_POLICY_BLOCKS : TIME_TRACKING_POLICY_BLOCKS;

  return (
    <div className="nf-attpolicy-overlay" style={{ position: 'fixed', inset: 0, background: 'var(--shell)', zIndex: 500, display: 'flex', flexDirection: 'column' }}>
      {/* Header + tabs stay pinned while the long policy text scrolls beneath them. */}
      <div className="nf-attpolicy-sticky" style={{ position: 'sticky', top: 0, background: 'var(--shell)', borderBottom: '1px solid var(--line)', zIndex: 1 }}>
        <div className="nf-attpolicy-header" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '20px 32px' }}>
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 20, color: 'var(--txt)' }}>Attendance Policy</span>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 8, borderRadius: 7, display: 'flex' }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--raised)'; e.currentTarget.style.color = 'var(--txt)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'var(--txt-dim)'; }}
          >
            <X size={18} />
          </button>
        </div>
        <div className="nf-attpolicy-tabs" style={{ display: 'flex', gap: 8, padding: '0 32px 16px' }}>
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
                  borderRadius: 7, padding: '8px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer',
                }}
              >
                {t.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Scrollable body — full-width panel, but text itself stays capped for readability. */}
      <div className="nf-attpolicy-content" style={{ flex: 1, overflowY: 'auto', padding: '28px 32px 56px' }}>
        <div style={{ maxWidth: 860 }}>
          {blocks.map((block, i) => <PolicyBlockView key={i} block={block} />)}
        </div>
      </div>
    </div>
  );
}
