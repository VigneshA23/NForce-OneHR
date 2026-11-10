// Non-interactive progress indicator for the four-step employee creation wizard — deliberately
// not clickable (no free-jump between steps, unlike components/TabBar.tsx): Continue is the only
// way to advance, and it's gated on the current step's own validation (see CreateEmployeeWizard's
// handleContinue), so a step can never be skipped by clicking ahead here.
const STEP_LABELS = ['Basic Information', 'Employment & Job Details', 'Work & Policy Configuration', 'Compensation'];

export function WizardSteps({ current }: { current: number }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', padding: '12px 24px 16px', overflowX: 'auto' }}>
      {STEP_LABELS.map((label, i) => {
        const completed = i < current;
        const active = i === current;
        return (
          <div key={label} style={{ display: 'flex', alignItems: 'center', flex: i < STEP_LABELS.length - 1 ? 1 : undefined, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexShrink: 0 }}>
              <span style={{
                width: 20, height: 20, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 10.5, fontWeight: 700, flexShrink: 0, boxSizing: 'border-box',
                background: completed ? 'var(--brand)' : 'transparent',
                border: active ? '1.5px solid var(--brand)' : completed ? 'none' : '1.5px solid var(--line2)',
                color: completed ? '#fff' : active ? 'var(--brand)' : 'var(--txt-dim)',
              }}>
                {completed ? '✓' : i + 1}
              </span>
              <span style={{ fontSize: 12.5, fontWeight: active ? 600 : 500, color: active ? 'var(--txt)' : completed ? 'var(--txt-mut)' : 'var(--txt-dim)', whiteSpace: 'nowrap' }}>
                {label}
              </span>
            </div>
            {i < STEP_LABELS.length - 1 && <div style={{ flex: 1, height: 1, background: completed ? 'var(--brand)' : 'var(--line)', minWidth: 16, margin: '0 10px' }} />}
          </div>
        );
      })}
    </div>
  );
}
