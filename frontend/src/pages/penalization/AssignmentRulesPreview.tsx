/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW - NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  PN-04: (1) how a policy is chosen; (2) the REAL Auto-assignment Rules screen with canned data
 *  (add, edit, reorder and delete work locally); (3) the "resolve" explanation for each outcome;
 *  (4) the effective-dated record an employee builds up as their details change.
 *    /assignment-rules-preview
 *  Theme colour and Light/Dark follow the app's real settings (see PreviewChrome).
 */
import { ArrowRight, GitBranch, History, Layers, ShieldCheck, Sparkles, UserCheck } from 'lucide-react';
import AssignmentRulesSection, { type AssignmentRulesMock } from './AssignmentRulesSection';
import type { AssignmentRule } from '../../api/assignmentRules';
import { PreviewChrome, PreviewSection } from './PreviewChrome';

const POLICIES = [
  { id: 'p-std', name: 'Standard Attendance Policy' },
  { id: 'p-strict', name: 'Strict Policy (Support)' },
  { id: 'p-flex', name: 'Flexible Policy (Contractors)' },
];
const DEPTS = [{ id: 'd-eng', name: 'Engineering' }, { id: 'd-sup', name: 'Customer Support' }, { id: 'd-hr', name: 'HR' }];
const LOCS = [{ id: 'l-blr', name: 'Bangalore' }, { id: 'l-hyd', name: 'Hyderabad' }];
const SHIFTS = [{ id: 's-gen', name: 'General Shift' }, { id: 's-night', name: 'Night Shift' }];

const rule = (n: number, name: string, target: number, crit: Partial<AssignmentRule>, text: string, count: number, active = true): AssignmentRule => ({
  id: 'r' + n, domain: 'PENALIZATION', targetId: POLICIES[target].id, targetName: POLICIES[target].name, name, priority: n * 10,
  departmentId: null, departmentName: null, locationId: null, locationName: null, shiftId: null, shiftName: null, employmentType: null, grade: null,
  active, criteriaText: text, assignedCount: count, updatedAt: new Date().toISOString(), ...crit,
});

const MOCK: AssignmentRulesMock = {
  policies: POLICIES, departments: DEPTS, locations: LOCS, shifts: SHIFTS, grades: ['L1', 'L2', 'L3', 'L4'],
  rules: [
    rule(1, 'Night-shift support', 1, { departmentId: 'd-sup', departmentName: 'Customer Support', shiftId: 's-night', shiftName: 'Night Shift' }, 'Department = Customer Support AND Shift = Night Shift', 18),
    rule(2, 'Contractors', 2, { employmentType: 'CONTRACT' }, 'Employment type = contract', 9),
    rule(3, 'Engineering Bangalore', 0, { departmentId: 'd-eng', departmentName: 'Engineering', locationId: 'l-blr', locationName: 'Bangalore' }, 'Department = Engineering AND Location = Bangalore', 64),
    rule(4, 'Interns (retired)', 0, { employmentType: 'INTERN' }, 'Employment type = intern', 0, false),
  ],
};

const FLOW = [
  { icon: UserCheck, title: 'Explicit allocation', text: 'HR assigned this employee a policy by hand. Always wins.', strong: true },
  { icon: Layers, title: 'Auto-assignment rule', text: 'The first rule, top to bottom, whose conditions all match.' },
  { icon: ShieldCheck, title: 'Existing assignment', text: 'The policy already on the employee record.' },
  { icon: Sparkles, title: 'Organization default', text: 'Used when nothing above applies.' },
];

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, boxShadow: '0 1px 2px rgba(0,0,0,.05), 0 12px 28px -18px rgba(0,0,0,.28)' };

