import { useEffect, useMemo, useState } from 'react';
import { projectAllocationsApi, type EmployeeCapacity } from '../../api/projectAllocations';
import { card, inputStyle, labelStyle, th, td, CardTitle, Empty, ErrorBox } from '../bench/ui';

/**
 * US-B13: team capacity view with optional department/project filters (ANDed server-side).
 * Visibility is enforced by the backend (direct-report hierarchy, same as Leave approvals) — the
 * dropdown options come from the caller's own unfiltered view, so they never list anything outside it.
 */
export default function TeamCapacityTab({ token }: { token: string }) {
  const [all, setAll] = useState<EmployeeCapacity[]>([]);
  const [rows, setRows] = useState<EmployeeCapacity[]>([]);
  const [departmentId, setDepartmentId] = useState('');
  const [projectId, setProjectId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    projectAllocationsApi.capacity(token).then(setAll).catch(() => setAll([]));
  }, [token]);

  useEffect(() => {
    setLoading(true);
    setError('');
    projectAllocationsApi.capacity(token, { departmentId, projectId })
      .then(setRows)
      .catch(e => { setRows([]); setError(e instanceof Error ? e.message : 'Failed to load capacity'); })
      .finally(() => setLoading(false));
  }, [token, departmentId, projectId]);

  const { departments, projects } = useMemo(() => {
    const d = new Map<string, string>(), p = new Map<string, string>();
    all.forEach(r => {
      if (r.departmentId) d.set(r.departmentId, r.departmentName ?? 'Unnamed department');
      r.allocations.forEach(a => p.set(a.projectId, a.projectName ?? 'Unnamed project'));
    });
    const sorted = (m: Map<string, string>) => [...m].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name));
    return { departments: sorted(d), projects: sorted(p) };
  }, [all]);

  const select = { ...inputStyle, width: 220 };

  return (
    <div style={card}>
      <CardTitle>Team Capacity</CardTitle>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 14 }}>
        <div>
          <label style={labelStyle} htmlFor="cap-dept">Department</label>
          <select id="cap-dept" value={departmentId} onChange={e => setDepartmentId(e.target.value)} style={select}>
            <option value="">All departments</option>
            {departments.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle} htmlFor="cap-proj">Project</label>
          <select id="cap-proj" value={projectId} onChange={e => setProjectId(e.target.value)} style={select}>
            <option value="">All projects</option>
            {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
      </div>

      <ErrorBox message={error} />
      {loading ? <Empty>Loading…</Empty> : rows.length === 0 ? <Empty>No team members match these filters.</Empty> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr><th style={th}>Employee</th><th style={th}>Department</th><th style={th}>Projects</th><th style={th}>Total capacity</th></tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.employeeId}>
                  <td style={td}>{r.employeeName}</td>
                  <td style={{ ...td, color: 'var(--txt-mut)' }}>{r.departmentName ?? '–'}</td>
                  <td style={{ ...td, color: 'var(--txt-mut)' }}>
                    {r.allocations.length === 0 ? '–' : r.allocations.map((a, i) => (
                      <div key={i}>{a.projectName ?? 'Unnamed project'} · {a.capacityPercent}% ({a.startDate} → {a.endDate})</div>
                    ))}
                  </td>
                  <td style={{ ...td, fontWeight: 700, color: r.totalCapacityPercent > 100 ? 'var(--risk)' : 'var(--txt)' }}>
                    {r.totalCapacityPercent}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
