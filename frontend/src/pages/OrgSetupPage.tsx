import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Building2, Briefcase, FileText, MapPin, Plus, Search, X } from 'lucide-react';
import { KebabMenu, type KebabItem } from '../components/KebabMenu';
import type { LucideIcon } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { orgApi, type DepartmentRow, type DesignationRow, type LocationRow } from '../api/org';
import {
  listAllDocTypes, createDocType, updateDocType, toggleDocTypeActive, deleteDocType,
  type DocumentType,
} from '../api/documents';

type OrgTab = 'departments' | 'designations' | 'locations' | 'doctypes';

interface TabDef {
  label: string;
  icon: LucideIcon;
  columns: string[];
  addLabel: string;
  emptyLine: string;
}

const LEVEL_OPTIONS = ['L1', 'L2', 'L3', 'L4', 'L5'];

const TABS: Record<OrgTab, TabDef> = {
  departments: {
    label: 'Departments', icon: Building2,
    columns: ['Name', 'Employees', 'Status'],
    addLabel: 'Add Department',
    emptyLine: 'No departments configured yet. Add one to get started.',
  },
  designations: {
    label: 'Designations', icon: Briefcase,
    columns: ['Title', 'Grade / Band', 'Level', 'Employees', 'Status'],
    addLabel: 'Add Designation',
    emptyLine: 'No designations defined yet. Add a title to assign to employees.',
  },
  locations: {
    label: 'Locations', icon: MapPin,
    columns: ['Name', 'City', 'State / Province', 'Country', 'Employees', 'Status'],
    addLabel: 'Add Location',
    emptyLine: 'No office locations configured yet. Add one to enable location-based features.',
  },
  doctypes: {
    label: 'Document Types', icon: FileText,
    columns: ['Name', 'Needs Verification', 'Needs Expiry', 'Employment Types', 'Locations', 'Usage', 'Status'],
    addLabel: 'Add Document Type',
    emptyLine: 'No document types configured yet. Add one to start collecting employee documents.',
  },
};

const PATH_COPY: Record<string, { title: string; tagline: string }> = {
  '/organization': {
    title: 'Organization Structure',
    tagline: "Manage your company's departments, designations, and locations.",
  },
  '/masters': {
    title: 'Organization Masters',
    tagline: 'Configure global master data that all modules and roles reference.',
  },
};

const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)',
  outline: 'none', width: '100%', boxSizing: 'border-box',
};

const labelStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 5 };
const labelTextStyle: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' };

function StatusBadge({ active }: { active: boolean }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
      background: active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
      color: active ? 'var(--ok)' : 'var(--txt-dim)',
    }}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

function CountBadge({ count }: { count: number }) {
  return (
    <span style={{
      fontFamily: '"JetBrains Mono", monospace', fontSize: 12,
      color: count > 0 ? 'var(--txt-mut)' : 'var(--txt-dim)',
    }}>
      {count}
    </span>
  );
}


// ── ConfirmModal ───────────────────────────────────────────────────────────────

interface ConfirmModalProps {
  title: string;
  body: string;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => Promise<void>;
  onClose: () => void;
}