type Source = 'RULE' | 'ALLOCATION' | 'DEFAULT';
const SOURCE: Record<Source, { label: string; color: string }> = {
  RULE: { label: 'Auto (rule)', color: 'var(--info)' },
  ALLOCATION: { label: 'Allocated', color: 'var(--brand)' },
  DEFAULT: { label: 'Org default', color: 'var(--txt-dim)' },
};
const EXAMPLES: { source: Source; initials: string; who: string; detail: string; policy: string; text: string }[] = [
  { source: 'RULE', initials: 'AR', who: 'Asha Rao', detail: 'Joined today. Engineering, Bangalore', policy: 'Standard Attendance Policy',
    text: 'Auto-assignment rule "Engineering Bangalore" (Department = Engineering AND Location = Bangalore) assigns "Standard Attendance Policy", in effect since 2026-11-25.' },
  { source: 'ALLOCATION', initials: 'VS', who: 'Vikram Singh', detail: 'Engineering, Bangalore. HR gave a one-off allocation', policy: 'Flexible Policy (Contractors)',
    text: 'Explicit allocation to "Flexible Policy (Contractors)" from 2026-11-01 onwards. An explicit allocation always overrides auto-assignment rules, so the rule "Engineering Bangalore" is not applied.' },
  { source: 'DEFAULT', initials: 'MN', who: 'Meera Nair', detail: 'HR, Hyderabad. No rule matches', policy: 'Standard Attendance Policy',
    text: 'No explicit allocation or auto-assignment rule applies, so the organization default policy "Standard Attendance Policy" is used.' },
];

const HISTORY = [
  { from: '2026-04-01', to: '2026-09-30', policy: 'Standard Attendance Policy', rule: 'Engineering Bangalore', why: 'Joined Engineering, Bangalore' },
  { from: '2026-10-01', to: '2026-11-24', policy: 'Strict Policy (Support)', rule: 'Night-shift support', why: 'Moved to Customer Support, Night Shift' },
  { from: '2026-11-25', to: null, policy: 'Standard Attendance Policy', rule: 'Engineering Bangalore', why: 'Moved back to Engineering' },
];

