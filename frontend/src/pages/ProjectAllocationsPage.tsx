import { useEffect, useMemo, useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import { Search, Users, FolderKanban, CheckCircle2, XCircle, AlertTriangle, ListChecks } from 'lucide-react';
import { TabBar } from '../components/TabBar';
import { EmployeeAvatar } from '../components/EmployeeAvatar';
import { useAuthStore } from '../store/authStore';
import { useCan } from '../lib/access';
import { employeesApi, type EmployeeRecord } from '../api/employees';
import { usePaged } from '../components/usePaged';
import { TablePagination } from '../components/TablePagination';
import { projectsApi, projectAllocationsApi, type ProjectRecord, type AllocationResult, type BulkAllocationPayload } from '../api/projectAllocations';
import ProjectsPanel from './allocations/ProjectsPanel';
import './allocations/allocations.css';

const STATUS_PILL: Record<AllocationResult['status'], { cls: string; label: string; Icon: typeof CheckCircle2 }> = {
  SUCCESS: { cls: 'is-ok', label: 'Success', Icon: CheckCircle2 },
  WARNING: { cls: 'is-warn', label: 'Over capacity', Icon: AlertTriangle },
  FAILED: { cls: 'is-bad', label: 'Failed', Icon: XCircle },
};

function ResultsTable({ results, employees, onConfirm, confirming }: {
  results: AllocationResult[]; employees: EmployeeRecord[]; onConfirm: () => void; confirming: boolean;
}) {
  const can = useCan();
  const nameFor = (id: string) => employees.find(e => e.userId === id)?.fullName ?? id;
  const successCount = results.filter(r => r.status === 'SUCCESS').length;
  const warningCount = results.filter(r => r.status === 'WARNING').length;
  const failedCount = results.length - successCount - warningCount;
  const clean = failedCount === 0 && warningCount === 0;
  const resultsPaged = usePaged(results);
  return (
    <div className="nf-pa-card nf-pa-results">
      <div className="nf-pa-head">
        <span className={`nf-pa-ico ${clean ? 'is-ok' : 'is-warn'}`}>{clean ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}</span>
        <div className="nf-pa-titles">
          <span>{successCount} of {results.length} allocated successfully</span>
          <small>{clean ? 'Every employee was allocated.' : `${failedCount + warningCount} could not be allocated. See the reason for each below.`}</small>
        </div>
        <span className="nf-pa-count is-ok">{successCount} done</span>
        {warningCount > 0 && <span className="nf-pa-count is-warn">{warningCount} over capacity</span>}
        {failedCount > 0 && <span className="nf-pa-count is-bad">{failedCount} failed</span>}
      </div>
      {warningCount > 0 && (
        <div role="alert" className="nf-pa-alert is-warn nf-pa-confirm">
          <AlertTriangle size={14} />
          <span>
            {warningCount} employee{warningCount === 1 ? ' would' : 's would'} go over 100% capacity and {warningCount === 1 ? 'was' : 'were'} not allocated.
          </span>
          {can('project_allocations', 'CREATE') && (
          <button type="button" onClick={onConfirm} disabled={confirming} className="nf-pa-primary is-sm">
            {confirming ? 'Allocating…' : 'Allocate anyway'}
          </button>
          )}
        </div>
      )}
      <div style={{ overflowX: 'auto' }}>
        <table className="nf-pa-table">
          <thead>
            <tr><th>Employee</th><th>Status</th><th>Reason</th></tr>
          </thead>
          <tbody>
            {resultsPaged.pageRows.map(r => (
              <tr key={r.employeeId}>
                <td className="is-strong">{nameFor(r.employeeId)}</td>
                <td>
                  {(() => { const { cls, label, Icon } = STATUS_PILL[r.status]; return (
                    <span className={`nf-pa-pill ${cls}`}><Icon size={12} />{label}</span>
                  ); })()}
                </td>
                <td>{r.reason ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <TablePagination {...resultsPaged.pagerProps} noun="results" />
    </div>
  );
}

export default function ProjectAllocationsPage() {
  const can = useCan();
  const token = useAuthStore(s => s.token) ?? '';

  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [tab, setTab] = useState<'allocate' | 'projects'>('allocate');

  const [employeeSearch, setEmployeeSearch] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [projectId, setProjectId] = useState('');
  const [capacityPercent, setCapacityPercent] = useState(100);
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');

  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState('');
  const [results, setResults] = useState<AllocationResult[] | null>(null);
  const [lastRequest, setLastRequest] = useState<Omit<BulkAllocationPayload, 'employeeIds'> | null>(null);
  const [confirming, setConfirming] = useState(false);

  function load() {
    setLoading(true);
    setLoadError('');
    // Independent loads: one failing (e.g. employee list) must not lock the whole page.
    Promise.allSettled([employeesApi.list(token), projectsApi.list(token)])
      .then(([emps, projs]) => {
        if (emps.status === 'fulfilled') setEmployees(emps.value.filter(e => e.active));
        if (projs.status === 'fulfilled') setProjects(projs.value);
        const failed = [emps, projs].find(r => r.status === 'rejected') as PromiseRejectedResult | undefined;
        if (projs.status === 'rejected' && emps.status === 'rejected') {
          setLoadError(failed?.reason instanceof Error ? failed.reason.message : 'Failed to load employees/projects');
        } else if (failed) {
          setFormError(failed.reason instanceof Error ? failed.reason.message : 'Some data failed to load');
        }
      })
      .finally(() => setLoading(false));
  }

  useEffect(load, [token]);

  const filteredEmployees = useMemo(() => {
    const q = employeeSearch.trim().toLowerCase();
    if (!q) return employees;
    return employees.filter(e => e.fullName.toLowerCase().includes(q) || e.employeeCode.toLowerCase().includes(q));
  }, [employees, employeeSearch]);

  function toggle(id: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  // Only ACTIVE projects accept allocations (ON_HOLD/CLOSED are rejected by the backend too).
  const activeProjects = useMemo(() => {
    const today = new Date().toLocaleDateString('en-CA'); // YYYY-MM-DD, local
    return projects.filter(p => p.status === 'ACTIVE' && (!p.endDate || p.endDate >= today));
  }, [projects]);

  function handleProjectSaved(saved: ProjectRecord) {
    setProjects(prev => {
      const next = prev.some(p => p.id === saved.id) ? prev.map(p => (p.id === saved.id ? saved : p)) : [...prev, saved];
      return next.sort((a, b) => a.name.localeCompare(b.name));
    });
    if (saved.id === projectId && saved.status !== 'ACTIVE') setProjectId('');
  }

  async function handleSubmit() {
    setFormError('');
    if (selected.size === 0) { setFormError('Select at least one employee'); return; }
    if (!projectId) { setFormError('Select a project'); return; }
    if (!startDate || !endDate) { setFormError('Start and end dates are required'); return; }

    setSubmitting(true);
    setResults(null);
    try {
      const res = await projectAllocationsApi.bulkAllocate(token, {
        employeeIds: Array.from(selected),
        projectId,
        capacityPercent,
        startDate,
        endDate,
      });
      setResults(res.results);
      setLastRequest({ projectId, capacityPercent, startDate, endDate });
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Bulk allocation failed');
    } finally {
      setSubmitting(false);
    }
  }

  // US-B06 confirm-to-proceed: resubmit only the WARNING rows, with the same values they were checked against.
  async function handleConfirmWarnings() {
    if (!results || !lastRequest) return;
    const warned = results.filter(r => r.status === 'WARNING').map(r => r.employeeId);
    setConfirming(true);
    try {
      const res = await projectAllocationsApi.bulkAllocate(token, { ...lastRequest, employeeIds: warned, confirm: true });
      const byId = new Map(res.results.map(r => [r.employeeId, r]));
      setResults(prev => prev?.map(r => byId.get(r.employeeId) ?? r) ?? null);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Allocation failed');
    } finally {
      setConfirming(false);
    }
  }

  if (loading) return <div style={{ padding: 24, color: 'var(--txt-mut)' }}>Loading…</div>;
  if (loadError) return <div style={{ padding: 24, color: 'var(--risk)' }}>{loadError}</div>;

  return (
    <div>
      <PageTitleBlock card icon={FolderKanban} style={{ marginBottom: 18 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Project Allocations</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          Maintain projects, and allocate multiple employees to a project in one go - each is validated independently.
        </p>
      </PageTitleBlock>

      <TabBar
        ariaLabel="Project allocation sections"
        className="nf-pilltabs"
        style={{ marginBottom: 16 }}
        active={tab}
        onChange={setTab}
        tabs={[
          { key: 'allocate', label: 'Allocate' },
          { key: 'projects', label: 'Projects', count: projects.length },
        ]}
      />

      {tab === 'projects' && (
        <ProjectsPanel token={token} projects={projects} employees={employees} onSaved={handleProjectSaved} />
      )}

      {tab === 'allocate' && (<>

      <div className="nf-pa-grid">
        {/* Employee multi-select */}
        <div className="nf-pa-card">
          <div className="nf-pa-head">
            <span className="nf-pa-ico"><Users size={16} /></span>
            <div className="nf-pa-titles">
              <span>Select Employees</span>
              <small>Tick everyone who should join the project</small>
            </div>
            <span className={`nf-pa-count${selected.size > 0 ? ' is-on' : ''}`}>{selected.size} selected</span>
          </div>
          <div className="nf-pa-body">
            <div className="nf-pa-search">
              <Search size={14} />
              <input
                placeholder="Search by name or employee code"
                value={employeeSearch}
                onChange={e => setEmployeeSearch(e.target.value)}
              />
            </div>
            <div className="nf-pa-list">
              {filteredEmployees.map(e => (
                <label key={e.userId} className={selected.has(e.userId) ? 'nf-pa-emp is-on' : 'nf-pa-emp'}>
                  <input type="checkbox" checked={selected.has(e.userId)} onChange={() => toggle(e.userId)} />
                  <EmployeeAvatar userId={e.userId} name={e.fullName} size={32} fontSize={11} background="var(--brand)" />
                  <span className="nf-pa-emp-main">
                    <b>{e.fullName}</b>
                    <small>{[e.designationName, e.departmentName].filter(Boolean).join(' · ') || e.employeeCode}</small>
                  </span>
                  <span className="nf-pa-code">{e.employeeCode}</span>
                </label>
              ))}
              {filteredEmployees.length === 0 && (
                <div className="nf-pa-empty"><Search size={20} />No employees match</div>
              )}
            </div>
          </div>
        </div>

        {/* Allocation details */}
        <div className="nf-pa-card">
          <div className="nf-pa-head">
            <span className="nf-pa-ico"><ListChecks size={16} /></span>
            <div className="nf-pa-titles">
              <span>Allocation Details</span>
              <small>{selected.size === 0 ? 'No employees selected yet' : `${selected.size} employee${selected.size === 1 ? '' : 's'} will be allocated`}</small>
            </div>
          </div>
          <div className="nf-pa-body nf-pa-form">
            <div className="nf-pa-field">
              <label>Project</label>
              <select value={projectId} onChange={e => setProjectId(e.target.value)} className="nf-pa-input">
                <option value="">Select a project</option>
                {activeProjects.map(p => <option key={p.id} value={p.id}>{p.code} · {p.name}</option>)}
              </select>
              <small>Only active projects are listed. Add or edit projects in the Projects tab.</small>
            </div>

            <div className="nf-pa-field">
              <label>Capacity %</label>
              <div className="nf-pa-suffix">
                <input type="number" min={1} max={100} value={capacityPercent}
                  onChange={e => setCapacityPercent(Number(e.target.value))} className="nf-pa-input" />
                <span>%</span>
              </div>
            </div>

            <div className="nf-pa-two">
              <div className="nf-pa-field">
                <label>Start Date</label>
                <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className="nf-pa-input" />
              </div>
              <div className="nf-pa-field">
                <label>End Date</label>
                <input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className="nf-pa-input" />
              </div>
            </div>

            {formError && (
              <div role="alert" className="nf-pa-alert"><AlertTriangle size={14} />{formError}</div>
            )}

            {can('project_allocations', 'CREATE') && (
            <button type="button" onClick={handleSubmit} disabled={submitting} className="nf-pa-primary">
              {submitting ? 'Allocating…' : `Allocate ${selected.size || ''} Employee${selected.size === 1 ? '' : 's'}`}
            </button>
            )}
          </div>
        </div>
      </div>

      {results && <ResultsTable results={results} employees={employees} onConfirm={handleConfirmWarnings} confirming={confirming} />}
      </>)}
    </div>
  );
}
