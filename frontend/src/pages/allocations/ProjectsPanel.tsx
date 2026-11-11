import { useEffect, useMemo, useState } from 'react';
import { Plus, Pencil, Search, FolderKanban, CalendarDays, AlertTriangle, X } from 'lucide-react';
import type { EmployeeRecord } from '../../api/employees';
import { orgApi, type DepartmentRow } from '../../api/org';
import {
  projectsApi, PROJECT_STATUS_LABEL,
  type ClientRecord, type ProjectPayload, type ProjectRecord, type ProjectStatus,
} from '../../api/projectAllocations';
import { useCan } from '../../lib/access';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import './allocations.css';

const STATUS_TONE: Record<ProjectStatus, string> = { ACTIVE: 'is-ok', ON_HOLD: 'is-warn', CLOSED: 'is-off' };

const EMPTY_FORM: ProjectPayload = {
  code: '', name: '', startDate: '', endDate: null, ownerEmployeeId: null, departmentId: null, clientId: null, status: 'ACTIVE',
};

interface Props {
  token: string;
  projects: ProjectRecord[];
  employees: EmployeeRecord[];
  onSaved: (project: ProjectRecord) => void;
}

/**
 * US-B01 (projects as reference data), US-B02 (client tag) and US-B03 (Manager owner). There is
 * intentionally no delete: closing a project is a status change, so its allocations stay intact.
 */
