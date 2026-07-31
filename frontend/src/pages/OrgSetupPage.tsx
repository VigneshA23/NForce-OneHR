import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Building2, Briefcase, MapPin, Plus, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { orgApi, type DepartmentRow, type DesignationRow, type LocationRow } from '../api/org';

type OrgTab = 'departments' | 'designations' | 'locations';

interface TabDef {
  label: string;
  icon: LucideIcon;
  columns: string[];
  addLabel: string;
  emptyLine: string;
}

const TABS: Record<OrgTab, TabDef> = {
  departments: {
    label: 'Departments',
    icon: Building2,
    columns: ['Name', 'Status'],
    addLabel: 'Add Department',
    emptyLine: 'No departments configured yet. Add one to get started.',
  },
  designations: {
    label: 'Designations',
    icon: Briefcase,
    columns: ['Title', 'Grade / Band', 'Status'],
    addLabel: 'Add Designation',
    emptyLine: 'No designations defined yet. Add a title to assign to employees.',
  },
  locations: {
    label: 'Locations',
    icon: MapPin,
    columns: ['Name', 'City', 'State / Province', 'Country', 'Status'],
    addLabel: 'Add Location',
    emptyLine: 'No office locations configured yet. Add one to enable location-based features.',
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

const STATUS_BADGE = (active: boolean) => (
  <span
    style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
      background: active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
      color: active ? 'var(--ok)' : 'var(--txt-dim)',
    }}
  >
    {active ? 'Active' : 'Inactive'}
  </span>
);

// ── Add Modal ──────────────────────────────────────────────────────────────────

interface ModalProps {
  tab: OrgTab;
  onClose: () => void;
  onCreated: () => void;
  token: string;
}