export default function AssignmentRulesPreview() {
  return (
    <PreviewChrome
      story="PN-04" icon={GitBranch} title="Auto-assignment rules"
      subtitle="Assign penalization policies automatically by department, location, shift, employment type or grade, so new joiners never need allocating by hand."
      stats={[{ label: 'Rules', value: '4 (3 active)' }, { label: 'Auto-assigned', value: '91 employees' }, { label: 'Precedence', value: 'Allocation > Rule > Existing > Default' }, { label: 'History', value: 'Effective-dated' }]}
    >
      <PreviewSection n={1} title="How a policy is chosen" hint="The first of these that applies decides the employee's policy.">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 12 }}>
          {FLOW.map((f, i) => (
            <div key={f.title} style={{ ...card, padding: '15px 16px', position: 'relative', borderColor: f.strong ? 'color-mix(in srgb, var(--brand) 45%, var(--line))' : 'var(--line)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 7 }}>
                <span style={{ width: 32, height: 32, borderRadius: 10, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: f.strong ? '#fff' : 'var(--brand)', background: f.strong ? 'var(--brand)' : 'color-mix(in srgb, var(--brand) 12%, var(--panel))' }}><f.icon size={16} /></span>
                <span style={{ fontSize: 11, fontWeight: 800, color: 'var(--txt-dim)' }}>0{i + 1}</span>
                {i < FLOW.length - 1 && <ArrowRight size={14} style={{ marginLeft: 'auto', color: 'var(--txt-dim)' }} />}
              </div>
              <b style={{ fontSize: 13.5 }}>{f.title}</b>
              <p style={{ margin: '4px 0 0', fontSize: 12, lineHeight: 1.5, color: 'var(--txt-mut)' }}>{f.text}</p>
            </div>
          ))}
        </div>
      </PreviewSection>

      <PreviewSection n={2} title="Rules" hint="Add, edit, reorder with the arrows and delete. Changes stay local in this preview.">
        <div style={{ ...card, borderTop: '3px solid var(--brand)' }}>
          <AssignmentRulesSection token="mock" mock={MOCK} />
        </div>
      </PreviewSection>

      <PreviewSection n={3} title="Which rule or allocation applied, and why" hint="Shown in the employee's Allocation details and returned by the resolve endpoint.">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 14 }}>
          {EXAMPLES.map((e) => {
            const src = SOURCE[e.source];
            return (
              <div key={e.source} data-testid={'example-' + e.source} style={{ ...card, padding: '16px 18px', borderLeft: `4px solid ${src.color}` }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 11 }}>
                  <span style={{ width: 38, height: 38, borderRadius: '50%', flexShrink: 0, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 12.5, fontWeight: 800, color: src.color, background: `color-mix(in srgb, ${src.color} 14%, var(--panel))` }}>{e.initials}</span>
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 14, fontWeight: 700 }}>{e.who}</div>
                    <div style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>{e.detail}</div>
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', margin: '14px 0 10px' }}>
                  <b style={{ fontSize: 13.5 }}>{e.policy}</b>
                  <span style={{ fontSize: 10, fontWeight: 800, letterSpacing: '.05em', textTransform: 'uppercase', padding: '3px 8px', borderRadius: 999, color: src.color, background: `color-mix(in srgb, ${src.color} 13%, var(--panel))`, border: `1px solid color-mix(in srgb, ${src.color} 30%, var(--line))` }}>{src.label}</span>
                </div>
                <div style={{ fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.6, padding: '10px 12px', borderRadius: 10, background: 'var(--raised)', border: '1px solid var(--line)' }}>
                  <b style={{ color: 'var(--txt)' }}>Why this policy: </b>{e.text}
                </div>
              </div>
            );
          })}
        </div>
      </PreviewSection>

      <PreviewSection n={4} title="Effective-dated history" hint="Rules re-run when an employee's details change. The old record is closed and a new one opened, so past penalties keep the policy they were evaluated under.">
        <div style={{ ...card, padding: '22px 24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16, fontSize: 12, color: 'var(--txt-mut)' }}>
            <History size={14} style={{ color: 'var(--brand)' }} /> Asha Rao, assignment record
          </div>
          <ol style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {HISTORY.map((h, i) => {
              const current = h.to === null;
              return (
                <li key={h.from} data-testid="history-row" style={{ display: 'grid', gridTemplateColumns: '22px minmax(0,1fr)', gap: 14, paddingBottom: i < HISTORY.length - 1 ? 18 : 0 }}>
                  <div style={{ position: 'relative', display: 'flex', justifyContent: 'center' }}>
                    <span style={{ width: 14, height: 14, borderRadius: '50%', marginTop: 3, zIndex: 1, background: current ? 'var(--brand)' : 'var(--panel)', border: `3px solid ${current ? 'color-mix(in srgb, var(--brand) 35%, var(--panel))' : 'var(--line2)'}`, boxSizing: 'content-box' }} />
                    {i < HISTORY.length - 1 && <span style={{ position: 'absolute', top: 22, bottom: -18, width: 2, background: 'var(--line2)' }} />}
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', gap: '4px 14px' }}>
                    <b style={{ fontSize: 13.5 }}>{h.policy}</b>
                    <span style={{ fontSize: 11.5, padding: '2px 9px', borderRadius: 999, fontWeight: 700, color: current ? 'var(--brand)' : 'var(--txt-mut)', background: current ? 'color-mix(in srgb, var(--brand) 12%, var(--panel))' : 'var(--raised)', border: '1px solid var(--line)' }}>
                      {h.from} to {h.to ?? 'now'}{current ? ' (current)' : ''}
                    </span>
                    <span style={{ flexBasis: '100%', fontSize: 12, color: 'var(--txt-mut)' }}>Rule: {h.rule}. {h.why}.</span>
                  </div>
                </li>
              );
            })}
          </ol>
        </div>
      </PreviewSection>
    </PreviewChrome>
  );
}