export default function ProjectsPanel({ token, projects, employees, onSaved }: Props) {
  const can = useCan();
  const [departments, setDepartments] = useState<DepartmentRow[]>([]);
  const [clients, setClients] = useState<ClientRecord[]>([]);
  const [search, setSearch] = useState('');

  const [editingId, setEditingId] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<ProjectPayload>(EMPTY_FORM);
  const [newClientName, setNewClientName] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    Promise.all([orgApi.listDepartments(token), projectsApi.listClients(token)])
      .then(([depts, cls]) => { setDepartments(depts.filter(d => d.active)); setClients(cls); })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load departments/clients'));
  }, [token]);

  // Owner eligibility is decided by the server (ProjectService.requireEligibleOwner: the owner must hold
  // projects:VIEW:TEAM), not by a role name here; a rejected pick is shown through the form's error banner.
  const owners = employees;

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return projects;
    return projects.filter(p => [p.code, p.name, p.clientName, p.ownerName, p.departmentName]
      .some(v => v?.toLowerCase().includes(q)));
  }, [projects, search]);
  const paged = usePaged(filtered);

  function openNew() {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setError('');
    setFormOpen(true);
  }

  function openEdit(p: ProjectRecord) {
    setEditingId(p.id);
    setForm({
      code: p.code, name: p.name, startDate: p.startDate, endDate: p.endDate,
      ownerEmployeeId: p.ownerEmployeeId, departmentId: p.departmentId, clientId: p.clientId, status: p.status,
    });
    setError('');
    setFormOpen(true);
  }

  function set<K extends keyof ProjectPayload>(key: K, value: ProjectPayload[K]) {
    setForm(prev => ({ ...prev, [key]: value }));
  }

  async function handleAddClient() {
    const name = newClientName.trim();
    if (!name) return;
    try {
      const created = await projectsApi.createClient(token, name);
      setClients(prev => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)));
      set('clientId', created.id);
      setNewClientName('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to add client');
    }
  }

  async function handleSave() {
    setError('');
    if (!form.code.trim() || !form.name.trim()) { setError('Code and name are required'); return; }
    if (!form.startDate) { setError('Start date is required'); return; }
    if (form.endDate && form.endDate < form.startDate) { setError('End date cannot be before start date'); return; }

    setSaving(true);
    try {
      const saved = editingId
        ? await projectsApi.update(token, editingId, form)
        : await projectsApi.create(token, form);
      onSaved(saved);
      setFormOpen(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save project');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={formOpen ? 'nf-pa-grid is-projects is-open' : 'nf-pa-grid is-projects'}>
      <div className="nf-pa-card" style={{ minWidth: 0 }}>
        <div className="nf-pa-head">
          <span className="nf-pa-ico"><FolderKanban size={16} /></span>
          <div className="nf-pa-titles">
            <span>Projects</span>
            <small>{projects.length} in total. Close a project to stop new allocations.</small>
          </div>
          {can('projects', 'CREATE') && (
          <button type="button" onClick={openNew} className="nf-pa-primary is-sm">
            <Plus size={14} /> New Project
          </button>
          )}
        </div>
        <div className="nf-pa-body">
          <div className="nf-pa-search">
            <Search size={14} />
            <input placeholder="Search code, name, client, owner or department" value={search}
              onChange={e => setSearch(e.target.value)} />
          </div>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table className="nf-pa-table">
            <thead>
              <tr>
                <th>Code</th>
                <th>Name</th>
                <th>Client</th>
                <th>Department</th>
                <th>Owner</th>
                <th>Dates</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {paged.pageRows.map(p => (
                <tr key={p.id}>
                  <td><span className="nf-pa-codechip">{p.code}</span></td>
                  <td className="is-strong">{p.name}</td>
                  <td>{p.clientName ?? '-'}</td>
                  <td>{p.departmentName ?? '-'}</td>
                  <td>{p.ownerName ?? '-'}</td>
                  <td><span className="nf-pa-dates"><CalendarDays size={12} />{p.startDate} to {p.endDate ?? 'Ongoing'}</span></td>
                  <td>
                    <span className={`nf-pa-pill ${STATUS_TONE[p.status]}`}><i />{PROJECT_STATUS_LABEL[p.status]}</span>
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    {can('projects', 'EDIT') && (
                    <button type="button" onClick={() => openEdit(p)} aria-label={`Edit ${p.name}`} className="nf-pa-edit">
                      <Pencil size={14} />
                    </button>
                    )}
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr><td colSpan={8} className="nf-pa-cellmsg">
                  {projects.length === 0 ? 'No projects yet' : 'No projects match'}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>
        <TablePagination {...paged.pagerProps} noun="projects" />
      </div>

      {formOpen && (
        <div className="nf-pa-card">
          <div className="nf-pa-head">
            <span className="nf-pa-ico">{editingId ? <Pencil size={15} /> : <Plus size={16} />}</span>
            <div className="nf-pa-titles">
              <span>{editingId ? 'Edit Project' : 'New Project'}</span>
              <small>{editingId ? 'Update the project details' : 'Add a project employees can be allocated to'}</small>
            </div>
            <button type="button" className="nf-pa-x" aria-label="Close form" onClick={() => setFormOpen(false)} disabled={saving}><X size={15} /></button>
          </div>
          <div className="nf-pa-body nf-pa-form">
            <div className="nf-pa-field">
              <label>Project Code *</label>
              <input value={form.code} onChange={e => set('code', e.target.value)} maxLength={50} className="nf-pa-input" />
            </div>

            <div className="nf-pa-field">
              <label>Project Name *</label>
              <input value={form.name} onChange={e => set('name', e.target.value)} maxLength={255} className="nf-pa-input" />
            </div>

            <div className="nf-pa-two">
              <div className="nf-pa-field">
                <label>Start Date *</label>
                <input type="date" value={form.startDate} onChange={e => set('startDate', e.target.value)} className="nf-pa-input" />
              </div>
              <div className="nf-pa-field">
                <label>End Date</label>
                <input type="date" value={form.endDate ?? ''} onChange={e => set('endDate', e.target.value || null)} className="nf-pa-input" />
              </div>
            </div>

            <div className="nf-pa-field">
              <label>Owning Department</label>
              <select value={form.departmentId ?? ''} onChange={e => set('departmentId', e.target.value || null)} className="nf-pa-input">
                <option value="">None</option>
                {departments.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </div>

            <div className="nf-pa-field">
              <label>Project Owner (Manager)</label>
              <select value={form.ownerEmployeeId ?? ''} onChange={e => set('ownerEmployeeId', e.target.value || null)} className="nf-pa-input">
                <option value="">Unassigned (HR allocates)</option>
                {owners.map(o => <option key={o.userId} value={o.userId}>{o.fullName}</option>)}
              </select>
            </div>

            <div className="nf-pa-field">
              <label>Client</label>
              <select value={form.clientId ?? ''} onChange={e => set('clientId', e.target.value || null)} className="nf-pa-input">
                <option value="">None</option>
                {clients.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
              {can('projects', 'CREATE') && (
              <div className="nf-pa-addrow">
                <input placeholder="New client / cost center" value={newClientName} onChange={e => setNewClientName(e.target.value)} className="nf-pa-input" />
                <button type="button" onClick={handleAddClient} disabled={!newClientName.trim()} className="nf-pa-ghost">Add</button>
              </div>
              )}
            </div>

            <div className="nf-pa-field">
              <label>Status</label>
              <select value={form.status} onChange={e => set('status', e.target.value as ProjectStatus)} className="nf-pa-input">
                {(Object.keys(PROJECT_STATUS_LABEL) as ProjectStatus[]).map(s => <option key={s} value={s}>{PROJECT_STATUS_LABEL[s]}</option>)}
              </select>
              <small>Only Active projects accept new allocations. Closing a project keeps its existing allocations.</small>
            </div>

            {error && (
              <div role="alert" className="nf-pa-alert"><AlertTriangle size={14} />{error}</div>
            )}

            <div className="nf-pa-btnrow">
              <button type="button" onClick={() => setFormOpen(false)} disabled={saving} className="nf-pa-ghost">Cancel</button>
              <button type="button" onClick={handleSave} disabled={saving} className="nf-pa-primary">{saving ? 'Saving…' : 'Save'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
