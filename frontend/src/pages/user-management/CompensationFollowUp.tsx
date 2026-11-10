// Purely informational — rendered on the post-creation success screen (CreateEmployeeWizard.tsx),
// not as a wizard step. OneHR has no working per-employee compensation/payroll backend today
// (CompensationBand/EmployeeCompensationBand are average-CTC market bands for Bench reporting,
// never linked to an individual's actual salary — see those entities' own doc comments), so this
// deliberately has no action, no API call, and no salary fields: just a plain status note. The
// administrator finishes using the existing Done action below it, same as any other field left
// unset at creation (e.g. no Shift/no Weekly-Off Policy).
export function CompensationFollowUp() {
  return (
    <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 14, marginBottom: 16, fontSize: 13, color: 'var(--txt-mut)' }}>
      <div style={{ color: 'var(--txt)', fontWeight: 600, marginBottom: 4 }}>Compensation not configured</div>
      Compensation details haven't been set up for this employee yet. This will be available as a separate step in a future release.
    </div>
  );
}