function ConfirmModal({ title, body, confirmLabel, danger, onConfirm, onClose }: ConfirmModalProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function handleConfirm() {
    setLoading(true);
    setError('');
    try {
      await onConfirm();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong');
      setLoading(false);
    }
  }

  return (
    <div
      role="dialog" aria-modal="true"
      style={{
        position: 'fixed', inset: 0, zIndex: 200,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
      }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div style={{
        background: 'var(--panel)', border: '1px solid var(--line)',
        borderRadius: 12, padding: '24px 28px', width: 400, maxWidth: '95vw',
        boxShadow: '0 24px 48px rgba(0,0,0,.4)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>
            {title}
          </h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}>
            <X size={16} />
          </button>
        </div>
        <p style={{ margin: '0 0 20px', fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.55 }}>{body}</p>
        {error && (
          <div role="alert" style={{
            background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)',
            borderRadius: 6, padding: '8px 12px', marginBottom: 16,
            color: 'var(--risk)', fontSize: 12.5,
          }}>{error}</div>
        )}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button type="button" onClick={onClose} style={{
            padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)',
            borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer',
          }}>
            Cancel
          </button>
          <button type="button" onClick={handleConfirm} disabled={loading} style={{
            padding: '7px 16px',
            background: danger ? 'var(--risk)' : 'var(--brand)',
            border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff',
            cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1,
          }}>
            {loading ? 'Please wait…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── AddEditModal ───────────────────────────────────────────────────────────────

interface AddEditModalProps {
  tab: OrgTab;
  editRow?: DepartmentRow | DesignationRow | LocationRow;
  onClose: () => void;
  onSaved: () => void;
  token: string;
}

function AddEditModal({ tab, editRow, onClose, onSaved, token }: AddEditModalProps) {
  const isEdit = !!editRow;
  const primaryLabel = tab === 'designations' ? 'Title' : 'Name';
  const modalTitle = isEdit ? `Edit ${TABS[tab].label.slice(0, -1)}` : TABS[tab].addLabel;

  const [name, setName] = useState(() => {
    if (!editRow) return '';
    return tab === 'designations' ? (editRow as DesignationRow).title : (editRow as DepartmentRow | LocationRow).name;
  });
  const [grade, setGrade] = useState(() => (editRow && tab === 'designations' ? ((editRow as DesignationRow).grade ?? '') : ''));
  const [level, setLevel] = useState(() => (editRow && tab === 'designations' ? ((editRow as DesignationRow).level ?? '') : ''));
  const [city, setCity] = useState(() => (editRow && tab === 'locations' ? ((editRow as LocationRow).city ?? '') : ''));
  const [state, setState] = useState(() => (editRow && tab === 'locations' ? ((editRow as LocationRow).state ?? '') : ''));
  const [country, setCountry] = useState(() => (editRow && tab === 'locations' ? ((editRow as LocationRow).country ?? '') : ''));
  const [holidayRegion, setHolidayRegion] = useState(() => (editRow && tab === 'locations' ? ((editRow as LocationRow).holidayRegion ?? '') : ''));
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const firstRef = useRef<HTMLInputElement>(null);

  useEffect(() => { firstRef.current?.focus(); }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const trimmed = name.trim();
    if (!trimmed) { setError(`${primaryLabel} is required`); return; }
    if (/\d/.test(trimmed)) { setError(`${primaryLabel} cannot contain numbers`); return; }
    setLoading(true);
    try {
      if (isEdit && editRow) {
        if (tab === 'departments') {
          await orgApi.updateDepartment(token, editRow.id, trimmed);
        } else if (tab === 'designations') {
          await orgApi.updateDesignation(token, editRow.id, {
            title: trimmed, grade: grade.trim() || undefined, level: level || undefined,
          });
        } else {
          await orgApi.updateLocation(token, editRow.id, {
            name: trimmed,
            city: city.trim() || undefined, state: state.trim() || undefined,
            country: country.trim() || undefined, holidayRegion: holidayRegion.trim() || undefined,
          });
        }
      } else {
        if (tab === 'departments') {
          await orgApi.createDepartment(token, trimmed);
        } else if (tab === 'designations') {
          await orgApi.createDesignation(token, trimmed, grade.trim() || undefined, level || undefined);
        } else {
          await orgApi.createLocation(token, {
            name: trimmed,
            city: city.trim() || undefined, state: state.trim() || undefined,
            country: country.trim() || undefined, holidayRegion: holidayRegion.trim() || undefined,
          });
        }
      }
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div
      role="dialog" aria-modal="true" aria-label={modalTitle}
      style={{
        position: 'fixed', inset: 0, zIndex: 200,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
      }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div style={{
        background: 'var(--panel)', border: '1px solid var(--line)',
        borderRadius: 12, padding: '24px 28px', width: 440, maxWidth: '95vw',
        boxShadow: '0 24px 48px rgba(0,0,0,.4)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>
            {modalTitle}
          </h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}>
            <X size={16} />
          </button>
        </div>

        {error && (
          <div role="alert" style={{
            background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)',
            borderRadius: 6, padding: '8px 12px', marginBottom: 16,
            color: 'var(--risk)', fontSize: 12.5,
          }}>{error}</div>
        )}

        <form onSubmit={submit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <label style={labelStyle}>
            <span style={labelTextStyle}>
              {primaryLabel} <span style={{ color: 'var(--risk)' }}>*</span>
            </span>
            <input
              ref={firstRef} value={name} onChange={e => setName(e.target.value)}
              placeholder={
                tab === 'designations' ? 'e.g. Senior Software Engineer'
                : tab === 'departments' ? 'e.g. Engineering'
                : 'e.g. Chennai HQ'
              }
              style={inputStyle}
            />
          </label>

          {tab === 'designations' && (
            <>
              <label style={labelStyle}>
                <span style={labelTextStyle}>Grade / Band</span>
                <input value={grade} onChange={e => setGrade(e.target.value)}
                  placeholder="e.g. G5" style={inputStyle} />
              </label>
              <label style={labelStyle}>
                <span style={labelTextStyle}>Level</span>
                <select value={level} onChange={e => setLevel(e.target.value)}
                  style={{ ...inputStyle, appearance: 'none' as const, WebkitAppearance: 'none' as const }}>
                  <option value="">— Not set —</option>
                  {LEVEL_OPTIONS.map(l => <option key={l} value={l}>{l}</option>)}
                </select>
              </label>
            </>
          )}

          {tab === 'locations' && (
            <>
              <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <label style={labelStyle}>
                  <span style={labelTextStyle}>City</span>
                  <input value={city} onChange={e => setCity(e.target.value)} placeholder="e.g. Chennai" style={inputStyle} />
                </label>
                <label style={labelStyle}>
                  <span style={labelTextStyle}>State / Province</span>
                  <input value={state} onChange={e => setState(e.target.value)} placeholder="e.g. Tamil Nadu" style={inputStyle} />
                </label>
              </div>
              <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <label style={labelStyle}>
                  <span style={labelTextStyle}>Country</span>
                  <input value={country} onChange={e => setCountry(e.target.value)} placeholder="e.g. India" style={inputStyle} />
                </label>
                <label style={labelStyle}>
                  <span style={labelTextStyle}>Holiday Region</span>
                  <input value={holidayRegion} onChange={e => setHolidayRegion(e.target.value)} placeholder="e.g. IN-TN" style={inputStyle} />
                </label>
              </div>
            </>
          )}

          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 4 }}>
            <button type="button" onClick={onClose} style={{
              padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)',
              borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer',
            }}>
              Cancel
            </button>
            <button type="submit" disabled={loading} style={{
              padding: '7px 16px', background: 'var(--brand)', border: 'none',
              borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff',
              cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1,
            }}>
              {loading ? 'Saving…' : isEdit ? 'Update' : 'Save'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

interface ConfirmState {
  title: string;
  body: string;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => Promise<void>;
}

// ── DocTypeModal ───────────────────────────────────────────────────────────────

interface DocTypeModalProps {
  editRow?: DocumentType;
  token: string;
  onClose(): void;
  onSaved(): void;
}

function DocTypeModal({ editRow, token, onClose, onSaved }: DocTypeModalProps) {
  const isEdit = !!editRow;
  const [name, setName] = useState(editRow?.name ?? '');
  const [reqVerify, setReqVerify] = useState(editRow?.requiresVerification ?? true);
  const [reqExpiry, setReqExpiry] = useState(editRow?.requiresExpiryDate ?? false);
  const [empTypes, setEmpTypes] = useState(editRow?.applicableEmploymentTypes ?? '');
  const [locs, setLocs] = useState(editRow?.applicableLocations ?? '');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) { setError('Name is required'); return; }
    setError('');
    setLoading(true);
    try {
      if (isEdit && editRow) {
        await updateDocType(token, editRow.id, {
          name: name.trim(),
          requiresVerification: reqVerify,
          requiresExpiryDate: reqExpiry,
          applicableEmploymentTypes: empTypes.trim() || null,
          applicableLocations: locs.trim() || null,
        });
      } else {
        await createDocType(token, {
          name: name.trim(),
          requiresVerification: reqVerify,
          requiresExpiryDate: reqExpiry,
          applicableEmploymentTypes: empTypes.trim() || null,
          applicableLocations: locs.trim() || null,
        });
      }
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  const inputS: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', width: '100%', boxSizing: 'border-box' };
  const labelS: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };

  return (
    <div role="dialog" aria-modal="true" style={{ position: 'fixed', inset: 0, zIndex: 200, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 460, maxWidth: '94vw' }}>
        <h2 style={{ margin: '0 0 20px', fontSize: 15, fontWeight: 700 }}>{isEdit ? 'Edit Document Type' : 'Add Document Type'}</h2>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Name *</label>
            <input style={inputS} value={name} onChange={e => setName(e.target.value)} required autoFocus />
          </div>
          <div style={{ display: 'flex', gap: 20, marginBottom: 14 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, cursor: 'pointer' }}>
              <input type="checkbox" checked={reqVerify} onChange={e => setReqVerify(e.target.checked)} style={{ width: 15, height: 15 }} />
              Requires HR verification
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, cursor: 'pointer' }}>
              <input type="checkbox" checked={reqExpiry} onChange={e => setReqExpiry(e.target.checked)} style={{ width: 15, height: 15 }} />
              Requires expiry date
            </label>
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Applicable Employment Types <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}>(comma-separated, blank = all)</span></label>
            <input style={inputS} value={empTypes} onChange={e => setEmpTypes(e.target.value)} placeholder="e.g. FULL_TIME,CONTRACT" />
          </div>
          <div style={{ marginBottom: 20 }}>
            <label style={labelS}>Applicable Locations <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}>(comma-separated, blank = all)</span></label>
            <input style={inputS} value={locs} onChange={e => setLocs(e.target.value)} placeholder="e.g. Chennai HQ,Bangalore Office" />
          </div>
          {error && <div style={{ color: 'var(--risk)', fontSize: 12, marginBottom: 12 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading} style={{ padding: '7px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={loading} style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: 'pointer', opacity: loading ? 0.7 : 1 }}>
              {loading ? 'Saving…' : isEdit ? 'Save Changes' : 'Add'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function OrgSetupPage() {
  const { pathname } = useLocation();
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const copy = PATH_COPY[pathname] ?? PATH_COPY['/organization'];

  const [activeTab, setActiveTab] = useState<OrgTab>('departments');
  const [departments, setDepartments] = useState<DepartmentRow[]>([]);
  const [designations, setDesignations] = useState<DesignationRow[]>([]);
  const [locations, setLocations] = useState<LocationRow[]>([]);
  const [docTypes, setDocTypes] = useState<DocumentType[]>([]);
  const [loadError, setLoadError] = useState('');
  const [search, setSearch] = useState('');

  const [addEditModal, setAddEditModal] = useState<{
    open: boolean;
    row?: DepartmentRow | DesignationRow | LocationRow;
    key: number;
  }>({ open: false, key: 0 });
  const [docTypeModal, setDocTypeModal] = useState<{ open: boolean; row?: DocumentType; key: number }>({ open: false, key: 0 });
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);

  const tab = TABS[activeTab];
  const Icon = tab.icon;

  async function fetchAll() {
    setLoadError('');
    try {
      const [deps, desigs, locs, dts] = await Promise.all([
        orgApi.listDepartments(token),
        orgApi.listDesignations(token),
        orgApi.listLocations(token),
        listAllDocTypes(token),
      ]);
      setDepartments(deps);
      setDesignations(desigs);
      setLocations(locs);
      setDocTypes(dts);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load data');
    }
  }

  useEffect(() => { if (token) fetchAll(); }, [token]);
  useEffect(() => { setSearch(''); }, [activeTab]);

  const q = search.toLowerCase();
  const visibleDepts  = departments.filter(d => d.name.toLowerCase().includes(q));
  const visibleDesigs = designations.filter(d => d.title.toLowerCase().includes(q));
  const visibleLocs   = locations.filter(l =>
    l.name.toLowerCase().includes(q) || (l.city ?? '').toLowerCase().includes(q)
  );
  const visibleDocTypes = docTypes.filter(d => d.name.toLowerCase().includes(q));
  const visibleRows =
    activeTab === 'departments' ? visibleDepts :
    activeTab === 'designations' ? visibleDesigs :
    activeTab === 'doctypes' ? visibleDocTypes :
    visibleLocs;

  function openAdd() {
    if (activeTab === 'doctypes') { setDocTypeModal(s => ({ open: true, key: s.key + 1 })); return; }
    setAddEditModal(s => ({ open: true, key: s.key + 1 }));
  }
  function openEdit(row: DepartmentRow | DesignationRow | LocationRow) {
    setAddEditModal(s => ({ open: true, row, key: s.key + 1 }));
  }
  function openEditDocType(dt: DocumentType) {
    setDocTypeModal(s => ({ open: true, row: dt, key: s.key + 1 }));
  }

  function triggerToggleActive(row: DepartmentRow | DesignationRow | LocationRow, label: string) {
    const wasActive = row.active;
    setConfirmState({
      title: wasActive ? `Deactivate ${label}` : `Reactivate ${label}`,
      body: wasActive
        ? `"${label}" will no longer appear in selection lists.`
        : `"${label}" will become available again in selection lists.`,
      confirmLabel: wasActive ? 'Deactivate' : 'Reactivate',
      danger: wasActive,
      onConfirm: async () => {
        if (activeTab === 'departments') await orgApi.toggleDepartmentActive(token, row.id);
        else if (activeTab === 'designations') await orgApi.toggleDesignationActive(token, row.id);
        else await orgApi.toggleLocationActive(token, row.id);
        showToast('success', `"${label}" ${wasActive ? 'deactivated' : 'reactivated'}`);
        await fetchAll();
      },
    });
  }

  function triggerDelete(row: DepartmentRow | DesignationRow | LocationRow, label: string) {
    setConfirmState({
      title: `Delete ${label}`,
      body: `"${label}" will be permanently deleted. This cannot be undone.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: async () => {
        if (activeTab === 'departments') await orgApi.deleteDepartment(token, row.id);
        else if (activeTab === 'designations') await orgApi.deleteDesignation(token, row.id);
        else await orgApi.deleteLocation(token, row.id);
        showToast('success', `"${label}" deleted`);
        await fetchAll();
      },
    });
  }

  function triggerDocTypeToggle(dt: DocumentType) {
    const wasActive = dt.active;
    setConfirmState({
      title: wasActive ? `Deactivate "${dt.name}"` : `Reactivate "${dt.name}"`,
      body: wasActive ? `"${dt.name}" will no longer appear for employees to upload.` : `"${dt.name}" will become available again.`,
      confirmLabel: wasActive ? 'Deactivate' : 'Reactivate',
      danger: wasActive,
      onConfirm: async () => {
        await toggleDocTypeActive(token, dt.id);
        showToast('success', `"${dt.name}" ${wasActive ? 'deactivated' : 'reactivated'}`);
        await fetchAll();
      },
    });
  }

  function triggerDocTypeDelete(dt: DocumentType) {
    if (dt.usageCount > 0) {
      setConfirmState({
        title: `Cannot Delete "${dt.name}"`,
        body: `${dt.usageCount} employee document(s) use this type. Deactivate it instead.`,
        confirmLabel: 'Got it',
        danger: false,
        onConfirm: async () => {},
      });
      return;
    }
    setConfirmState({
      title: `Delete "${dt.name}"`,
      body: `"${dt.name}" will be permanently deleted.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: async () => {
        await deleteDocType(token, dt.id);
        showToast('success', `"${dt.name}" deleted`);
        await fetchAll();
      },
    });
  }

  function kebabItems(row: DepartmentRow | DesignationRow | LocationRow, label: string): KebabItem[] {
    const count = row.employeeCount;
    return [
      { label: 'Edit', onClick: () => openEdit(row) },
      {
        label: row.active ? 'Deactivate' : 'Reactivate',
        onClick: () => triggerToggleActive(row, label),
        dividerBefore: true,
      },
      {
        label: 'Delete',
        danger: true,
        onClick: () => {
          if (count > 0) {
            setConfirmState({
              title: `Cannot Delete "${label}"`,
              body: `${count} employee${count === 1 ? ' is' : 's are'} assigned to this ${activeTab.slice(0, -1)}. Remove all assignments first, or deactivate it instead.`,
              confirmLabel: 'Got it',
              danger: false,
              onConfirm: async () => {},
            });
          } else {
            triggerDelete(row, label);
          }
        },
      },
    ];
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {addEditModal.open && (
        <AddEditModal
          key={addEditModal.key}
          tab={activeTab}
          editRow={addEditModal.row}
          token={token}
          onClose={() => setAddEditModal(s => ({ ...s, open: false }))}
          onSaved={() => {
            fetchAll();
            showToast('success', addEditModal.row ? 'Updated successfully' : 'Created successfully');
          }}
        />
      )}
      {docTypeModal.open && (
        <DocTypeModal
          key={docTypeModal.key}
          editRow={docTypeModal.row}
          token={token}
          onClose={() => setDocTypeModal(s => ({ ...s, open: false }))}
          onSaved={() => {
            fetchAll();
            showToast('success', docTypeModal.row ? 'Updated successfully' : 'Document type created');
          }}
        />
      )}
      {confirmState && (
        <ConfirmModal
          {...confirmState}
          onClose={() => setConfirmState(null)}
        />
      )}

      <div>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, marginBottom: 4 }}>
          {copy.title}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>{copy.tagline}</p>
      </div>

      {loadError && (
        <div role="alert" style={{
          background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)',
          borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13,
        }}>
          {loadError}
        </div>
      )}

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {/* Tab bar + search + add */}
        <div style={{ display: 'flex', borderBottom: '1px solid var(--line)', padding: '0 4px', alignItems: 'center' }}>
          <div style={{ display: 'flex', flex: 1 }}>
            {(Object.keys(TABS) as OrgTab[]).map(key => {
              const T = TABS[key];
              const TabIcon = T.icon;
              const isActive = activeTab === key;
              return (
                <button key={key} onClick={() => setActiveTab(key)} style={{
                  display: 'flex', alignItems: 'center', gap: 6,
                  padding: '11px 14px', background: 'transparent', border: 'none',
                  cursor: 'pointer', fontSize: 12.5,
                  fontWeight: isActive ? 600 : 400,
                  color: isActive ? 'var(--brand-bright)' : 'var(--txt-mut)',
                  borderBottom: isActive ? '2px solid var(--brand-bright)' : '2px solid transparent',
                  marginBottom: -1, transition: 'color 120ms',
                }}>
                  <TabIcon size={13} aria-hidden="true" />
                  {T.label}
                </button>
              );
            })}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px' }}>
            <div style={{ position: 'relative' }}>
              <Search size={12} aria-hidden="true" style={{
                position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)',
                color: 'var(--txt-dim)', pointerEvents: 'none',
              }} />
              <input
                type="search" value={search} onChange={e => setSearch(e.target.value)}
                placeholder={`Search ${tab.label.toLowerCase()}…`}
                aria-label={`Search ${tab.label}`}
                style={{
                  background: 'var(--raised)', border: '1px solid var(--line2)',
                  borderRadius: 6, padding: '5px 10px 5px 26px',
                  fontSize: 12, color: 'var(--txt)', outline: 'none', width: 196,
                }}
              />
            </div>
            <button onClick={openAdd} aria-label={tab.addLabel} style={{
              display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
              background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
              fontSize: 12, fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap',
            }}>
              <Plus size={13} aria-hidden="true" />
              {tab.addLabel}
            </button>
          </div>
        </div>

        {/* Table */}
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
            <thead>
              <tr style={{ background: 'var(--raised)' }}>
                {tab.columns.map(col => (
                  <th key={col} style={{
                    padding: '10px 16px', textAlign: 'left', fontWeight: 600,
                    color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)',
                    whiteSpace: 'nowrap', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase',
                  }}>
                    {col}
                  </th>
                ))}
                <th style={{
                  padding: '10px 16px', textAlign: 'right', fontWeight: 600,
                  color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)',
                  fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase', width: 52,
                }}>
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {visibleRows.length === 0 ? (
                <tr>
                  <td colSpan={tab.columns.length + 1} style={{ padding: '52px 24px', textAlign: 'center' }}>
                    {search ? (
                      <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>
                        No {tab.label.toLowerCase()} match <strong>"{search}"</strong>
                      </div>
                    ) : (
                      <>
                        <Icon size={32} aria-hidden="true" style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 12px' }} />
                        <div style={{ color: 'var(--txt-mut)', fontSize: 13, marginBottom: 14 }}>{tab.emptyLine}</div>
                        <button onClick={openAdd} style={{
                          display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px',
                          background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)',
                          borderRadius: 6, fontSize: 12, fontWeight: 500, cursor: 'pointer',
                        }}>
                          <Plus size={12} aria-hidden="true" />
                          {tab.addLabel}
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ) : activeTab === 'departments' ? (
                visibleDepts.map(d => (
                  <tr key={d.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{d.name}</td>
                    <td style={{ padding: '10px 16px' }}><CountBadge count={d.employeeCount} /></td>
                    <td style={{ padding: '10px 16px' }}><StatusBadge active={d.active} /></td>
                    <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                      <KebabMenu items={kebabItems(d, d.name)} />
                    </td>
                  </tr>
                ))
              ) : activeTab === 'designations' ? (
                visibleDesigs.map(d => (
                  <tr key={d.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{d.title}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{d.grade ?? '—'}</td>
                    <td style={{ padding: '10px 16px', fontFamily: '"JetBrains Mono", monospace', fontSize: 12, color: 'var(--txt-mut)' }}>{d.level ?? '—'}</td>
                    <td style={{ padding: '10px 16px' }}><CountBadge count={d.employeeCount} /></td>
                    <td style={{ padding: '10px 16px' }}><StatusBadge active={d.active} /></td>
                    <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                      <KebabMenu items={kebabItems(d, d.title)} />
                    </td>
                  </tr>
                ))
              ) : activeTab === 'locations' ? (
                visibleLocs.map(l => (
                  <tr key={l.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{l.name}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.city ?? '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.state ?? '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.country ?? '—'}</td>
                    <td style={{ padding: '10px 16px' }}><CountBadge count={l.employeeCount} /></td>
                    <td style={{ padding: '10px 16px' }}><StatusBadge active={l.active} /></td>
                    <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                      <KebabMenu items={kebabItems(l, l.name)} />
                    </td>
                  </tr>
                ))
              ) : (
                visibleDocTypes.map(dt => (
                  <tr key={dt.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{dt.name}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 12 }}>{dt.requiresVerification ? '✓ Yes' : '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 12 }}>{dt.requiresExpiryDate ? '✓ Yes' : '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 11 }}>{dt.applicableEmploymentTypes ?? 'All'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 11 }}>{dt.applicableLocations ?? 'All'}</td>
                    <td style={{ padding: '10px 16px' }}><CountBadge count={dt.usageCount} /></td>
                    <td style={{ padding: '10px 16px' }}><StatusBadge active={dt.active} /></td>
                    <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                      <KebabMenu items={[
                        { label: 'Edit', onClick: () => openEditDocType(dt) },
                        { label: dt.active ? 'Deactivate' : 'Reactivate', onClick: () => triggerDocTypeToggle(dt), dividerBefore: true },
                        { label: 'Delete', danger: true, onClick: () => triggerDocTypeDelete(dt) },
                      ]} />
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
