import { useEffect, useMemo, useState } from 'react';
import { Plus, Pencil, Search } from 'lucide-react';
import type { EmployeeRecord } from '../../api/employees';
import { orgApi, type DepartmentRow } from '../../api/org';
import {
  projectsApi, PROJECT_STATUS_LABEL,
  type ClientRecord, type ProjectPayload, type ProjectRecord, type ProjectStatus,
} from '../../api/projectAllocations';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10 };
const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)', outline: 'none', fontFamily: 'inherit',
  width: '100%', boxSizing: 'border-box',
};
const labelStyle: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };
const th: React.CSSProperties = { textAlign: 'left', padding: '6px 8px', fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' };
const td: React.CSSProperties = { padding: '7px 8px', fontSize: 12.5, color: 'var(--txt)', borderBottom: '1px solid var(--line)' };

const STATUS_COLORS: Record<ProjectStatus, { bg: string; fg: string }> = {
  ACTIVE: { bg: 'rgba(47,182,124,.15)', fg: 'var(--ok)' },
  ON_HOLD: { bg: 'rgba(230,160,40,.15)', fg: 'var(--warn, #c98a12)' },
  CLOSED: { bg: 'rgba(140,140,150,.15)', fg: 'var(--txt-mut)' },
};

// US-B03: only these may own (and therefore allocate on) a project — the backend enforces the same rule.
const OWNER_ROLES = new Set(['MANAGER', 'HR_ADMIN', 'SUPER_ADMIN']);

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

  const owners = useMemo(() => employees.filter(e => OWNER_ROLES.has(e.role)), [employees]);

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
    <div style={{ display: 'grid', gridTemplateColumns: formOpen ? '1fr 340px' : '1fr', gap: 16, alignItems: 'start' }}>
      <div style={{ ...card, padding: '14px 16px', minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div style={{ position: 'relative', flex: 1 }}>
            <Search size={13} style={{ position: 'absolute', left: 9, top: 9, color: 'var(--txt-dim)' }} />
            <input placeholder="Search code, name, client, owner or department" value={search}
              onChange={e => setSearch(e.target.value)} style={{ ...inputStyle, paddingLeft: 28 }} />
          </div>
          <button type="button" onClick={openNew}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '7px 12px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: 'pointer', whiteSpace: 'nowrap' }}>
            <Plus size={13} /> New Project
          </button>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={th}>Code</th>
                <th style={th}>Name</th>
                <th style={th}>Client</th>
                <th style={th}>Department</th>
                <th style={th}>Owner</th>
                <th style={th}>Dates</th>
                <th style={th}>Status</th>
                <th style={th} />
              </tr>
            </thead>
            <tbody>
              {paged.pageRows.map(p => (
                <tr key={p.id}>
                  <td style={{ ...td, fontWeight: 600 }}>{p.code}</td>
                  <td style={td}>{p.name}</td>
                  <td style={{ ...td, color: 'var(--txt-mut)' }}>{p.clientName ?? '–'}</td>
                  <td style={{ ...td, color: 'var(--txt-mut)' }}>{p.departmentName ?? '–'}</td>
                  <td style={{ ...td, color: 'var(--txt-mut)' }}>{p.ownerName ?? '–'}</td>
                  <td style={{ ...td, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>{p.startDate} → {p.endDate ?? 'Ongoing'}</td>
                  <td style={td}>
                    <span style={{ fontSize: 10.5, fontWeight: 700, padding: '2px 8px', borderRadius: 20, background: STATUS_COLORS[p.status].bg, color: STATUS_COLORS[p.status].fg, whiteSpace: 'nowrap' }}>
                      {PROJECT_STATUS_LABEL[p.status]}
                    </span>
                  </td>
                  <td style={{ ...td, textAlign: 'right' }}>
                    <button type="button" onClick={() => openEdit(p)} aria-label={`Edit ${p.name}`}
                      style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 4 }}>
                      <Pencil size={13} />
                    </button>
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr><td colSpan={8} style={{ ...td, textAlign: 'center', color: 'var(--txt-dim)', padding: 16 }}>
                  {projects.length === 0 ? 'No projects yet' : 'No projects match'}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>
        <TablePagination {...paged.pagerProps} noun="projects" />
      </div>

      {formOpen && (
        <div style={{ ...card, padding: '14px 16px' }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>
            {editingId ? 'Edit Project' : 'New Project'}
          </div>

          <label style={labelStyle}>Project Code *</label>
          <input value={form.code} onChange={e => set('code', e.target.value)} maxLength={50} style={{ ...inputStyle, marginBottom: 8 }} />

          <label style={labelStyle}>Project Name *</label>
          <input value={form.name} onChange={e => set('name', e.target.value)} maxLength={255} style={{ ...inputStyle, marginBottom: 8 }} />

          <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
            <div style={{ flex: 1 }}>
              <label style={labelStyle}>Start Date *</label>
              <input type="date" value={form.startDate} onChange={e => set('startDate', e.target.value)} style={inputStyle} />
            </div>
            <div style={{ flex: 1 }}>
              <label style={labelStyle}>End Date</label>
              <input type="date" value={form.endDate ?? ''} onChange={e => set('endDate', e.target.value || null)} style={inputStyle} />
            </div>
          </div>

          <label style={labelStyle}>Owning Department</label>
          <select value={form.departmentId ?? ''} onChange={e => set('departmentId', e.target.value || null)} style={{ ...inputStyle, marginBottom: 8 }}>
            <option value="">None</option>
            {departments.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>

          <label style={labelStyle}>Project Owner (Manager)</label>
          <select value={form.ownerEmployeeId ?? ''} onChange={e => set('ownerEmployeeId', e.target.value || null)} style={{ ...inputStyle, marginBottom: 8 }}>
            <option value="">Unassigned (HR allocates)</option>
            {owners.map(o => <option key={o.userId} value={o.userId}>{o.fullName}</option>)}
          </select>

          <label style={labelStyle}>Client</label>
          <select value={form.clientId ?? ''} onChange={e => set('clientId', e.target.value || null)} style={{ ...inputStyle, marginBottom: 6 }}>
            <option value="">None</option>
            {clients.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
            <input placeholder="New client / cost center" value={newClientName} onChange={e => setNewClientName(e.target.value)} style={{ ...inputStyle, flex: 1 }} />
            <button type="button" onClick={handleAddClient} disabled={!newClientName.trim()}
              style={{ padding: '7px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, color: 'var(--txt)', cursor: newClientName.trim() ? 'pointer' : 'not-allowed' }}>
              Add
            </button>
          </div>

          <label style={labelStyle}>Status</label>
          <select value={form.status} onChange={e => set('status', e.target.value as ProjectStatus)} style={{ ...inputStyle, marginBottom: 4 }}>
            {(Object.keys(PROJECT_STATUS_LABEL) as ProjectStatus[]).map(s => <option key={s} value={s}>{PROJECT_STATUS_LABEL[s]}</option>)}
          </select>
          <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 14 }}>
            Only Active projects accept new allocations. Closing a project keeps its existing allocations.
          </div>

          {error && (
            <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 10px', marginBottom: 12, color: 'var(--risk)', fontSize: 12 }}>
              {error}
            </div>
          )}

          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" onClick={() => setFormOpen(false)} disabled={saving}
              style={{ flex: 1, padding: '9px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 13, color: 'var(--txt)', cursor: 'pointer' }}>
              Cancel
            </button>
            <button type="button" onClick={handleSave} disabled={saving}
              style={{ flex: 1, padding: '9px 14px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 13, fontWeight: 600, color: '#fff', cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? 0.7 : 1 }}>
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
