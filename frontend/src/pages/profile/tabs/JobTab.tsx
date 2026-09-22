import { useEffect, useState } from 'react';
import { type ProfileData } from '../../../api/profile';
import { hierarchyApi, type PersonCard } from '../../../api/hierarchy';
import { dashboardApi, type DirectReport } from '../../../api/dashboard';
import { SectionHeader, ReadField } from '../shared';

function Card({ title, badge, children }: { title: string; badge?: string; children: React.ReactNode }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <SectionHeader title={title} badge={badge} />
      {children}
    </div>
  );
}

export function JobTab({ profile, token }: { profile: ProfileData; token: string }) {
  const [l2Manager, setL2Manager] = useState<PersonCard | null | undefined>(undefined);
  const [directReports, setDirectReports] = useState<DirectReport[]>([]);
  const [reportsLoaded, setReportsLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    hierarchyApi.getContext(token, profile.userId)
      .then(ctx => {
        if (cancelled) return;
        if (!ctx.manager) { setL2Manager(null); return; }
        // Manager of manager: re-fetch context focused on the L1 manager, then read *their*
        // manager off the result (same "re-focus and read .manager again" pattern used elsewhere
        // in the org-hierarchy views).
        return hierarchyApi.getContext(token, ctx.manager.id)
          .then(l1Ctx => { if (!cancelled) setL2Manager(l1Ctx.manager); });
      })
      .catch(() => { if (!cancelled) setL2Manager(null); });
    return () => { cancelled = true; };
  }, [token, profile.userId]);

  useEffect(() => {
    dashboardApi.managerDashboard(token)
      .then(d => setDirectReports(d.directReports))
      .catch(() => {})
      .finally(() => setReportsLoaded(true));
  }, [token]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <Card title="Job Details" badge="Read-Only">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Employee ID" value={profile.employeeCode} />
          <ReadField label="Job Title / Job Code" value={profile.designationName ? `${profile.designationName}${profile.jobCode ? ` (${profile.jobCode})` : ''}` : profile.jobCode} />
          <ReadField label="Employment Type" value={profile.employmentType?.replace('_', ' ')} />
          <ReadField label="Employment Status" value={profile.active ? 'Active' : 'Inactive'} />
          <ReadField label="Date of Joining" value={profile.joiningDate} />
          <ReadField label="Probation End / Confirmation Date" value={profile.confirmationDate ? `Confirmed · ${profile.confirmationDate}` : profile.probationEndDate} />
          <ReadField label="Reporting Manager" value={profile.managerName} />
          <ReadField label="Work Mode" value={profile.workMode} />
        </div>
      </Card>

      <Card title="Employee Time" badge="Read-Only">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Shift / Work Schedule" value={profile.shiftName} />
          <ReadField label="Holiday Calendar" value={null} />
          <ReadField label="Attendance Policy Group" value={null} />
          <ReadField label="Attendance Time Tracking Policy" value={null} />
          <ReadField label="Attendance Penalization Policy" value={profile.attendancePenalizationPolicyName} />
        </div>
      </Card>

      <Card title="Organization" badge="Read-Only">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Business Unit / Division" value={profile.businessUnitName} />
          <ReadField label="Department" value={profile.departmentName} />
          <ReadField label="Location / Office" value={profile.locationName} />
          <ReadField label="Reports To" value={profile.managerName} />
          <ReadField label="Manager of Manager (L2)" value={l2Manager === undefined ? undefined : l2Manager?.name ?? null} />
          <div style={{ gridColumn: '1/-1' }}>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 6 }}>Direct Reports</div>
            {!reportsLoaded ? (
              <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>Loading…</div>
            ) : directReports.length === 0 ? (
              <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>No direct reports.</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {directReports.map(r => (
                  <div key={r.userId} style={{ fontSize: 13, color: 'var(--txt)' }}>
                    {r.fullName}
                    {r.designationName && <span style={{ color: 'var(--txt-mut)' }}> — {r.designationName}</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </Card>
    </div>
  );
}
