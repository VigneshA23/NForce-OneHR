import { Check } from 'lucide-react';

// Non-interactive progress indicator for the four-step employee creation wizard — deliberately
// not clickable (no free-jump between steps, unlike components/TabBar.tsx): Continue is the only
// way to advance, and it's gated on the current step's own validation (see CreateEmployeeWizard's
// handleContinue), so a step can never be skipped by clicking ahead here.
const STEP_LABELS = ['Basic Information', 'Employment & Job Details', 'Work & Policy Configuration', 'Compensation'];

export function WizardSteps({ current }: { current: number }) {
  return (
    <ol className="nf-ce-steps" aria-label="Create employee progress">
      {STEP_LABELS.map((label, i) => {
        const completed = i < current;
        const active = i === current;
        return (
          <li key={label} className={`nf-ce-step${completed ? ' is-done' : ''}${active ? ' is-on' : ''}`} aria-current={active ? 'step' : undefined}>
            <span className="nf-ce-step-dot">{completed ? <Check size={13} strokeWidth={3} /> : i + 1}</span>
            <span className="nf-ce-step-label">
              <small>Step {i + 1}</small>
              {label}
            </span>
            {i < STEP_LABELS.length - 1 && <span className="nf-ce-step-line" aria-hidden="true" />}
          </li>
        );
      })}
    </ol>
  );
}