function AddModal({ tab, onClose, onCreated, token }: ModalProps) {
  const [name, setName]       = useState('');
  const [grade, setGrade]     = useState('');
  const [city, setCity]       = useState('');
  const [state, setState]     = useState('');
  const [country, setCountry] = useState('');
  const [error, setError]     = useState('');
  const [loading, setLoading] = useState(false);
  const firstRef = useRef<HTMLInputElement>(null);

  useEffect(() => { firstRef.current?.focus(); }, []);

  const primaryLabel = tab === 'designations' ? 'Title' : 'Name';

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const trimmed = name.trim();
    if (!trimmed) { setError(`${primaryLabel} is required`); return; }

    setLoading(true);
    try {
      if (tab === 'departments') {
        await orgApi.createDepartment(token, trimmed);
      } else if (tab === 'designations') {
        await orgApi.createDesignation(token, trimmed, grade.trim() || undefined);
      } else {
        await orgApi.createLocation(token, {
          name: trimmed,
          city: city.trim() || undefined,
          state: state.trim() || undefined,
          country: country.trim() || undefined,
        });
      }
      onCreated();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Add ${TABS[tab].label.slice(0, -1)}`}
      style={{
        position: 'fixed', inset: 0, zIndex: 200,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
      }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        style={{
          background: 'var(--panel)', border: '1px solid var(--line)',
          borderRadius: 12, padding: '24px 28px', width: 420, maxWidth: '95vw',
          boxShadow: '0 24px 48px rgba(0,0,0,.4)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>
            {TABS[tab].addLabel}
          </h2>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}
          >
            <X size={16} />
          </button>
        </div>

        {error && (
          <div role="alert" style={{
            background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)',
            borderRadius: 6, padding: '8px 12px', marginBottom: 16,
            color: 'var(--risk)', fontSize: 12.5,
          }}>
            {error}
          </div>
        )}

        <form onSubmit={submit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
            <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>
              {primaryLabel} <span style={{ color: 'var(--risk)' }}>*</span>
            </span>
            <input
              ref={firstRef}
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder={tab === 'designations' ? 'e.g. Senior Software Engineer' : tab === 'departments' ? 'e.g. Engineering' : 'e.g. Chennai HQ'}
              style={{
                background: 'var(--raised)', border: '1px solid var(--line2)',
                borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)',
                outline: 'none', width: '100%', boxSizing: 'border-box',
              }}
            />
          </label>

          {tab === 'designations' && (
            <label style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>Grade / Band</span>
              <input
                value={grade}
                onChange={e => setGrade(e.target.value)}
                placeholder="e.g. L5"
                style={{
                  background: 'var(--raised)', border: '1px solid var(--line2)',
                  borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)',
                  outline: 'none', width: '100%', boxSizing: 'border-box',
                }}
              />
            </label>
          )}

          {tab === 'locations' && (
            <>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>City</span>
                <input value={city} onChange={e => setCity(e.target.value)} placeholder="e.g. Chennai"
                  style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', outline: 'none', width: '100%', boxSizing: 'border-box' }} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>State / Province</span>
                <input value={state} onChange={e => setState(e.target.value)} placeholder="e.g. Tamil Nadu"
                  style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', outline: 'none', width: '100%', boxSizing: 'border-box' }} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)' }}>Country</span>
                <input value={country} onChange={e => setCountry(e.target.value)} placeholder="e.g. India"
                  style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', outline: 'none', width: '100%', boxSizing: 'border-box' }} />
              </label>
            </>
          )}

          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 4 }}>
            <button type="button" onClick={onClose}
              style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
              Cancel
            </button>
            <button type="submit" disabled={loading}
              style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>
              {loading ? 'Saving…' : 'Save'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function OrgSetupPage() {
  const { pathname } = useLocation();
  const token = useAuthStore((s) => s.token) ?? '';
  const copy = PATH_COPY[pathname] ?? PATH_COPY['/organization'];

  const [activeTab, setActiveTab]       = useState<OrgTab>('departments');
  const [departments, setDepartments]   = useState<DepartmentRow[]>([]);
  const [designations, setDesignations] = useState<DesignationRow[]>([]);
  const [locations, setLocations]       = useState<LocationRow[]>([]);
  const [loadError, setLoadError]       = useState('');
  const [modalOpen, setModalOpen]       = useState(false);

  const tab = TABS[activeTab];
  const Icon = tab.icon;

  async function fetchAll() {
    setLoadError('');
    try {
      const [deps, desigs, locs] = await Promise.all([
        orgApi.listDepartments(token),
        orgApi.listDesignations(token),
        orgApi.listLocations(token),
      ]);
      setDepartments(deps);
      setDesignations(desigs);
      setLocations(locs);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load data');
    }
  }

  useEffect(() => { if (token) fetchAll(); }, [token]);

  const rows: unknown[] =
    activeTab === 'departments'  ? departments  :
    activeTab === 'designations' ? designations :
    locations;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {modalOpen && (
        <AddModal
          tab={activeTab}
          token={token}
          onClose={() => setModalOpen(false)}
          onCreated={fetchAll}
        />
      )}

      <div>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, marginBottom: 4 }}>
          {copy.title}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>{copy.tagline}</p>
      </div>

      {loadError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13 }}>
          {loadError}
        </div>
      )}

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {/* Tab bar */}
        <div style={{ display: 'flex', borderBottom: '1px solid var(--line)', padding: '0 4px' }}>
          {(Object.keys(TABS) as OrgTab[]).map((key) => {
            const T = TABS[key];
            const TabIcon = T.icon;
            const isActive = activeTab === key;
            return (
              <button key={key} onClick={() => setActiveTab(key)}
                style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '11px 14px', background: 'transparent', border: 'none', cursor: 'pointer', fontSize: 12.5, fontWeight: isActive ? 600 : 400, color: isActive ? 'var(--brand-bright)' : 'var(--txt-mut)', borderBottom: isActive ? '2px solid var(--brand-bright)' : '2px solid transparent', marginBottom: -1, transition: 'color 120ms' }}>
                <TabIcon size={13} aria-hidden="true" />
                {T.label}
              </button>
            );
          })}
          <div style={{ flex: 1 }} />
          <div style={{ display: 'flex', alignItems: 'center', padding: '0 12px' }}>
            <button
              onClick={() => setModalOpen(true)}
              aria-label={tab.addLabel}
              style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}
            >
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
                {tab.columns.map((col) => (
                  <th key={col} style={{ padding: '10px 16px', textAlign: 'left', fontWeight: 600, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase' }}>
                    {col}
                  </th>
                ))}
                <th style={{ padding: '10px 16px', textAlign: 'right', fontWeight: 600, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase' }}>
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 ? (
                <tr>
                  <td colSpan={tab.columns.length + 1} style={{ padding: '52px 24px', textAlign: 'center' }}>
                    <Icon size={32} aria-hidden="true" style={{ color: 'var(--line2)', display: 'block', margin: '0 auto 12px' }} />
                    <div style={{ color: 'var(--txt-mut)', fontSize: 13, marginBottom: 14 }}>{tab.emptyLine}</div>
                    <button onClick={() => setModalOpen(true)}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px', background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, fontWeight: 500, cursor: 'pointer' }}>
                      <Plus size={12} aria-hidden="true" />
                      {tab.addLabel}
                    </button>
                  </td>
                </tr>
              ) : activeTab === 'departments' ? (
                (departments as DepartmentRow[]).map((d) => (
                  <tr key={d.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{d.name}</td>
                    <td style={{ padding: '10px 16px' }}>{STATUS_BADGE(d.active)}</td>
                    <td style={{ padding: '10px 16px', textAlign: 'right', color: 'var(--txt-dim)', fontSize: 12 }}>—</td>
                  </tr>
                ))
              ) : activeTab === 'designations' ? (
                (designations as DesignationRow[]).map((d) => (
                  <tr key={d.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{d.title}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{d.grade ?? '—'}</td>
                    <td style={{ padding: '10px 16px' }}>{STATUS_BADGE(d.active)}</td>
                    <td style={{ padding: '10px 16px', textAlign: 'right', color: 'var(--txt-dim)', fontSize: 12 }}>—</td>
                  </tr>
                ))
              ) : (
                (locations as LocationRow[]).map((l) => (
                  <tr key={l.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{l.name}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.city ?? '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.state ?? '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.country ?? '—'}</td>
                    <td style={{ padding: '10px 16px' }}>{STATUS_BADGE(l.active)}</td>
                    <td style={{ padding: '10px 16px', textAlign: 'right', color: 'var(--txt-dim)', fontSize: 12 }}>—</td>
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
