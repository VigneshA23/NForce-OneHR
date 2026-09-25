import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation } from 'react-router-dom';
import {
  Building2, Building, Briefcase, FileText, MapPin, ShieldAlert, Plus, Search, X, Clock,
  CalendarDays, Bot, Wallet, ShieldCheck, Activity, UsersRound, Crown, Handshake, Megaphone,
  Cpu, Landmark, Wrench, BriefcaseBusiness, UserCog, TestTube, Code, UserRound, Home, Globe,
  Sunrise, Sun, Sunset, Moon, Timer, IdCard, FileSignature, FileCheck, Award, File,
  ClipboardCheck, CalendarClock, CalendarX2, CalendarCheck2,
} from 'lucide-react';
import { KebabMenu, type KebabItem } from '../components/KebabMenu';
import type { LucideIcon } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  orgApi, type BusinessUnitRow, type DepartmentRow, type DesignationRow, type LocationRow,
  type ShiftRow, type ShiftEmployeeRow, type ShiftVersionRow, type WeeklyOffPolicyRow,
} from '../api/org';
import {
  listAllDocTypes, createDocType, updateDocType, toggleDocTypeActive, deleteDocType,
  type DocumentType,
} from '../api/documents';
import { leaveApi, type LeaveType, type LeaveTypeClassification } from '../api/leave';
import * as aiAssistantApi from '../api/aiAssistant';
import PolicyListSection from './penalization/PolicyListSection';
import PenalizationPolicyAllocationSection from './penalization/PenalizationPolicyAllocationSection';
import { inactiveDimStyle } from '../components/EmployeeStatus';

// 'shiftweeklyoff' is ONE top-level Organization Masters tab — Shifts/Weekly Offs/Shift and
// Weekly Off Rules live as nested sub-tabs inside it (see shiftWeeklyOffSubTab below), matching
// the approved reference design. They must never become separate top-level entries here again.
type OrgTab = 'businessunits' | 'departments' | 'designations' | 'locations' | 'shiftweeklyoff' | 'doctypes' | 'leavetypes' | 'penalization' | 'attendance' | 'ai-assistant';

interface TabDef {
  label: string;
  icon: LucideIcon;
  columns: string[];
  addLabel: string;
  emptyLine: string;
}

const LEVEL_OPTIONS = ['L1', 'L2', 'L3', 'L4', 'L5'];

const TABS: Record<OrgTab, TabDef> = {
  businessunits: {
    label: 'Business Units', icon: Building,
    columns: ['Name', 'Employees', 'Status'],
    addLabel: 'Add Business Unit',
    emptyLine: 'No business units configured yet. Add one to get started.',
  },
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
    columns: ['Name', 'City', 'State / Province', 'Country', 'Timezone', 'Employees', 'Status'],
    addLabel: 'Add Location',
    emptyLine: 'No office locations configured yet. Add one to enable location-based features.',
  },
  // A parent tab with its own nested sub-tab bar (Shifts / Weekly Offs / Shift and Weekly Off
  // Rules — see shiftWeeklyOffSubTab), not a row-per-item table like the tabs above — mirrors how
  // Penalization Policy below manages its own policy/allocation sub-tabs. columns/addLabel/
  // emptyLine are unused here; "Add Shift" (the only add action any sub-tab needs) is rendered
  // directly, conditioned on the active sub-tab, not driven by this generic addLabel.
  shiftweeklyoff: {
    label: 'Shift & Weekly Off', icon: Clock,
    columns: [], addLabel: '', emptyLine: '',
  },
  doctypes: {
    label: 'Document Types', icon: FileText,
    columns: ['Name', 'Needs Verification', 'Needs Expiry', 'Employment Types', 'Locations', 'Usage', 'Status'],
    addLabel: 'Add Document Type',
    emptyLine: 'No document types configured yet. Add one to start collecting employee documents.',
  },
  leavetypes: {
    label: 'Leave', icon: CalendarDays,
    columns: ['Leave Type', 'Code', 'Classification'],
    addLabel: 'Add Leave Type',
    emptyLine: 'No leave types configured yet. Add one to start collecting leave requests.',
  },
  // Not a row-per-item table like the other tabs above — the Policy List (Section 5), rendered
  // by PolicyListSection, which in turn opens PenalizationPolicySection per-policy for editing.
  // columns/addLabel/emptyLine are unused for this tab (see the search/add-button and
  // table-vs-section guards below).
  penalization: {
    label: 'Penalization Policy', icon: ShieldAlert,
    columns: [], addLabel: '', emptyLine: '',
  },
  // A single-setting form (AttendanceRulesSection), not a row-per-item table — same shape as the
  // "Shift and Weekly Off Rules" sub-tab, but a separate top-level tab since Half Day Max Hours
  // is a general attendance-classification rule, not a shift/weekly-off concept. columns/addLabel/
  // emptyLine are unused for this tab (see the search/add-button and table-vs-section guards below).
  attendance: {
    label: 'Attendance Rules', icon: ClipboardCheck,
    columns: [], addLabel: '', emptyLine: '',
  },
  // A single-setting form (AiRateLimitSettingsSection), same shape as Attendance Rules above. Read
  // AND write of this one are Super-Admin-only server-side (unlike every other tab here, which HR
  // Admin can at least view via /organization) - see the tab-bar filter below, which hides this
  // entry entirely rather than showing a tab that would 403 on load. columns/addLabel/emptyLine
  // are unused, same reason as attendance.
  'ai-assistant': {
    label: 'AI Assistant', icon: Bot,
    columns: [], addLabel: '', emptyLine: '',
  },
};

// Presentational accent per tab — reuses the app's existing status/brand tokens plus a handful
// of restrained additions (index.css --om-*) so each of the 10 sections reads as its own visual
// category at a glance, without turning the tab bar into a rainbow.
const TAB_ACCENT: Record<OrgTab, string> = {
  businessunits: 'var(--info)',
  departments: 'var(--ok)',
  designations: 'var(--om-purple)',
  locations: 'var(--om-teal)',
  shiftweeklyoff: 'var(--warn)',
  doctypes: 'var(--om-indigo)',
  leavetypes: 'var(--om-rose)',
  penalization: 'var(--risk)',
  attendance: 'var(--om-cyan)',
  'ai-assistant': 'var(--om-violet)',
};

// ── Semantic row icons ──────────────────────────────────────────────────────
// Each entity's row icon is resolved from its real data (name/timing/etc.) via an ordered
// keyword table — first matching rule wins — rather than one fixed icon per tab. Keeps the
// table scannable at a glance without hardcoding per-record icons or inventing any data.
interface IconRule { test: RegExp; icon: LucideIcon; accent: string }

function resolveIcon(rules: IconRule[], text: string, fallback: { icon: LucideIcon; accent: string }) {
  const hay = text.toLowerCase();
  const hit = rules.find(r => r.test.test(hay));
  return hit ? { icon: hit.icon, accent: hit.accent } : fallback;
}

// Shared by Business Units and Departments — both are org-groupings, so the same keyword
// vocabulary (Finance/Engineering/Sales/etc.) is meaningful for either one's real names.
const ORG_GROUP_ICON_RULES: IconRule[] = [
  { test: /financ|account|treasury|payroll/, icon: Wallet, accent: 'var(--warn)' },
  { test: /quality/, icon: ShieldCheck, accent: 'var(--risk)' },
  { test: /sales.*operat|operat.*sales/, icon: Activity, accent: 'var(--info)' },
  { test: /human resource|\bhr\b/, icon: UsersRound, accent: 'var(--ok)' },
  { test: /executive|leadership|\bhead\b|director|chief/, icon: Crown, accent: 'var(--brand-bright)' },
  { test: /sales/, icon: Handshake, accent: 'var(--ok)' },
  { test: /marketing|brand/, icon: Megaphone, accent: 'var(--brand-bright)' },
  { test: /engineer|development|tech/, icon: Cpu, accent: 'var(--info)' },
  { test: /legal|complian/, icon: Landmark, accent: 'var(--txt-mut)' },
  { test: /support|helpdesk|service|it\b/, icon: Wrench, accent: 'var(--warn)' },
  { test: /operat/, icon: Activity, accent: 'var(--info)' },
];
function getBusinessUnitIcon(name: string) {
  return resolveIcon(ORG_GROUP_ICON_RULES, name, { icon: TABS.businessunits.icon, accent: TAB_ACCENT.businessunits });
}
function getDepartmentIcon(name: string) {
  return resolveIcon(ORG_GROUP_ICON_RULES, name, { icon: TABS.departments.icon, accent: TAB_ACCENT.departments });
}

const DESIGNATION_ICON_RULES: IconRule[] = [
  { test: /senior.*manager|manager.*senior/, icon: BriefcaseBusiness, accent: 'var(--brand-bright)' },
  { test: /\blead\b/, icon: UserCog, accent: 'var(--brand-bright)' },
  { test: /manager/, icon: Briefcase, accent: 'var(--brand-bright)' },
  { test: /qa|quality|test|automation/, icon: TestTube, accent: 'var(--risk)' },
  { test: /engineer|developer|software|backend|frontend|full[\s-]?stack/, icon: Code, accent: 'var(--info)' },
  { test: /human resource|\bhr\b/, icon: UserRound, accent: 'var(--ok)' },
  { test: /analyst/, icon: Activity, accent: 'var(--info)' },
  { test: /executive|leadership|\bhead\b|director|chief/, icon: Award, accent: 'var(--brand-bright)' },
];
function getDesignationIcon(title: string) {
  return resolveIcon(DESIGNATION_ICON_RULES, title, { icon: TABS.designations.icon, accent: TAB_ACCENT.designations });
}

const DOCTYPE_ICON_RULES: IconRule[] = [
  { test: /passport|aadhar|aadhaar|identity|\bpan\b|voter/, icon: IdCard, accent: 'var(--info)' },
  { test: /driving|licen[cs]e/, icon: IdCard, accent: 'var(--info)' },
  { test: /contract|agreement/, icon: FileSignature, accent: 'var(--txt-mut)' },
  { test: /offer/, icon: FileText, accent: 'var(--txt-mut)' },
  { test: /experience/, icon: FileCheck, accent: 'var(--ok)' },
  { test: /certificate|educational|degree|diploma/, icon: Award, accent: 'var(--warn)' },
  { test: /authoriz|authoris/, icon: ShieldCheck, accent: 'var(--risk)' },
];
function getDocTypeIcon(name: string) {
  return resolveIcon(DOCTYPE_ICON_RULES, name, { icon: File, accent: TAB_ACCENT.doctypes });
}

// A small, consistent state system (remote / recognized major city / international / on-site
// office) driven by the row's own city/state/country fields — never a different icon just
// because two office names differ.
const MAJOR_CITY_KEYWORDS = [
  'hyderabad', 'bengaluru', 'bangalore', 'chennai', 'mumbai', 'delhi', 'pune', 'kolkata',
  'new york', 'los angeles', 'san francisco', 'chicago', 'london', 'dubai', 'singapore',
  'sydney', 'paris', 'tokyo', 'toronto',
];
function getLocationIcon(row: { name: string; city?: string | null; state?: string | null; country?: string | null }) {
  const hay = `${row.name} ${row.city ?? ''} ${row.state ?? ''}`.toLowerCase();
  if (/remote|work from home|\bwfh\b/.test(hay)) return { icon: Home, accent: 'var(--warn)' };
  if (MAJOR_CITY_KEYWORDS.some(city => hay.includes(city))) return { icon: Landmark, accent: TAB_ACCENT.locations };
  const country = (row.country ?? '').trim().toLowerCase();
  if (country && country !== 'india') return { icon: Globe, accent: 'var(--info)' };
  return { icon: Building2, accent: TAB_ACCENT.locations };
}

const SHIFT_NAME_ICON_RULES: IconRule[] = [
  { test: /morning|sunrise/, icon: Sunrise, accent: 'var(--warn)' },
  { test: /night|graveyard|overnight/, icon: Moon, accent: 'var(--info)' },
  { test: /evening|dusk/, icon: Sunset, accent: 'var(--brand-bright)' },
  { test: /\bday\b|afternoon|noon/, icon: Sun, accent: 'var(--warn)' },
  { test: /regular|general|standard/, icon: Clock, accent: TAB_ACCENT.shiftweeklyoff },
];
// Named shifts go by what the name says; unnamed/generic ones fall back to their actual start
// time so every row still gets a meaningful icon. Flexible shifts always show Timer, since
// "flexible" is itself the defining characteristic (real data, not a name guess).
function getShiftIcon(row: { name: string; startTime: string; flexible?: boolean }) {
  if (row.flexible) return { icon: Timer, accent: 'var(--warn)' };
  const byName = SHIFT_NAME_ICON_RULES.find(r => r.test.test(row.name.toLowerCase()));
  if (byName) return { icon: byName.icon, accent: byName.accent };
  const hour = Number(row.startTime?.slice(0, 2));
  if (Number.isNaN(hour)) return { icon: Clock, accent: TAB_ACCENT.shiftweeklyoff };
  if (hour >= 5 && hour < 9) return { icon: Sunrise, accent: 'var(--warn)' };
  if (hour >= 9 && hour < 15) return { icon: Sun, accent: 'var(--warn)' };
  if (hour >= 15 && hour < 19) return { icon: Sunset, accent: 'var(--brand-bright)' };
  return { icon: Moon, accent: 'var(--info)' };
}

const LEAVE_ICON_RULES: IconRule[] = [
  { test: /sick|medical|health/, icon: CalendarX2, accent: 'var(--risk)' },
  { test: /unpaid|loss of pay|\blop\b/, icon: CalendarX2, accent: 'var(--txt-mut)' },
  { test: /casual/, icon: CalendarClock, accent: 'var(--warn)' },
  { test: /annual|paid|earned/, icon: CalendarCheck2, accent: 'var(--ok)' },
];
function getLeaveTypeIcon(name: string) {
  return resolveIcon(LEAVE_ICON_RULES, name, { icon: TABS.leavetypes.icon, accent: TAB_ACCENT.leavetypes });
}

// Small tab-colored icon badge shown before a row's primary name/title — resolved per-row from
// real data, purely presentational. A soft tinted fill + a thin inset ring (--badge-accent, read
// by .nf-org-icon-badge in index.css) gives the glyph more visual weight than a bare thin-outline
// icon, without switching icon libraries or adding image assets.
function RowIconBadge({ icon: Icon, accent }: { icon: LucideIcon; accent: string }) {
  return (
    <span
      className="nf-org-icon-badge"
      style={{ background: `color-mix(in srgb, ${accent} 16%, transparent)`, ['--badge-accent' as string]: accent }}
    >
      <Icon size={14} aria-hidden="true" strokeWidth={2.25} style={{ color: accent }} />
    </span>
  );
}

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

function ClassificationBadge({ classification }: { classification: LeaveTypeClassification }) {
  const isPaid = classification === 'PAID';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
      background: isPaid ? 'rgba(47,182,124,.15)' : 'rgba(228,155,15,.15)',
      color: isPaid ? 'var(--ok)' : 'var(--warn, #b7791f)',
    }}>
      {isPaid ? 'Paid' : 'Unpaid'}
    </span>
  );
}

function CountBadge({ count }: { count: number }) {
  return (
    <span style={{
      fontFamily: 'Inter, sans-serif', fontSize: 12,
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
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: 'Inter, sans-serif', fontWeight: 700, color: 'var(--txt)' }}>
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
  editRow?: BusinessUnitRow | DepartmentRow | DesignationRow | LocationRow;
  onClose: () => void;
  onSaved: () => void;
  token: string;
}

function AddEditModal({ tab, editRow, onClose, onSaved, token }: AddEditModalProps) {
  const isEdit = !!editRow;
  const isLocations = tab === 'locations';
  const primaryLabel = tab === 'designations' ? 'Title' : 'Name';
  const modalTitle = isEdit ? `Edit ${TABS[tab].label.slice(0, -1)}` : TABS[tab].addLabel;

  const [name, setName] = useState(() => {
    if (!editRow) return '';
    return tab === 'designations' ? (editRow as DesignationRow).title : (editRow as BusinessUnitRow | DepartmentRow | LocationRow).name;
  });
  const [grade, setGrade] = useState(() => (editRow && tab === 'designations' ? ((editRow as DesignationRow).grade ?? '') : ''));
  const [level, setLevel] = useState(() => (editRow && tab === 'designations' ? ((editRow as DesignationRow).level ?? '') : ''));
  const [city, setCity] = useState(() => (editRow && isLocations ? ((editRow as LocationRow).city ?? '') : ''));
  const [state, setState] = useState(() => (editRow && isLocations ? ((editRow as LocationRow).state ?? '') : ''));
  const [country, setCountry] = useState(() => (editRow && isLocations ? ((editRow as LocationRow).country ?? '') : ''));
  const [holidayRegion, setHolidayRegion] = useState(() => (editRow && isLocations ? ((editRow as LocationRow).holidayRegion ?? '') : ''));
  // Unlike Name/City/State/Country (freely editable, any number of Locations can be created),
  // Timezone must be one of a fixed, supported set — see OrgService.SUPPORTED_TIMEZONES on the
  // backend, which independently enforces the same set regardless of what this dropdown offers.
  const [timezone, setTimezone] = useState(() => (editRow && isLocations ? ((editRow as LocationRow).timezone ?? '') : ''));
  const [supportedTimezones, setSupportedTimezones] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const firstRef = useRef<HTMLInputElement>(null);

  useEffect(() => { firstRef.current?.focus(); }, []);
  useEffect(() => {
    if (!isLocations) return;
    orgApi.listSupportedLocationTimezones(token).then(setSupportedTimezones).catch(() => {});
  }, [isLocations, token]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const trimmed = name.trim();
    if (!trimmed) { setError(`${primaryLabel} is required`); return; }
    if (tab === 'designations') {
      // Letters, numbers, spaces, - and / are allowed (e.g. "SDET-01", "Developer L2"), but the
      // title must include at least one letter — this rejects numeric-only ("12345") and
      // special-character-only values while still allowing a trailing level/grade number.
      if (!/^(?=.*[A-Za-z])[A-Za-z0-9 \-/]+$/.test(trimmed)) {
        setError(`${primaryLabel} must include letters, and may only contain letters, numbers, spaces, - and /`);
        return;
      }
      const trimmedGrade = grade.trim();
      if (trimmedGrade && !/^[A-Za-z][0-9]$/.test(trimmedGrade)) {
        setError('Grade/Band must contain exactly 1 letter followed by 1 number (e.g. L1)');
        return;
      }
    } else if (isLocations) {
      // Location names are alphabetic only — letters and spaces (for multi-word names like
      // "Chennai HQ"), no digits, no hyphens, no other special characters.
      if (!/^[A-Za-z]+( [A-Za-z]+)*$/.test(trimmed)) {
        setError(`${primaryLabel} must contain only letters (spaces allowed between words) — no numbers or special characters`);
        return;
      }
    } else if (!/^(?=.*[A-Za-z])[^0-9]+$/.test(trimmed)) {
      // Department names may contain most non-digit characters (e.g. "R&D",
      // "Sales & Marketing"), but must include at least one letter — this rejects
      // numeric-only ("12345") and special-character-only ("@#$%^&*") values.
      setError(`${primaryLabel} must contain letters and cannot contain numbers or be made up of special characters only`);
      return;
    }
    if (isLocations) {
      if (/\d/.test(city.trim())) { setError('City cannot contain numbers'); return; }
      if (/\d/.test(state.trim())) { setError('State / Province cannot contain numbers'); return; }
      if (/\d/.test(country.trim())) { setError('Country cannot contain numbers'); return; }
      const trimmedRegion = holidayRegion.trim();
      if (trimmedRegion && !/^[A-Za-z]{2}$/.test(trimmedRegion)) {
        setError('Region must contain exactly 2 letters (e.g. TN)');
        return;
      }
      if (!timezone.trim()) { setError('Timezone is required'); return; }
    }
    setLoading(true);
    try {
      if (isEdit && editRow) {
        if (tab === 'businessunits') {
          await orgApi.updateBusinessUnit(token, editRow.id, trimmed);
        } else if (tab === 'departments') {
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
            timezone: timezone.trim(),
          });
        }
      } else {
        if (tab === 'businessunits') {
          await orgApi.createBusinessUnit(token, trimmed);
        } else if (tab === 'departments') {
          await orgApi.createDepartment(token, trimmed);
        } else if (tab === 'designations') {
          await orgApi.createDesignation(token, trimmed, grade.trim() || undefined, level || undefined);
        } else {
          await orgApi.createLocation(token, {
            name: trimmed,
            city: city.trim() || undefined, state: state.trim() || undefined,
            country: country.trim() || undefined, holidayRegion: holidayRegion.trim() || undefined,
            timezone: timezone.trim(),
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
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: 'Inter, sans-serif', fontWeight: 700, color: 'var(--txt)' }}>
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
                : isLocations ? 'e.g. Chennai HQ'
                : 'e.g. Operations'
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

          {isLocations && (
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
                  <input value={holidayRegion} onChange={e => setHolidayRegion(e.target.value)} placeholder="e.g. TN" style={inputStyle} />
                </label>
              </div>
              <label style={labelStyle}>
                <span style={labelTextStyle}>
                  Timezone <span style={{ color: 'var(--risk)' }}>*</span>
                </span>
                <select
                  value={timezone} onChange={e => setTimezone(e.target.value)}
                  style={{ ...inputStyle, appearance: 'none' as const, WebkitAppearance: 'none' as const }}
                >
                  <option value="">— Select a timezone —</option>
                  {supportedTimezones.map(tz => <option key={tz} value={tz}>{tz}</option>)}
                </select>
                <span style={{ fontSize: 11, color: 'var(--txt-mut)', marginTop: 3 }}>
                  Limited to this business's supported zones — every employee assigned to this
                  location uses it for check-in/out, lateness, and shift-day calculations, so it
                  can't be entered freely.
                </span>
              </label>
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
// Create/edit is HR Admin/Super Admin — enforced by DocumentTypeController (@PreAuthorize).
// Applicability fields are comma-separated and matched case-insensitively by the backend;
// blank means the type applies to everyone.

const DOC_EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'];

function parseCsv(v: string): string[] {
  return v.split(',').map(s => s.trim()).filter(Boolean);
}

function toggleCsv(v: string, item: string): string {
  const parts = parseCsv(v);
  const has = parts.some(p => p.toLowerCase() === item.toLowerCase());
  return (has ? parts.filter(p => p.toLowerCase() !== item.toLowerCase()) : [...parts, item]).join(', ');
}

interface DocTypeModalProps {
  editRow?: DocumentType;
  token: string;
  locationNames: string[];
  onClose(): void;
  onSaved(): void;
}

function DocTypeModal({ editRow, token, locationNames, onClose, onSaved }: DocTypeModalProps) {
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
    const trimmed = name.trim();
    if (!trimmed) { setError('Name is required'); return; }
    if (trimmed.length > 80) { setError('Name must be at most 80 characters'); return; }
    const empCsv = parseCsv(empTypes).join(',');
    const locCsv = parseCsv(locs).join(',');
    if (empCsv.length > 200 || locCsv.length > 200) { setError('Applicability lists must be at most 200 characters'); return; }
    setError('');
    setLoading(true);
    try {
      if (isEdit && editRow) {
        // PATCH: null means "unchanged", so send '' (not null) to clear a list back to "All".
        await updateDocType(token, editRow.id, {
          name: trimmed,
          requiresVerification: reqVerify,
          requiresExpiryDate: reqExpiry,
          applicableEmploymentTypes: empCsv,
          applicableLocations: locCsv,
        });
      } else {
        await createDocType(token, {
          name: trimmed,
          requiresVerification: reqVerify,
          requiresExpiryDate: reqExpiry,
          applicableEmploymentTypes: empCsv || null,
          applicableLocations: locCsv || null,
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

  function Chips({ value, options, onChange }: { value: string; options: string[]; onChange(v: string): void }) {
    if (options.length === 0) return null;
    const selected = new Set(parseCsv(value).map(s => s.toLowerCase()));
    return (
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
        {options.map(o => {
          const on = selected.has(o.toLowerCase());
          return (
            <button key={o} type="button" onClick={() => onChange(toggleCsv(value, o))} aria-pressed={on}
              style={{ padding: '3px 9px', borderRadius: 999, fontSize: 11, cursor: 'pointer', border: `1px solid ${on ? 'var(--brand)' : 'var(--line2)'}`, background: on ? 'var(--brand)' : 'var(--raised)', color: on ? '#fff' : 'var(--txt-mut)' }}>
              {o}
            </button>
          );
        })}
      </div>
    );
  }

  return (
    <div role="dialog" aria-modal="true" style={{ position: 'fixed', inset: 0, zIndex: 200, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 480, maxWidth: '94vw', maxHeight: '92vh', overflowY: 'auto' }}>
        <h2 style={{ margin: '0 0 20px', fontSize: 15, fontWeight: 700 }}>{isEdit ? 'Edit Document Type' : 'Add Document Type'}</h2>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Name *</label>
            <input style={inputS} value={name} onChange={e => setName(e.target.value)} required maxLength={80} autoFocus />
          </div>
          <div style={{ display: 'flex', gap: 20, marginBottom: 6, flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, cursor: 'pointer' }}>
              <input type="checkbox" checked={reqVerify} onChange={e => setReqVerify(e.target.checked)} style={{ width: 15, height: 15 }} />
              Requires HR verification
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, cursor: 'pointer' }}>
              <input type="checkbox" checked={reqExpiry} onChange={e => setReqExpiry(e.target.checked)} style={{ width: 15, height: 15 }} />
              Requires expiry date
            </label>
          </div>
          <p style={{ margin: '0 0 14px', fontSize: 11.5, color: 'var(--txt-dim)' }}>
            {reqVerify ? 'New uploads start as Pending Review until HR verifies them.' : 'New uploads are accepted as Verified without HR review.'}
          </p>
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Applicable Employment Types <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}>(comma-separated, blank = all)</span></label>
            <input style={inputS} value={empTypes} onChange={e => setEmpTypes(e.target.value)} placeholder="All employment types" />
            <Chips value={empTypes} options={DOC_EMPLOYMENT_TYPES} onChange={setEmpTypes} />
          </div>
          <div style={{ marginBottom: 20 }}>
            <label style={labelS}>Applicable Locations <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}>(comma-separated, blank = all)</span></label>
            <input style={inputS} value={locs} onChange={e => setLocs(e.target.value)} placeholder="All locations" />
            <Chips value={locs} options={locationNames} onChange={setLocs} />
          </div>
          {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginBottom: 12 }}>{error}</div>}
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

// ── LeaveTypeModal ─────────────────────────────────────────────────────────────
// Create/edit are Super Admin/HR Admin — enforced by LeaveController (@PreAuthorize) regardless
// of whether this modal is reachable, same convention as DocTypeModal above.

interface LeaveTypeModalProps {
  editRow?: LeaveType;
  token: string;
  onClose(): void;
  onSaved(): void;
}

function LeaveTypeModal({ editRow, token, onClose, onSaved }: LeaveTypeModalProps) {
  const isEdit = !!editRow;
  const [code, setCode] = useState(editRow?.code ?? '');
  const [name, setName] = useState(editRow?.name ?? '');
  const [classification, setClassification] = useState<LeaveTypeClassification>(editRow?.classification ?? 'PAID');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) { setError('Name is required'); return; }
    if (!isEdit && !code.trim()) { setError('Code is required'); return; }
    setError('');
    setLoading(true);
    try {
      if (isEdit && editRow) {
        await leaveApi.updateType(editRow.id, { name: name.trim(), classification }, token);
      } else {
        await leaveApi.createType({ code: code.trim(), name: name.trim(), classification }, token);
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
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 420, maxWidth: '94vw' }}>
        <h2 style={{ margin: '0 0 20px', fontSize: 15, fontWeight: 700 }}>{isEdit ? 'Edit Leave Type' : 'Add Leave Type'}</h2>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Leave Type Name *</label>
            <input style={inputS} value={name} onChange={e => setName(e.target.value)} required autoFocus />
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Code {isEdit ? '' : '*'}</label>
            <input
              style={{ ...inputS, opacity: isEdit ? 0.6 : 1 }}
              value={code}
              onChange={e => setCode(e.target.value)}
              maxLength={20}
              disabled={isEdit}
              placeholder="e.g. LOP"
            />
          </div>
          <div style={{ marginBottom: 20 }}>
            <label style={labelS}>Classification *</label>
            <div style={{ display: 'flex', gap: 20 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, cursor: 'pointer' }}>
                <input type="radio" name="classification" checked={classification === 'PAID'} onChange={() => setClassification('PAID')} />
                Paid
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, cursor: 'pointer' }}>
                <input type="radio" name="classification" checked={classification === 'UNPAID'} onChange={() => setClassification('UNPAID')} />
                Unpaid
              </label>
            </div>
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

// ── ShiftFormModal ─────────────────────────────────────────────────────────────
// Create/edit are Super Admin only — enforced by OrgService (@PreAuthorize) regardless of
// whether this modal is reachable; canManageShifts in the parent just controls whether the
// "Add"/"Edit" actions are offered at all.

const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

export function fmtShiftTime(t: string): string {
  const [h, m] = t.split(':').map(Number);
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
}

/** "All days" for the common case, otherwise abbreviated day names in Mon-Sun order regardless of the order the API happens to return them in. */
function formatApplicableDays(workingDays: string[] | null | undefined): string {
  // Defensive: ShiftRow#workingDays is documented as always-present (backend defaults it), but
  // guard anyway so a stale backend response (missing the field) shows "All days" instead of
  // throwing and blanking the whole app.
  const days = workingDays ?? [];
  if (days.length === 7) return 'All days';
  return WEEKDAYS.filter(day => days.includes(day)).map(day => day.slice(0, 3)).join(', ');
}

interface ShiftFormModalProps {
  editRow?: ShiftRow;
  token: string;
  onClose(): void;
  // Passed the created/updated row — callers that just want a post-save refresh can ignore the
  // argument; callers that need the row itself (e.g. the Add User Shift dropdown, to auto-select
  // a shift just created inline) don't have to re-fetch the whole list to find it.
  onSaved(saved: ShiftRow): void;
}

/** Elapsed span of a shift in hours, overnight-aware (end <= start rolls into the next day) — same rollover convention as the backend's ShiftDayPolicy.shiftEndAt. Times are "HH:mm". */
function elapsedShiftHours(startTime: string, endTime: string): number {
  const [sh, sm] = startTime.split(':').map(Number);
  const [eh, em] = endTime.split(':').map(Number);
  const startMin = sh * 60 + sm;
  let endMin = eh * 60 + em;
  if (endMin <= startMin) endMin += 24 * 60;
  return (endMin - startMin) / 60;
}

/** YYYY-MM-DD for N days from today, in the viewer's own local calendar — matches what a plain <input type="date"> compares against. */
function isoDatePlusDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

export function ShiftFormModal({ editRow, token, onClose, onSaved }: ShiftFormModalProps) {
  const isEdit = !!editRow;
  // A pending version already scheduled (from a previous edit) becomes the starting point for
  // further edits — adjusting it again replaces it, rather than starting fresh from the
  // currently-effective timing and losing track of what was already scheduled.
  const pending = editRow?.pendingEffectiveFrom
    ? {
        startTime: editRow.pendingStartTime!, endTime: editRow.pendingEndTime!, breakMinutes: editRow.pendingBreakMinutes,
        lateGraceMinutes: editRow.pendingLateGraceMinutes, effectiveFrom: editRow.pendingEffectiveFrom,
      }
    : null;
  const [name, setName] = useState(editRow?.name ?? '');
  const [code, setCode] = useState(editRow?.code ?? '');
  const [description, setDescription] = useState(editRow?.description ?? '');
  const [startTime, setStartTime] = useState((pending?.startTime ?? editRow?.startTime)?.slice(0, 5) ?? '09:00');
  const [endTime, setEndTime] = useState((pending?.endTime ?? editRow?.endTime)?.slice(0, 5) ?? '18:00');
  const [breakMinutes, setBreakMinutes] = useState(
    (pending?.breakMinutes ?? editRow?.breakMinutes) != null ? String(pending?.breakMinutes ?? editRow?.breakMinutes) : '');
  const [lateGraceMinutes, setLateGraceMinutes] = useState(
    (pending?.lateGraceMinutes ?? editRow?.lateGraceMinutes) != null ? String(pending?.lateGraceMinutes ?? editRow?.lateGraceMinutes) : '10');
  // Applicable Days — seeded from the existing Shift's current value when editing (unlike
  // startTime/endTime/etc above, this is NOT versioned/future-effective: it changes immediately on
  // save, since it's a plain Shift-row attribute never read by any attendance/workday calculation
  // — see UpdateShiftRequest's own doc comment). Defaults to all 7 days selected for a brand-new
  // Shift, per this feature's own requirement.
  const [workingDays, setWorkingDays] = useState<string[]>(editRow?.workingDays ?? WEEKDAYS);
  function toggleWorkingDay(day: string) {
    setWorkingDays(prev => {
      if (prev.includes(day)) {
        // At least one applicable day must always remain selected — prevent removing the last one
        // outright (per this feature's own requirement) rather than allow an invalid zero-day
        // state and only catch it at submit time.
        return prev.length === 1 ? prev : prev.filter(d => d !== day);
      }
      // Re-filtered against WEEKDAYS (rather than simply appended) so re-selecting a previously-
      // deselected day lands back in canonical Monday-first order — the backend stores/returns
      // whatever order is sent, and nothing should have to re-sort it after the fact.
      return WEEKDAYS.filter(d => prev.includes(d) || d === day);
    });
  }
  // Shift changes are always future-effective — never today, never in the past (enforced
  // server-side regardless of what's picked here). Defaults to tomorrow; re-editing an
  // already-scheduled pending version keeps its own date instead of resetting to tomorrow.
  const tomorrow = isoDatePlusDays(1);
  const [effectiveFrom, setEffectiveFrom] = useState(pending?.effectiveFrom ?? tomorrow);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  // Reads the org's current Maximum Shift Day Duration (Shifts & Weekly Off Rules) — the same
  // value OrgService.validateShiftDuration enforces server-side, reused here (not a second,
  // hardcoded limit) so the UI can block early with a clear message instead of only surfacing the
  // backend's 400 after a round-trip. If this fetch fails, `maxDurationHours` stays null and
  // `exceedsMaxDuration` reads false — the backend still enforces the limit regardless; this is
  // best-effort UX on top of that, never the actual guarantee.
  const [maxDurationHours, setMaxDurationHours] = useState<number | null>(null);

  useEffect(() => {
    orgApi.getShiftWeeklyOffRules(token).then(r => setMaxDurationHours(r.maximumShiftDayDurationHours)).catch(() => {});
  }, [token]);

  const elapsedHours = elapsedShiftHours(startTime, endTime);
  const exceedsMaxDuration = maxDurationHours != null && elapsedHours > maxDurationHours;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    if (!name.trim()) { setError('Shift name is required'); return; }
    if (breakMinutes.trim() && (isNaN(Number(breakMinutes)) || Number(breakMinutes) < 0)) {
      setError('Break duration must be a non-negative number of minutes');
      return;
    }
    if (lateGraceMinutes.trim() && (isNaN(Number(lateGraceMinutes)) || Number(lateGraceMinutes) < 0)) {
      setError('Grace period must be a non-negative number of minutes');
      return;
    }
    if (isEdit && effectiveFrom <= isoDatePlusDays(0)) {
      setError('Effective From must be a future date (after today)');
      return;
    }
    // Defense-in-depth: toggleWorkingDay already prevents reaching zero selected days, and the
    // backend independently rejects an empty list too — this just keeps the same "never rely on
    // frontend validation alone" double-check every other rule in this form already has.
    if (workingDays.length === 0) {
      setError('At least one applicable day is required');
      return;
    }
    if (exceedsMaxDuration) {
      setError(`Shift duration cannot exceed the organization's Maximum Shift Day Duration of ${maxDurationHours}h (this shift spans ${elapsedHours % 1 === 0 ? elapsedHours : elapsedHours.toFixed(1)}h)`);
      return;
    }
    setLoading(true);
    try {
      const saved = isEdit && editRow
        ? await orgApi.updateShift(token, editRow.id, {
            name: name.trim(), code: code.trim() || undefined, description: description.trim() || undefined,
            startTime, endTime, breakMinutes: breakMinutes.trim() ? Number(breakMinutes) : undefined,
            lateGraceMinutes: lateGraceMinutes.trim() ? Number(lateGraceMinutes) : undefined, effectiveFrom,
            workingDays,
          })
        : await orgApi.createShift(token, {
            name: name.trim(), code: code.trim() || undefined, description: description.trim() || undefined,
            startTime, endTime, breakMinutes: breakMinutes.trim() ? Number(breakMinutes) : undefined,
            lateGraceMinutes: lateGraceMinutes.trim() ? Number(lateGraceMinutes) : undefined,
            workingDays,
          });
      onSaved(saved);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  const inputS: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', width: '100%', boxSizing: 'border-box' };
  const labelS: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };

  // Rendered via a portal straight to document.body: callers like the Add/Edit User Shift
  // dropdown open this from *inside* their own <form> (see UserManagementPage's ShiftSelect) —
  // without the portal, this modal's own <form> below would be a DOM descendant of that outer
  // form. Nested <form> elements are invalid HTML, and clicking this form's "Add"/"Save Changes"
  // submit button inside one triggers an ambiguous native form submission (a real page
  // navigation/reload, wiping the outer form's state) instead of being caught by this
  // component's own onSubmit — exactly the "leaves the Add User screen" bug this fixes. The
  // portal keeps this modal a sibling of <body>'s other content in the DOM, so its <form> is
  // never nested inside anyone else's, while staying identical in appearance/behavior (still a
  // fixed, full-viewport overlay) and still fully wired into React's own component tree/state.
  // z-index 600 (not this file's usual 200): once portaled to <body>, this overlay is a sibling
  // of whatever opened it rather than a descendant, so when opened from inside another modal
  // (e.g. UserManagementPage's Add/Edit User, overlay z-index 500) it must outrank that modal's
  // own overlay or its backdrop swallows every click meant for this one.
  return createPortal(
    <div role="dialog" aria-modal="true" aria-label={isEdit ? 'Edit Shift' : 'Add Shift'} style={{ position: 'fixed', inset: 0, zIndex: 600, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 480, maxWidth: '94vw', maxHeight: '90vh', overflowY: 'auto' }}>
        <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>{isEdit ? 'Edit Shift' : 'Add Shift'}</h2>
        {isEdit && (
          <p style={{ margin: '0 0 16px', fontSize: 11.5, color: 'var(--txt-mut)' }}>
            The current timing stays in effect through today exactly as it is — saving schedules a new timing
            to take over from the Effective From date below, it never changes today's timing retroactively.
          </p>
        )}
        {pending && (
          <div style={{
            background: 'var(--info-bg, rgba(59,130,246,.10))', border: '1px solid var(--info, #3b82f6)',
            borderRadius: 8, padding: '10px 12px', fontSize: 12, color: 'var(--txt)', marginBottom: 16,
          }}>
            A change to {fmtShiftTime(pending.startTime)}–{fmtShiftTime(pending.endTime)} is already scheduled to take
            effect {pending.effectiveFrom}. Saving below replaces that scheduled change.
          </div>
        )}
        <form onSubmit={submit}>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14 }}>
            <div>
              <label style={labelS}>Shift Name *</label>
              <input style={inputS} value={name} onChange={e => setName(e.target.value)} placeholder="e.g. US Night Shift" required autoFocus />
            </div>
            <div>
              <label style={labelS}>Shift Code</label>
              <input style={inputS} value={code} onChange={e => setCode(e.target.value)} placeholder="e.g. US-NIGHT" />
            </div>
          </div>
          <div style={{ marginBottom: 14 }}>
            <label style={labelS}>Description</label>
            <textarea style={{ ...inputS, minHeight: 60, resize: 'vertical', fontFamily: 'inherit' }} value={description} onChange={e => setDescription(e.target.value)} placeholder="Optional notes about who this shift is for" />
          </div>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14 }}>
            <div>
              <label style={labelS}>Start Time *</label>
              <input type="time" style={inputS} value={startTime} onChange={e => setStartTime(e.target.value)} required />
            </div>
            <div>
              <label style={labelS}>End Time *</label>
              <input type="time" style={inputS} value={endTime} onChange={e => setEndTime(e.target.value)} required />
              <span style={{ fontSize: 10.5, color: 'var(--txt-mut)' }}>Earlier than start = overnight shift, crossing midnight</span>
            </div>
          </div>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: isEdit ? 14 : 20 }}>
            <div>
              <label style={labelS}>Break Duration (minutes)</label>
              <input type="number" min={0} style={inputS} value={breakMinutes} onChange={e => setBreakMinutes(e.target.value)} placeholder="e.g. 60" />
            </div>
            <div>
              <label style={labelS}>Grace Period (minutes)</label>
              <input type="number" min={0} style={inputS} value={lateGraceMinutes} onChange={e => setLateGraceMinutes(e.target.value)} placeholder="e.g. 10" />
              <span style={{ fontSize: 10.5, color: 'var(--txt-mut)' }}>Minutes past Start Time forgiven before a check-in counts as Late</span>
            </div>
          </div>
          <div style={{ marginBottom: 20 }}>
            <label style={labelS} id="applicable-days-label">Applicable Days *</label>
            <div role="group" aria-labelledby="applicable-days-label" style={{ display: 'flex', gap: 6, marginTop: 6 }}>
              {WEEKDAYS.map(day => {
                const selected = workingDays.includes(day);
                const dayLabel = day.charAt(0) + day.slice(1).toLowerCase();
                return (
                  <button
                    key={day}
                    type="button"
                    aria-pressed={selected}
                    aria-label={dayLabel}
                    title={dayLabel}
                    onClick={() => toggleWorkingDay(day)}
                    style={{
                      width: 34, height: 34, borderRadius: 6, cursor: 'pointer',
                      border: selected ? '1px solid var(--brand)' : '1px solid var(--line2)',
                      background: selected ? 'var(--brand)' : 'var(--raised)',
                      color: selected ? '#fff' : 'var(--txt-mut)',
                      fontSize: 12.5, fontWeight: 600,
                    }}
                  >
                    {day.charAt(0)}
                  </button>
                );
              })}
            </div>
            <span style={{ fontSize: 10.5, color: 'var(--txt-mut)' }}>
              At least one day must remain selected{isEdit ? ' — unlike the timing below, this takes effect immediately on save' : ''}
            </span>
          </div>
          {isEdit && (
            <div style={{ marginBottom: 20 }}>
              <label style={labelS}>Effective From *</label>
              <input type="date" style={{ ...inputS, maxWidth: 200 }} value={effectiveFrom} min={tomorrow}
                onChange={e => setEffectiveFrom(e.target.value)} required />
              <span style={{ fontSize: 10.5, color: 'var(--txt-mut)' }}>Tomorrow or any later date — never today or the past</span>
            </div>
          )}
          {/* Blocks saving (see submit's own check) — the backend enforces the exact same limit
              regardless, so this is a UX head-start, not the actual guarantee. */}
          {exceedsMaxDuration && (
            <div role="alert" style={{
              background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)',
              borderRadius: 8, padding: '10px 12px', fontSize: 12, color: 'var(--risk)', marginBottom: 16,
            }}>
              This shift spans {elapsedHours % 1 === 0 ? elapsedHours : elapsedHours.toFixed(1)}h, which exceeds your
              organization's current Maximum Shift Day Duration of {maxDurationHours}h. Adjust the timing, or raise the
              limit in Shifts & Weekly Off Rules first.
            </div>
          )}
          {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginBottom: 12 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} disabled={loading} style={{ padding: '7px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={loading} style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: 'pointer', opacity: loading ? 0.7 : 1 }}>
              {loading ? 'Saving…' : isEdit ? 'Save Changes' : 'Add'}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body
  );
}

// ── ShiftEmployeesPanel — the selected Shift's "Employees" detail tab ───────────
// Deliberately READ-ONLY: this tab is "which employees are assigned to this Shift," not a place
// to change that assignment or the Shift's own configuration — editing a Shift's timing affects
// every employee shown here at once, so that action lives only on the Shift-level kebab/Summary
// area (see ShiftsMasterDetail, which hides that kebab specifically while this tab is active), and
// per-employee shift reassignment belongs to the existing Time Assignments area (My Team ->
// Assignments — bulk update or CSV import), not a second, narrower control duplicated here.
interface ShiftEmployeesPanelProps {
  shift: ShiftRow;
  token: string;
}

function ShiftEmployeesPanel({ shift, token }: ShiftEmployeesPanelProps) {
  const [rows, setRows] = useState<ShiftEmployeeRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    orgApi.listShiftEmployees(token, shift.id)
      .then(r => setRows(r))
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load employees'))
      .finally(() => setLoading(false));
  }, [token, shift.id]);

  return (
    <div>
      <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 14 }}>
        {rows.length} employee{rows.length === 1 ? '' : 's'} assigned
      </div>
      {loading ? (
        <div style={{ padding: '24px 0', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : error ? (
        <div role="alert" style={{ color: 'var(--risk)', fontSize: 13 }}>{error}</div>
      ) : rows.length === 0 ? (
        <div style={{ padding: '24px 0', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>No employees assigned to this shift.</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {rows.map(r => (
            <div key={r.userId} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '9px 4px', borderBottom: '1px solid var(--line)', fontSize: 13, ...inactiveDimStyle(r.active) }}>
              <div style={{ minWidth: 0, flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <div style={{ color: 'var(--txt)', fontWeight: 500 }}>{r.fullName}</div>
                  {!r.active && <StatusBadge active={r.active} />}
                </div>
                <div style={{ color: 'var(--txt-mut)', fontSize: 11.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {r.email}{r.departmentName ? ` · ${r.departmentName}` : ''} · {r.employeeCode}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── ShiftVersionsPanel — the selected Shift's "Track Shift Versions" detail tab ─
// Read-only: a version is never edited in place (see ShiftFormModal's own comment) — the only way
// to see a new one appear here is saving an edit through that modal. Exposes the full timing
// history for the currently-selected logical Shift, backed by the Shift Versioning work
// (GET /api/org/shifts/{id}/versions) — the "Current"/"Upcoming" badges mirror the same
// effectiveFrom-vs-today logic the backend's ShiftVersionResolver itself uses to pick a version.
interface ShiftVersionsPanelProps {
  shift: ShiftRow;
  token: string;
}

function ShiftVersionsPanel({ shift, token }: ShiftVersionsPanelProps) {
  const [rows, setRows] = useState<ShiftVersionRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    orgApi.listShiftVersions(token, shift.id)
      .then(r => setRows(r))
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load version history'))
      .finally(() => setLoading(false));
  }, [token, shift.id]);

  const today = new Date().toISOString().slice(0, 10);

  return loading ? (
    <div style={{ padding: '24px 0', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
  ) : error ? (
    <div role="alert" style={{ color: 'var(--risk)', fontSize: 13 }}>{error}</div>
  ) : (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {rows.map((v, i) => {
        const isPending = v.effectiveFrom > today;
        const isCurrent = !isPending && (i === 0 || rows[i - 1].effectiveFrom > today);
        return (
          <div key={v.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '9px 4px', borderBottom: '1px solid var(--line)', fontSize: 13 }}>
            <div>
              <div style={{ color: 'var(--txt)', fontWeight: 500 }}>{fmtShiftTime(v.startTime)} – {fmtShiftTime(v.endTime)}</div>
              <div style={{ color: 'var(--txt-mut)', fontSize: 11.5 }}>Effective from {v.effectiveFrom}{v.breakMinutes != null ? ` · ${v.breakMinutes}m break` : ''}{v.lateGraceMinutes != null ? ` · ${v.lateGraceMinutes}m grace` : ''}</div>
            </div>
            {isPending && <span style={{ fontSize: 10.5, color: 'var(--info, #3b82f6)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.04em' }}>Upcoming</span>}
            {isCurrent && <span style={{ fontSize: 10.5, color: 'var(--brand)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.04em' }}>Current</span>}
          </div>
        );
      })}
    </div>
  );
}

// ── ShiftSummaryPanel — the selected Shift's "Summary" detail tab (default) ─────
function ShiftSummaryPanel({ shift }: { shift: ShiftRow }) {
  const rowS: React.CSSProperties = { display: 'contents' };
  const labelS: React.CSSProperties = { color: 'var(--txt-mut)', padding: '6px 0' };
  const valueS: React.CSSProperties = { color: 'var(--txt)', padding: '6px 0' };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '160px 1fr', fontSize: 13, maxWidth: 480 }}>
        <div style={rowS}><span style={labelS}>Timing</span><span style={valueS}>{fmtShiftTime(shift.startTime)} – {fmtShiftTime(shift.endTime)}</span></div>
        <div style={rowS}><span style={labelS}>Break Duration</span><span style={valueS}>{shift.breakMinutes != null ? `${shift.breakMinutes} minutes` : '—'}</span></div>
        <div style={rowS}><span style={labelS}>Grace Period</span><span style={valueS}>{shift.lateGraceMinutes != null ? `${shift.lateGraceMinutes} minutes` : '—'}</span></div>
        <div style={rowS}><span style={labelS}>Status</span><span style={valueS}><StatusBadge active={shift.active} /></span></div>
        <div style={rowS}><span style={labelS}>Employees Assigned</span><span style={valueS}><CountBadge count={shift.employeeCount} /></span></div>
        <div style={rowS}><span style={labelS}>Applicable Days</span><span style={valueS}>{formatApplicableDays(shift.workingDays)}</span></div>
      </div>
      {shift.pendingEffectiveFrom && (
        <div style={{
          background: 'var(--info-bg, rgba(59,130,246,.10))', border: '1px solid var(--info, #3b82f6)',
          borderRadius: 8, padding: '10px 12px', fontSize: 12, color: 'var(--txt)', maxWidth: 480,
        }}>
          A change to {fmtShiftTime(shift.pendingStartTime!)}–{fmtShiftTime(shift.pendingEndTime!)} is scheduled to
          take effect {shift.pendingEffectiveFrom} — see Track Shift Versions for the full history.
        </div>
      )}
    </div>
  );
}

// ── ShiftsMasterDetail — the Shifts sub-tab's approved layout: a searchable list on the left,
// the selected Shift's Summary/Employees/Track Shift Versions on the right. Replaces what had
// regressed into a generic full-width table — every other Organization Masters tab keeps that
// generic table; only Shifts uses this dedicated layout, matching the approved reference design.
interface ShiftsMasterDetailProps {
  shifts: ShiftRow[];
  loading?: boolean;
  token: string;
  canManageShifts: boolean;
  kebabItems(row: ShiftRow): KebabItem[];
}

function ShiftsMasterDetail({ shifts, loading, token, canManageShifts, kebabItems }: ShiftsMasterDetailProps) {
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detailTab, setDetailTab] = useState<'summary' | 'employees' | 'versions'>('summary');

  const visible = shifts.filter(s =>
    s.name.toLowerCase().includes(search.toLowerCase()) || (s.code ?? '').toLowerCase().includes(search.toLowerCase()));
  // Keeps a valid selection across searches/refreshes: the explicitly-selected shift if it's
  // still around, else the first visible one, else the first shift at all (e.g. right after the
  // selected one was deleted) — never a stale reference to a shift no longer in the list.
  const selected = shifts.find(s => s.id === selectedId) ?? visible[0] ?? shifts[0] ?? null;

  useEffect(() => { setDetailTab('summary'); }, [selected?.id]);

  return (
    <div style={{ display: 'flex', minHeight: 440 }}>
      <div style={{ width: 260, flexShrink: 0, borderRight: '1px solid var(--line)', display: 'flex', flexDirection: 'column' }}>
        <div style={{ padding: 12, borderBottom: '1px solid var(--line)' }}>
          <div style={{ position: 'relative' }}>
            <Search size={12} aria-hidden="true" style={{ position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
            <input
              type="search" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search shifts…" aria-label="Search shifts"
              style={{
                width: '100%', boxSizing: 'border-box', background: 'var(--raised)', border: '1px solid var(--line2)',
                borderRadius: 6, padding: '6px 10px 6px 26px', fontSize: 12, color: 'var(--txt)', outline: 'none',
              }}
            />
          </div>
        </div>
        <div style={{ overflowY: 'auto', flex: 1 }}>
          {shifts.length === 0 ? (
            <div style={{ padding: '32px 16px', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 12.5 }}>
              {loading ? (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                  <span className="nf-org-spinner" aria-hidden="true" /> Loading shifts…
                </span>
              ) : 'No shifts configured yet.'}
            </div>
          ) : visible.length === 0 ? (
            <div style={{ padding: '32px 16px', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 12.5 }}>No shifts match "{search}"</div>
          ) : visible.map(s => (
            <button key={s.id} onClick={() => setSelectedId(s.id)} style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', textAlign: 'left',
              padding: '10px 14px', background: selected?.id === s.id ? 'var(--raised)' : 'transparent',
              border: 'none', borderLeft: selected?.id === s.id ? '2px solid var(--brand-bright)' : '2px solid transparent',
              cursor: 'pointer', fontSize: 13, color: 'var(--txt)', gap: 8,
            }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 9, overflow: 'hidden' }}>
                <RowIconBadge {...getShiftIcon(s)} />
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: selected?.id === s.id ? 600 : 400 }}>{s.name}</span>
              </span>
              <StatusBadge active={s.active} />
            </button>
          ))}
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        {!selected ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>Select a shift to view its details.</div>
        ) : (
          <>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', padding: '18px 20px 0' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <RowIconBadge {...getShiftIcon(selected)} />
                <div>
                  <h2 style={{ margin: 0, fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{selected.name}</h2>
                  <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 4, textTransform: 'uppercase', letterSpacing: '.04em' }}>
                    Code: {selected.code ?? '--'}
                  </div>
                </div>
              </div>
              {/* Hidden specifically while viewing Employees: editing/deactivating/deleting the
                  Shift affects every employee listed there at once, so that action must not be
                  reachable from what is meant to be a read-only roster view — see
                  ShiftEmployeesPanel's own comment. Still available from Summary/Track Shift
                  Versions, both genuinely Shift-level (not employee-list) contexts. */}
              {canManageShifts && detailTab !== 'employees' && <KebabMenu items={kebabItems(selected)} />}
            </div>
            <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)', margin: '14px 20px 0' }}>
              {([
                { key: 'summary', label: 'Summary' },
                { key: 'employees', label: 'Employees' },
                { key: 'versions', label: 'Track Shift Versions' },
              ] as const).map(t => (
                <button key={t.key} onClick={() => setDetailTab(t.key)} style={{
                  padding: '9px 14px', background: 'transparent', border: 'none', cursor: 'pointer', fontSize: 12.5,
                  fontWeight: detailTab === t.key ? 600 : 400,
                  color: detailTab === t.key ? 'var(--brand-bright)' : 'var(--txt-mut)',
                  borderBottom: detailTab === t.key ? '2px solid var(--brand-bright)' : '2px solid transparent',
                  marginBottom: -1,
                }}>
                  {t.label}
                </button>
              ))}
            </div>
            <div style={{ padding: 20, flex: 1, overflowY: 'auto' }}>
              {detailTab === 'summary' ? (
                <ShiftSummaryPanel shift={selected} />
              ) : detailTab === 'employees' ? (
                <ShiftEmployeesPanel shift={selected} token={token} />
              ) : (
                <ShiftVersionsPanel shift={selected} token={token} />
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

export default function OrgSetupPage() {
  const { pathname } = useLocation();
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const copy = PATH_COPY[pathname] ?? PATH_COPY['/organization'];

  const role = useAuthStore(s => s.user?.role);
  // Shift master-data create/edit/delete is Super Admin only (backend-enforced via @PreAuthorize
  // in OrgService regardless of this check) -- this only controls whether the UI offers the
  // action at all, matching the "don't just hide the button" requirement by never being the
  // only line of defense.
  const canManageShifts = role === 'SUPER_ADMIN';

  const [activeTab, setActiveTab] = useState<OrgTab>('departments');
  const [penalizationSubTab, setPenalizationSubTab] = useState<'policy' | 'allocation'>('policy');
  // Set when the Policy List's employee-count link is clicked — consumed once by
  // PenalizationPolicyAllocationSection to pre-select its Penalization Policy filter, then cleared
  // so switching tabs afterward doesn't keep re-applying it.
  const [allocationInitialPolicyId, setAllocationInitialPolicyId] = useState<string | null>(null);
  const [businessUnits, setBusinessUnits] = useState<BusinessUnitRow[]>([]);
  const [departments, setDepartments] = useState<DepartmentRow[]>([]);
  const [designations, setDesignations] = useState<DesignationRow[]>([]);
  const [locations, setLocations] = useState<LocationRow[]>([]);
  const [shifts, setShifts] = useState<ShiftRow[]>([]);
  const [docTypes, setDocTypes] = useState<DocumentType[]>([]);
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[]>([]);
  const [loadError, setLoadError] = useState('');
  const [search, setSearch] = useState('');

  const [addEditModal, setAddEditModal] = useState<{
    open: boolean;
    row?: BusinessUnitRow | DepartmentRow | DesignationRow | LocationRow;
    key: number;
  }>({ open: false, key: 0 });
  const [docTypeModal, setDocTypeModal] = useState<{ open: boolean; row?: DocumentType; key: number }>({ open: false, key: 0 });
  const [leaveTypeModal, setLeaveTypeModal] = useState<{ open: boolean; row?: LeaveType; key: number }>({ open: false, key: 0 });
  const [shiftModal, setShiftModal] = useState<{ open: boolean; row?: ShiftRow; key: number }>({ open: false, key: 0 });
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);
  // The Shift & Weekly Off tab's own nested sub-tab — see TABS.shiftweeklyoff's own comment.
  const [shiftWeeklyOffSubTab, setShiftWeeklyOffSubTab] = useState<'shifts' | 'weeklyoffs' | 'rules'>('shifts');

  const tab = TABS[activeTab];
  const Icon = tab.icon;

  // Each of these 7 tabs' data used to be fetched together, eagerly, on every page load — 7
  // concurrent requests before the user could see anything, even though only one tab is ever
  // visible at a time. Now: the active tab's own section loads first (with its own small
  // in-table spinner, not a page-wide blocker), then the rest load quietly in the background
  // shortly after — so switching to a tab visited earlier is instant (cached, no refetch), a
  // brand-new tab still gets its data promptly, and dropdowns elsewhere that read another
  // section's data (e.g. Document Types' "Applicable Locations", which reads `locations`) still
  // end up populated without the user having to visit that tab first.
  type FetchableTab = 'businessunits' | 'departments' | 'designations' | 'locations' | 'shiftweeklyoff' | 'doctypes' | 'leavetypes';
  const FETCHABLE_TABS: FetchableTab[] = ['businessunits', 'departments', 'designations', 'locations', 'shiftweeklyoff', 'doctypes', 'leavetypes'];
  const loadedRef = useRef<Set<FetchableTab>>(new Set());
  const inFlightRef = useRef<Set<FetchableTab>>(new Set());
  const [sectionLoading, setSectionLoading] = useState<Partial<Record<FetchableTab, boolean>>>({});

  async function fetchSection(section: FetchableTab, opts?: { silent?: boolean; force?: boolean }) {
    if (inFlightRef.current.has(section)) return;
    if (!opts?.force && loadedRef.current.has(section)) return;
    inFlightRef.current.add(section);
    if (!opts?.silent) setSectionLoading(s => ({ ...s, [section]: true }));
    try {
      switch (section) {
        case 'businessunits': setBusinessUnits(await orgApi.listBusinessUnits(token)); break;
        case 'departments': setDepartments(await orgApi.listDepartments(token)); break;
        case 'designations': setDesignations(await orgApi.listDesignations(token)); break;
        case 'locations': setLocations(await orgApi.listLocations(token)); break;
        case 'shiftweeklyoff': setShifts(await orgApi.listShifts(token)); break;
        case 'doctypes': setDocTypes(await listAllDocTypes(token)); break;
        case 'leavetypes': setLeaveTypes(await leaveApi.listTypes(token)); break;
      }
      loadedRef.current.add(section);
      setLoadError('');
    } catch (err) {
      setLoadError(`Couldn't load ${TABS[section].label} (${err instanceof Error ? err.message : 'failed to load'})`);
    } finally {
      inFlightRef.current.delete(section);
      if (!opts?.silent) setSectionLoading(s => ({ ...s, [section]: false }));
    }
  }
  // Force-refetches whichever tab is currently active — used after a create/update/delete/toggle,
  // all of which only ever affect the section the user is already looking at.
  async function fetchAll() {
    if ((FETCHABLE_TABS as OrgTab[]).includes(activeTab)) await fetchSection(activeTab as FetchableTab, { force: true });
  }

  useEffect(() => {
    if (!token || !(FETCHABLE_TABS as OrgTab[]).includes(activeTab)) return;
    fetchSection(activeTab as FetchableTab);
  }, [token, activeTab]);

  useEffect(() => {
    if (!token) return;
    // Let the active tab's own request go first on the wire before quietly topping up the rest.
    const t = setTimeout(() => {
      FETCHABLE_TABS.forEach(section => fetchSection(section, { silent: true }));
    }, 200);
    return () => clearTimeout(t);
  }, [token]);

  useEffect(() => { setSearch(''); }, [activeTab]);

  const q = search.toLowerCase();
  const visibleBusinessUnits = businessUnits.filter(b => b.name.toLowerCase().includes(q));
  const visibleDepts  = departments.filter(d => d.name.toLowerCase().includes(q));
  const visibleDesigs = designations.filter(d => d.title.toLowerCase().includes(q));
  const visibleLocs   = locations.filter(l =>
    l.name.toLowerCase().includes(q) || (l.city ?? '').toLowerCase().includes(q)
  );
  const visibleDocTypes = docTypes.filter(d => d.name.toLowerCase().includes(q));
  const visibleLeaveTypes = leaveTypes.filter(t => t.name.toLowerCase().includes(q));
  // Shifts no longer participates in the shared search/generic-table plumbing above — its master-
  // detail layout (ShiftsMasterDetail) filters the full, unfiltered `shifts` list with its own
  // internal search state instead (see the reference design's dedicated left-panel search box).
  const visibleRows =
    activeTab === 'businessunits' ? visibleBusinessUnits :
    activeTab === 'departments' ? visibleDepts :
    activeTab === 'designations' ? visibleDesigs :
    activeTab === 'doctypes' ? visibleDocTypes :
    activeTab === 'leavetypes' ? visibleLeaveTypes :
    visibleLocs;

  function openAdd() {
    if (activeTab === 'doctypes') { setDocTypeModal(s => ({ open: true, key: s.key + 1 })); return; }
    if (activeTab === 'leavetypes') { setLeaveTypeModal(s => ({ open: true, key: s.key + 1 })); return; }
    if (activeTab === 'shiftweeklyoff' && shiftWeeklyOffSubTab === 'shifts') { setShiftModal(s => ({ open: true, key: s.key + 1 })); return; }
    setAddEditModal(s => ({ open: true, key: s.key + 1 }));
  }
  function openEdit(row: BusinessUnitRow | DepartmentRow | DesignationRow | LocationRow) {
    setAddEditModal(s => ({ open: true, row, key: s.key + 1 }));
  }
  function openEditDocType(dt: DocumentType) {
    setDocTypeModal(s => ({ open: true, row: dt, key: s.key + 1 }));
  }
  function openEditLeaveType(lt: LeaveType) {
    setLeaveTypeModal(s => ({ open: true, row: lt, key: s.key + 1 }));
  }
  function openEditShift(row: ShiftRow) {
    setShiftModal(s => ({ open: true, row, key: s.key + 1 }));
  }

  function triggerToggleActive(row: BusinessUnitRow | DepartmentRow | DesignationRow | LocationRow, label: string) {
    const wasActive = row.active;
    setConfirmState({
      title: wasActive ? `Deactivate ${label}` : `Reactivate ${label}`,
      body: wasActive
        ? `"${label}" will no longer appear in selection lists.`
        : `"${label}" will become available again in selection lists.`,
      confirmLabel: wasActive ? 'Deactivate' : 'Reactivate',
      danger: wasActive,
      onConfirm: async () => {
        if (activeTab === 'businessunits') await orgApi.toggleBusinessUnitActive(token, row.id);
        else if (activeTab === 'departments') await orgApi.toggleDepartmentActive(token, row.id);
        else if (activeTab === 'designations') await orgApi.toggleDesignationActive(token, row.id);
        else await orgApi.toggleLocationActive(token, row.id);
        showToast('success', `"${label}" ${wasActive ? 'deactivated' : 'reactivated'}`);
        await fetchAll();
      },
    });
  }

  function triggerDelete(row: BusinessUnitRow | DepartmentRow | DesignationRow | LocationRow, label: string) {
    setConfirmState({
      title: `Delete ${label}`,
      body: `"${label}" will be permanently deleted. This cannot be undone.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: async () => {
        if (activeTab === 'businessunits') await orgApi.deleteBusinessUnit(token, row.id);
        else if (activeTab === 'departments') await orgApi.deleteDepartment(token, row.id);
        else if (activeTab === 'designations') await orgApi.deleteDesignation(token, row.id);
        else await orgApi.deleteLocation(token, row.id);
        showToast('success', `"${label}" deleted`);
        await fetchAll();
      },
    });
  }

  function triggerShiftToggle(row: ShiftRow) {
    const wasActive = row.active;
    setConfirmState({
      title: wasActive ? `Deactivate ${row.name}` : `Reactivate ${row.name}`,
      body: wasActive
        ? `"${row.name}" will no longer be assignable to employees.`
        : `"${row.name}" will become assignable again.`,
      confirmLabel: wasActive ? 'Deactivate' : 'Reactivate',
      danger: wasActive,
      onConfirm: async () => {
        await orgApi.toggleShiftActive(token, row.id);
        showToast('success', `"${row.name}" ${wasActive ? 'deactivated' : 'reactivated'}`);
        await fetchAll();
      },
    });
  }

  function triggerShiftDelete(row: ShiftRow) {
    setConfirmState({
      title: `Delete ${row.name}`,
      body: `"${row.name}" will be permanently deleted. This cannot be undone.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: async () => {
        await orgApi.deleteShift(token, row.id);
        showToast('success', `"${row.name}" deleted`);
        await fetchAll();
      },
    });
  }

  function triggerDocTypeToggle(dt: DocumentType) {
    const wasActive = dt.active;
    setConfirmState({
      title: wasActive ? `Deactivate "${dt.name}"` : `Reactivate "${dt.name}"`,
      body: wasActive
        ? `"${dt.name}" will stop appearing in required-document and compliance checks, and employees can no longer upload it. The ${dt.usageCount} document(s) already uploaded are kept unchanged.`
        : `"${dt.name}" will be required again for applicable employees.`,
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
        body: `"${dt.name}" is used by ${dt.usageCount} employee document(s), so it can't be deleted. Deactivate it instead to stop new uploads while keeping existing documents.`,
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

  function shiftKebabItems(row: ShiftRow): KebabItem[] {
    // Not just hidden -- OrgService.createShift/updateShift/deleteShift/toggleShiftActive are
    // all @PreAuthorize("hasRole('SUPER_ADMIN')") regardless of what this menu offers.
    if (!canManageShifts) return [];
    return [
      { label: 'Edit', onClick: () => openEditShift(row) },
      {
        label: row.active ? 'Deactivate' : 'Reactivate',
        onClick: () => triggerShiftToggle(row),
        dividerBefore: true,
      },
      {
        label: 'Delete',
        danger: true,
        onClick: () => {
          if (row.employeeCount > 0) {
            setConfirmState({
              title: `Cannot Delete ${row.name}`,
              body: `${row.employeeCount} employee${row.employeeCount === 1 ? ' is' : 's are'} assigned to this shift. Deactivate it instead.`,
              confirmLabel: 'Got it',
              danger: false,
              onConfirm: async () => {},
            });
            return;
          }
          triggerShiftDelete(row);
        },
      },
    ];
  }

  function kebabItems(row: BusinessUnitRow | DepartmentRow | DesignationRow | LocationRow, label: string): KebabItem[] {
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
              body: `${count} employee${count === 1 ? ' is' : 's are'} assigned to this ${activeTab === 'businessunits' ? 'business unit' : activeTab.slice(0, -1)}. Remove all assignments first, or deactivate it instead.`,
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
          locationNames={locations.filter(l => l.active).map(l => l.name)}
          onClose={() => setDocTypeModal(s => ({ ...s, open: false }))}
          onSaved={() => {
            fetchAll();
            showToast('success', docTypeModal.row ? 'Updated successfully' : 'Document type created');
          }}
        />
      )}
      {leaveTypeModal.open && (
        <LeaveTypeModal
          key={leaveTypeModal.key}
          editRow={leaveTypeModal.row}
          token={token}
          onClose={() => setLeaveTypeModal(s => ({ ...s, open: false }))}
          onSaved={() => {
            fetchAll();
            showToast('success', leaveTypeModal.row ? 'Updated successfully' : 'Leave type created');
          }}
        />
      )}
      {shiftModal.open && (
        <ShiftFormModal
          key={shiftModal.key}
          editRow={shiftModal.row}
          token={token}
          onClose={() => setShiftModal(s => ({ ...s, open: false }))}
          onSaved={() => {
            fetchAll();
            showToast('success', shiftModal.row ? 'Shift updated successfully' : 'Shift created successfully');
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
        <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, marginBottom: 4 }}>
          {copy.title}
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>{copy.tagline}</p>
      </div>

      {/* Scoped to the tabs that actually consume fetchAll()'s data — Penalization Policy (and
          its Allocation sub-tab) fetch their own Business Unit/Department/Location/Policy
          lookups independently and render their own errors, so a failure here (e.g. one of the
          six unrelated Organization Master lookups) must never surface as a page-wide banner
          while the user is looking at an unrelated tab that doesn't use this data at all. */}
      {loadError && activeTab !== 'penalization' && (
        <div role="alert" style={{
          background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)',
          borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13,
        }}>
          {loadError}
        </div>
      )}

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {/* Tab bar + search + add, always stacked as two distinct rows (not just wrapped when
            tight on space) — the section tabs on their own row, the search/add controls on their
            own row directly beneath with a divider and vertical breathing room. This guarantees
            the two can never visually overlap/cover each other at any tab count or viewport
            width, which a single wrapping flex row could still do at some intermediate widths. */}
        <div className="nf-org-toolbar" style={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch', borderBottom: '1px solid var(--line)' }}>
          {/* minWidth: 0 lets this flex item actually shrink below its tabs' combined natural
              width instead of forcing the row wider than the panel — overflowX then scrolls the
              tabs themselves (each flexShrink:0/nowrap so they scroll intact rather than
              squeezing or wrapping) whenever there isn't room for all of them, at any width. */}
          <div className="nf-org-toolbar-tabs" style={{ display: 'flex', flex: 1, minWidth: 0, overflowX: 'auto', overflowY: 'hidden', padding: '0 4px' }}>
            {(Object.keys(TABS) as OrgTab[])
              // Hidden entirely for anyone but Super Admin, rather than shown and left to 403 on
              // load: unlike every other tab here (at least viewable via /organization by HR
              // Admin), the AI Assistant rate-limit configuration is Super-Admin-only end to end.
              .filter(key => key !== 'ai-assistant' || role === 'SUPER_ADMIN')
              .map(key => {
              const T = TABS[key];
              const TabIcon = T.icon;
              const isActive = activeTab === key;
              const accent = TAB_ACCENT[key];
              return (
                <button
                  key={key} onClick={() => setActiveTab(key)}
                  className="nf-org-tab" aria-selected={isActive}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 7, flexShrink: 0, whiteSpace: 'nowrap',
                    padding: '7px 14px 7px 7px', background: 'transparent', border: 'none',
                    cursor: 'pointer', fontSize: 12.5,
                    fontWeight: isActive ? 600 : 500,
                    color: isActive ? accent : 'var(--txt-mut)',
                    borderBottom: isActive ? `2px solid ${accent}` : '2px solid transparent',
                    marginBottom: -1, transition: 'color 120ms',
                    ['--tab-accent' as string]: accent,
                  }}>
                  <span className="nf-org-tab-icon">
                    <TabIcon size={14} aria-hidden="true" strokeWidth={2.25} />
                  </span>
                  {T.label}
                </button>
              );
            })}
          </div>
          {activeTab !== 'penalization' && activeTab !== 'shiftweeklyoff' && activeTab !== 'attendance'
            && activeTab !== 'ai-assistant' && (
            // Its own row directly under the tabs, right-aligned, with a divider + vertical
            // breathing room so it reads as a clearly separate toolbar strip rather than
            // crowding — or at some widths visually overlapping — the section tabs above it.
            <div className="nf-org-toolbar-actions" style={{
              display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8,
              padding: '10px 12px', borderTop: '1px solid var(--line)',
            }}>
              <div className="nf-org-search-wrap" style={{ position: 'relative' }}>
                <Search size={12} aria-hidden="true" style={{
                  position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)',
                  color: 'var(--txt-dim)', pointerEvents: 'none',
                }} />
                <input
                  className="nf-org-search-input"
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
          )}
          {/* Add Shift stays the primary top-right action (matching the approved reference), but
              only while the Shifts sub-tab itself is active — Weekly Offs/Shift and Weekly Off
              Rules manage their own add actions internally (see their own sections below). No
              shared search box here: the master-detail layout has its own, in the left panel. */}
          {activeTab === 'shiftweeklyoff' && shiftWeeklyOffSubTab === 'shifts' && canManageShifts && (
            <div className="nf-org-toolbar-actions" style={{
              display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8,
              padding: '10px 12px', borderTop: '1px solid var(--line)',
            }}>
              <button onClick={openAdd} aria-label="Add Shift" style={{
                display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
                background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
                fontSize: 12, fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap',
              }}>
                <Plus size={13} aria-hidden="true" />
                Add Shift
              </button>
            </div>
          )}
        </div>

        {/* Penalization Policy is a Policy List (Section 5), not a row-per-item table like the
            tabs below — rendered by PolicyListSection, reusing this page's shell/tab-bar/toast/
            loading/error patterns but not the generic table below. Penalization Policy
            Allocation is a sibling sub-tab of this same top-level tab (not a separate
            Organization Masters tab, and not under Time & Attendance), per the explicit
            navigation requirement. */}
        {activeTab === 'penalization' ? (
          <div style={{ padding: 18 }}>
            <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)', marginBottom: 16 }}>
              {([
                { key: 'policy', label: 'Penalization Policy' },
                { key: 'allocation', label: 'Penalization Policy Allocation' },
              ] as const).map(t => (
                <button key={t.key} onClick={() => setPenalizationSubTab(t.key)} style={{
                  padding: '9px 14px', background: 'transparent', border: 'none', cursor: 'pointer',
                  fontSize: 12.5, fontWeight: penalizationSubTab === t.key ? 600 : 400,
                  color: penalizationSubTab === t.key ? 'var(--brand-bright)' : 'var(--txt-mut)',
                  borderBottom: penalizationSubTab === t.key ? '2px solid var(--brand-bright)' : '2px solid transparent',
                  marginBottom: -1,
                }}>
                  {t.label}
                </button>
              ))}
            </div>
            {penalizationSubTab === 'policy' ? (
              <PolicyListSection token={token} onViewAllocations={policyId => {
                setAllocationInitialPolicyId(policyId);
                setPenalizationSubTab('allocation');
              }} />
            ) : (
              <PenalizationPolicyAllocationSection
                token={token}
                initialPolicyFilter={allocationInitialPolicyId}
                onInitialPolicyFilterConsumed={() => setAllocationInitialPolicyId(null)}
              />
            )}
          </div>
        ) : activeTab === 'shiftweeklyoff' ? (
          <div>
            <div style={{ display: 'flex', gap: 4, borderBottom: '1px solid var(--line)', padding: '0 18px' }}>
              {([
                { key: 'shifts', label: 'Shifts' },
                { key: 'weeklyoffs', label: 'Weekly Offs' },
                { key: 'rules', label: 'Shift and Weekly Off Rules' },
              ] as const).map(t => (
                <button key={t.key} onClick={() => setShiftWeeklyOffSubTab(t.key)} style={{
                  padding: '9px 14px', background: 'transparent', border: 'none', cursor: 'pointer',
                  fontSize: 12.5, fontWeight: shiftWeeklyOffSubTab === t.key ? 600 : 400,
                  color: shiftWeeklyOffSubTab === t.key ? 'var(--brand-bright)' : 'var(--txt-mut)',
                  borderBottom: shiftWeeklyOffSubTab === t.key ? '2px solid var(--brand-bright)' : '2px solid transparent',
                  marginBottom: -1,
                }}>
                  {t.label}
                </button>
              ))}
            </div>
            {shiftWeeklyOffSubTab === 'shifts' ? (
              <ShiftsMasterDetail
                shifts={shifts}
                loading={!!sectionLoading.shiftweeklyoff}
                token={token}
                canManageShifts={canManageShifts}
                kebabItems={shiftKebabItems}
              />
            ) : shiftWeeklyOffSubTab === 'weeklyoffs' ? (
              <WeeklyOffSection token={token} />
            ) : (
              <ShiftWeeklyOffRulesSection token={token} />
            )}
          </div>
        ) : activeTab === 'attendance' ? (
          <AttendanceRulesSection token={token} />
        ) : activeTab === 'ai-assistant' ? (
          <AiRateLimitSettingsSection token={token} />
        ) : (
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
              {sectionLoading[activeTab as FetchableTab] && visibleRows.length === 0 ? (
                <tr>
                  <td colSpan={tab.columns.length + 1} style={{ padding: '52px 24px', textAlign: 'center' }}>
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
                      <span className="nf-org-spinner" aria-hidden="true" />
                      <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading {tab.label.toLowerCase()}…</div>
                    </div>
                  </td>
                </tr>
              ) : visibleRows.length === 0 ? (
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
              ) : activeTab === 'businessunits' ? (
                visibleBusinessUnits.map(b => (
                  <tr key={b.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <RowIconBadge {...getBusinessUnitIcon(b.name)} />
                        {b.name}
                      </div>
                    </td>
                    <td style={{ padding: '10px 16px' }}><CountBadge count={b.employeeCount} /></td>
                    <td style={{ padding: '10px 16px' }}><StatusBadge active={b.active} /></td>
                    <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                      <KebabMenu items={kebabItems(b, b.name)} />
                    </td>
                  </tr>
                ))
              ) : activeTab === 'departments' ? (
                visibleDepts.map(d => (
                  <tr key={d.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <RowIconBadge {...getDepartmentIcon(d.name)} />
                        {d.name}
                      </div>
                    </td>
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
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <RowIconBadge {...getDesignationIcon(d.title)} />
                        {d.title}
                      </div>
                    </td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{d.grade ?? '—'}</td>
                    <td style={{ padding: '10px 16px', fontFamily: 'Inter, sans-serif', fontSize: 12, color: 'var(--txt-mut)' }}>{d.level ?? '—'}</td>
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
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <RowIconBadge {...getLocationIcon(l)} />
                        {l.name}
                      </div>
                    </td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.city ?? '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.state ?? '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>{l.country ?? '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}>{l.timezone ?? '—'}</td>
                    <td style={{ padding: '10px 16px' }}><CountBadge count={l.employeeCount} /></td>
                    <td style={{ padding: '10px 16px' }}><StatusBadge active={l.active} /></td>
                    <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                      <KebabMenu items={kebabItems(l, l.name)} />
                    </td>
                  </tr>
                ))
              ) : activeTab === 'doctypes' ? (
                visibleDocTypes.map(dt => (
                  <tr key={dt.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <RowIconBadge {...getDocTypeIcon(dt.name)} />
                        {dt.name}
                      </div>
                    </td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 12 }}>{dt.requiresVerification ? '✓ Yes' : '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 12 }}>{dt.requiresExpiryDate ? '✓ Yes' : '—'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 11 }}>{dt.applicableEmploymentTypes || 'All'}</td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 11 }}>{dt.applicableLocations || 'All'}</td>
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
              ) : (
                visibleLeaveTypes.map(lt => (
                  <tr key={lt.id} style={{ borderBottom: '1px solid var(--line)' }}>
                    <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <RowIconBadge {...getLeaveTypeIcon(lt.name)} />
                        {lt.name}
                      </div>
                    </td>
                    <td style={{ padding: '10px 16px', color: 'var(--txt-mut)', fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}>{lt.code}</td>
                    <td style={{ padding: '10px 16px' }}><ClassificationBadge classification={lt.classification} /></td>
                    <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                      <KebabMenu items={[
                        { label: 'Edit', onClick: () => openEditLeaveType(lt) },
                      ]} />
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        )}
      </div>
    </div>
  );
}

// ── Weekly Offs ──────────────────────────────────────────────────────────────
// WeeklyOffPolicy is the sole source of truth for weekly-off (Shift carries no working-days
// concept — see the backend's own comment on Shift.workingDays' removal from this surface).
// Full-day-only for P1, matching the offDays model already in place; no active/inactive concept
// (not requested for P1, and the product's own Weekly Off reference screens don't show one).
// Self-contained, mirroring how the Penalization Policy tab manages its own state/fetch rather
// than sharing OrgSetupPage's generic table plumbing.

interface WeeklyOffFormModalProps {
  editRow?: WeeklyOffPolicyRow;
  token: string;
  onClose(): void;
  onSaved(): void;
}

function WeeklyOffFormModal({ editRow, token, onClose, onSaved }: WeeklyOffFormModalProps) {
  const isEdit = !!editRow;
  const [name, setName] = useState(editRow?.name ?? '');
  const [offDays, setOffDays] = useState<string[]>(editRow?.offDays ?? []);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  function toggleDay(day: string) {
    setOffDays(prev => prev.includes(day) ? prev.filter(d => d !== day) : [...prev, day]);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    if (!name.trim()) { setError('Weekly off policy name is required'); return; }
    if (offDays.length === 0) { setError('At least one off day is required'); return; }
    const payload = { name: name.trim(), offDays };
    setLoading(true);
    try {
      if (isEdit && editRow) {
        await orgApi.updateWeeklyOffPolicy(token, editRow.id, payload);
      } else {
        await orgApi.createWeeklyOffPolicy(token, payload);
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
    <div role="dialog" aria-modal="true" aria-label={isEdit ? 'Edit Weekly Off Policy' : 'Add Weekly Off Policy'} style={{ position: 'fixed', inset: 0, zIndex: 200, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width: 440, maxWidth: '94vw' }}>
        <h2 style={{ margin: '0 0 20px', fontSize: 15, fontWeight: 700 }}>{isEdit ? 'Edit Weekly Off Policy' : 'Add Weekly Off Policy'}</h2>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 16 }}>
            <label style={labelS}>Policy Name *</label>
            <input style={inputS} value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Standard Weekly Off Policy" required autoFocus />
          </div>
          <div style={{ marginBottom: 20 }}>
            <label style={labelS}>Off Days * <span style={{ fontWeight: 400, color: 'var(--txt-dim)' }}>(full day)</span></label>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 6 }}>
              {WEEKDAYS.map(day => (
                <label key={day} style={{
                  display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, cursor: 'pointer',
                  padding: '4px 9px', borderRadius: 14, border: '1px solid var(--line2)',
                  background: offDays.includes(day) ? 'var(--brand)' : 'var(--raised)',
                  color: offDays.includes(day) ? '#fff' : 'var(--txt-mut)',
                }}>
                  <input type="checkbox" checked={offDays.includes(day)} onChange={() => toggleDay(day)} style={{ display: 'none' }} />
                  {day.slice(0, 3)}
                </label>
              ))}
            </div>
          </div>
          {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginBottom: 12 }}>{error}</div>}
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

function WeeklyOffSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const role = useAuthStore(s => s.user?.role);
  const canManage = role === 'SUPER_ADMIN';

  const [policies, setPolicies] = useState<WeeklyOffPolicyRow[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState('');
  const [modal, setModal] = useState<{ open: boolean; row?: WeeklyOffPolicyRow; key: number }>({ open: false, key: 0 });
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);

  async function fetchAll() {
    try {
      setPolicies(await orgApi.listWeeklyOffPolicies(token));
      setLoadError('');
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Couldn't load weekly off policies");
    }
  }

  useEffect(() => { if (token) fetchAll(); }, [token]);

  const visible = policies.filter(p => p.name.toLowerCase().includes(search.toLowerCase()));

  function triggerDelete(row: WeeklyOffPolicyRow) {
    if (row.employeeCount > 0) {
      setConfirmState({
        title: `Cannot Delete ${row.name}`,
        body: `${row.employeeCount} employee${row.employeeCount === 1 ? ' is' : 's are'} assigned to this weekly off policy. Reassign them first.`,
        confirmLabel: 'Got it', danger: false, onConfirm: async () => {},
      });
      return;
    }
    setConfirmState({
      title: `Delete ${row.name}`,
      body: `"${row.name}" will be permanently deleted. This cannot be undone.`,
      confirmLabel: 'Delete', danger: true,
      onConfirm: async () => {
        await orgApi.deleteWeeklyOffPolicy(token, row.id);
        showToast('success', `"${row.name}" deleted`);
        await fetchAll();
      },
    });
  }

  return (
    <div style={{ padding: 18 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div style={{ position: 'relative' }}>
          <Search size={12} aria-hidden="true" style={{ position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input
            type="search" value={search} onChange={e => setSearch(e.target.value)}
            placeholder="Search weekly off policies…" aria-label="Search weekly off policies"
            style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 10px 5px 26px', fontSize: 12, color: 'var(--txt)', outline: 'none', width: 220 }}
          />
        </div>
        {canManage && (
          <button onClick={() => setModal(s => ({ open: true, key: s.key + 1 }))} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
            <Plus size={13} aria-hidden="true" /> Add Weekly Off
          </button>
        )}
      </div>
      {loadError && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5, marginBottom: 12 }}>{loadError}</div>}
      <div style={{ overflowX: 'auto', border: '1px solid var(--line)', borderRadius: 10 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
          <thead>
            <tr style={{ background: 'var(--raised)' }}>
              {['Name', 'Off Days', 'Employees'].map(col => (
                <th key={col} style={{ padding: '10px 16px', textAlign: 'left', fontWeight: 600, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase' }}>{col}</th>
              ))}
              <th style={{ padding: '10px 16px', textAlign: 'right', fontWeight: 600, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase', width: 52 }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {visible.length === 0 ? (
              <tr>
                <td colSpan={4} style={{ padding: '52px 24px', textAlign: 'center' }}>
                  {search ? (
                    <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>No weekly off policies match "{search}"</div>
                  ) : (
                    <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>No weekly off policies configured yet.</div>
                  )}
                </td>
              </tr>
            ) : (
              visible.map(p => (
                <tr key={p.id} style={{ borderBottom: '1px solid var(--line)' }}>
                  <td style={{ padding: '10px 16px', color: 'var(--txt)', fontWeight: 500 }}>{p.name}</td>
                  <td style={{ padding: '10px 16px', color: 'var(--txt-mut)' }}>
                    {p.offDays.map(d => d.slice(0, 3).charAt(0) + d.slice(1, 3).toLowerCase()).join(', ') || '—'}
                  </td>
                  <td style={{ padding: '10px 16px' }}><CountBadge count={p.employeeCount} /></td>
                  <td style={{ padding: '10px 16px', textAlign: 'right' }}>
                    <KebabMenu items={canManage ? [
                      { label: 'Edit', onClick: () => setModal(s => ({ open: true, row: p, key: s.key + 1 })) },
                      { label: 'Delete', danger: true, dividerBefore: true, onClick: () => triggerDelete(p) },
                    ] : []} />
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      {modal.open && (
        <WeeklyOffFormModal
          key={modal.key} editRow={modal.row} token={token}
          onClose={() => setModal(s => ({ ...s, open: false }))}
          onSaved={() => { showToast('success', modal.row ? 'Weekly off policy updated' : 'Weekly off policy added'); fetchAll(); }}
        />
      )}
      {confirmState && (
        <ConfirmModal
          title={confirmState.title} body={confirmState.body} confirmLabel={confirmState.confirmLabel}
          danger={confirmState.danger} onConfirm={confirmState.onConfirm} onClose={() => setConfirmState(null)}
        />
      )}
    </div>
  );
}

// ── Shifts & Weekly Off Rules ────────────────────────────────────────────────
// Org-level singleton (ShiftWeeklyOffRules). P1 holds exactly one setting, Maximum Shift Day
// Duration — a pure elapsed-time boundary for logical-workday attribution (see the backend's
// ShiftDayPolicy), NOT a per-shift field and NOT an attendance-window/stale/auto-checkout
// control. Deliberately NOT the Keka-style request-workflow rules editor (shift-change/weekly-
// off request rate limits) — that's P2 and out of scope; this is a single-field settings form,
// matching the simplest existing OneHR pattern rather than inventing a Keka-like experience.

function ShiftWeeklyOffRulesSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const role = useAuthStore(s => s.user?.role);
  const canManage = role === 'SUPER_ADMIN';

  const [hours, setHours] = useState('');
  const [savedHours, setSavedHours] = useState<number | null>(null);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!token) return;
    orgApi.getShiftWeeklyOffRules(token)
      .then(r => { setSavedHours(r.maximumShiftDayDurationHours); setHours(String(r.maximumShiftDayDurationHours)); })
      .catch(err => setLoadError(err instanceof Error ? err.message : "Couldn't load Shifts & Weekly Off Rules"));
  }, [token]);

  const dirty = savedHours != null && hours.trim() !== '' && Number(hours) !== savedHours;

  async function save() {
    setError('');
    const parsed = Number(hours);
    if (hours.trim() === '' || isNaN(parsed) || parsed <= 0 || parsed > 24) {
      setError('Maximum Shift Day Duration must be greater than 0 and at most 24 hours');
      return;
    }
    setLoading(true);
    try {
      const updated = await orgApi.updateShiftWeeklyOffRules(token, parsed);
      setSavedHours(updated.maximumShiftDayDurationHours);
      setHours(String(updated.maximumShiftDayDurationHours));
      showToast('success', 'Shifts & Weekly Off Rules updated');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  const inputS: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', width: 140, boxSizing: 'border-box' };

  return (
    <div style={{ padding: 18, maxWidth: 560 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Maximum Shift Day Duration</h2>
      <p style={{ margin: '0 0 18px', fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
        The elapsed window, from an employee's assigned shift's own start time, during which attendance activity
        still belongs to that shift's logical workday. Applies org-wide, to every shift-assigned employee.
        Does not affect employees with no assigned shift, and is not a per-shift setting.
      </p>
      {loadError && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5, marginBottom: 12 }}>{loadError}</div>}
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12 }}>
        <div>
          <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Hours</label>
          <input
            type="number" min={0.1} max={24} step={0.5} style={inputS}
            value={hours} onChange={e => setHours(e.target.value)}
            disabled={!canManage || savedHours == null}
          />
        </div>
        {canManage && (
          <button onClick={save} disabled={loading || !dirty} style={{
            padding: '8px 16px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
            fontSize: 12.5, fontWeight: 600, cursor: loading || !dirty ? 'not-allowed' : 'pointer', opacity: loading || !dirty ? 0.6 : 1,
          }}>
            {loading ? 'Saving…' : 'Save'}
          </button>
        )}
      </div>
      {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginTop: 12 }}>{error}</div>}
      {!canManage && <p style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 14 }}>Only a Super Admin can change this setting.</p>}
    </div>
  );
}

// ── Attendance Rules ─────────────────────────────────────────────────────────
// Org-level singleton (AttendanceRules). Currently holds exactly one setting, Half Day Max
// Hours — the absolute-hours threshold below which a worked day is classified HALF_DAY (see the
// backend's AttendanceService/WebClockInService/RegularizationService, all three of which read
// this via AttendanceRulesService). Migrated off app.attendance.half-day-max-hours (Workstream
// B) — the only app.attendance.* YAML property that genuinely needed a persisted, admin-editable
// home; see scratch/workstream-b-settings-investigation.md for why late-grace-minutes,
// daily-break-budget-minutes, full-day-min-hours, and the regularization lookback/monthly-limit
// settings were left alone, removed outright, or deferred to a future Regularization Policy
// instead of joining this table. A separate top-level tab from "Shift and Weekly Off Rules"
// since this isn't a shift/weekly-off concept — same single-field settings form pattern though.

function AttendanceRulesSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const role = useAuthStore(s => s.user?.role);
  const canManage = role === 'SUPER_ADMIN';

  const [hours, setHours] = useState('');
  const [savedHours, setSavedHours] = useState<number | null>(null);
  const [timezone, setTimezone] = useState('');
  const [savedTimezone, setSavedTimezone] = useState<string | null>(null);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [tzError, setTzError] = useState('');
  const [tzLoading, setTzLoading] = useState(false);

  useEffect(() => {
    if (!token) return;
    orgApi.getAttendanceRules(token)
      .then(r => {
        setSavedHours(r.halfDayMaxHours); setHours(String(r.halfDayMaxHours));
        setSavedTimezone(r.defaultTimezone); setTimezone(r.defaultTimezone);
      })
      .catch(err => setLoadError(err instanceof Error ? err.message : "Couldn't load Attendance Rules"));
  }, [token]);

  const dirty = savedHours != null && hours.trim() !== '' && Number(hours) !== savedHours;
  const tzDirty = savedTimezone != null && timezone.trim() !== '' && timezone.trim() !== savedTimezone;

  async function save() {
    setError('');
    const parsed = Number(hours);
    if (hours.trim() === '' || isNaN(parsed) || parsed <= 0 || parsed >= 24) {
      setError('Half Day Max Hours must be greater than 0 and less than 24 hours');
      return;
    }
    setLoading(true);
    try {
      const updated = await orgApi.updateAttendanceRules(token, parsed);
      setSavedHours(updated.halfDayMaxHours);
      setHours(String(updated.halfDayMaxHours));
      showToast('success', 'Attendance Rules updated');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  async function saveTimezone() {
    setTzError('');
    if (timezone.trim() === '') {
      setTzError('Default Timezone is required');
      return;
    }
    setTzLoading(true);
    try {
      const updated = await orgApi.updateDefaultTimezone(token, timezone.trim());
      setSavedTimezone(updated.defaultTimezone);
      setTimezone(updated.defaultTimezone);
      showToast('success', 'Default Timezone updated');
    } catch (err) {
      setTzError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setTzLoading(false);
    }
  }

  const inputS: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', width: 140, boxSizing: 'border-box' };

  return (
    <div style={{ padding: 18, maxWidth: 560 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Half Day Max Hours</h2>
      <p style={{ margin: '0 0 18px', fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
        A worked day with fewer hours than this is classified Half Day. Applies org-wide, to every
        employee's attendance calculation. An absolute-hours threshold, not a percentage of shift duration.
      </p>
      {loadError && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5, marginBottom: 12 }}>{loadError}</div>}
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12 }}>
        <div>
          <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Hours</label>
          <input
            type="number" min={0.1} max={23.9} step={0.5} style={inputS}
            value={hours} onChange={e => setHours(e.target.value)}
            disabled={!canManage || savedHours == null}
          />
        </div>
        {canManage && (
          <button onClick={save} disabled={loading || !dirty} style={{
            padding: '8px 16px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
            fontSize: 12.5, fontWeight: 600, cursor: loading || !dirty ? 'not-allowed' : 'pointer', opacity: loading || !dirty ? 0.6 : 1,
          }}>
            {loading ? 'Saving…' : 'Save'}
          </button>
        )}
      </div>
      {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginTop: 12 }}>{error}</div>}
      {!canManage && <p style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 14 }}>Only a Super Admin can change this setting.</p>}

      <h2 style={{ margin: '28px 0 4px', fontSize: 15, fontWeight: 700 }}>Default Timezone</h2>
      <p style={{ margin: '0 0 18px', fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
        Org-wide fallback IANA zone id, used only for an employee who has no Location assigned at
        all. Every employee's attendance timezone otherwise comes entirely from their assigned
        Location (see the Locations tab) — employees have no timezone setting of their own.
        Changing this never reinterprets any existing attendance record.
      </p>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12 }}>
        <div>
          <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Timezone</label>
          <input
            style={{ ...inputS, width: 220 }}
            value={timezone} onChange={e => setTimezone(e.target.value)}
            placeholder="e.g. Asia/Kolkata" list="nf-default-tz-options"
            disabled={!canManage || savedTimezone == null}
          />
          <datalist id="nf-default-tz-options">
            <option value="Asia/Kolkata" /><option value="America/New_York" /><option value="America/Los_Angeles" />
            <option value="America/Chicago" /><option value="Europe/London" /><option value="Australia/Sydney" />
            <option value="Asia/Singapore" /><option value="Asia/Dubai" /><option value="Pacific/Auckland" />
            <option value="Asia/Kathmandu" /><option value="Asia/Chittagong" />
          </datalist>
        </div>
        {canManage && (
          <button onClick={saveTimezone} disabled={tzLoading || !tzDirty} style={{
            padding: '8px 16px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
            fontSize: 12.5, fontWeight: 600, cursor: tzLoading || !tzDirty ? 'not-allowed' : 'pointer', opacity: tzLoading || !tzDirty ? 0.6 : 1,
          }}>
            {tzLoading ? 'Saving…' : 'Save'}
          </button>
        )}
      </div>
      {tzError && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginTop: 12 }}>{tzError}</div>}
    </div>
  );
}

// ── AI Assistant — per-user rate limit ─────────────────────────────────────────
// Persisted singleton (ai_rate_limit_settings, V192), same shape as AttendanceRulesSection above.
// Unlike every other tab on this page, read is Super-Admin-only too, not just write — the
// tab-bar filter above hides this entry entirely for anyone else, so canManage here is a second,
// defensive layer rather than the only gate, matching how canManageShifts is used elsewhere on
// this page.
function AiRateLimitSettingsSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const role = useAuthStore(s => s.user?.role);
  const canManage = role === 'SUPER_ADMIN';

  const [enabled, setEnabled] = useState(true);
  const [requests, setRequests] = useState('');
  const [windowMinutes, setWindowMinutes] = useState('');
  const [saved, setSaved] = useState<aiAssistantApi.AiRateLimitSettings | null>(null);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!token || !canManage) return;
    aiAssistantApi.fetchRateLimitSettings(token)
      .then(s => {
        setSaved(s);
        setEnabled(s.enabled);
        setRequests(String(s.requestsPerWindow));
        setWindowMinutes(String(s.windowMinutes));
      })
      .catch(err => setLoadError(err instanceof Error ? err.message : "Couldn't load the AI Assistant usage limit"));
  }, [token, canManage]);

  const dirty = saved != null && (
    enabled !== saved.enabled
    || Number(requests) !== saved.requestsPerWindow
    || Number(windowMinutes) !== saved.windowMinutes
  );

  async function save() {
    setError('');
    const requestsN = Number(requests);
    const windowN = Number(windowMinutes);
    // Bounds mirror UpdateAiRateLimitSettingsRequest's bean validation and V192's DB CHECKs.
    if (requests.trim() === '' || !Number.isInteger(requestsN) || requestsN < 1 || requestsN > 1000) {
      setError('Requests must be a whole number between 1 and 1000');
      return;
    }
    if (windowMinutes.trim() === '' || !Number.isInteger(windowN) || windowN < 1 || windowN > 1440) {
      setError('Time window must be a whole number of minutes between 1 and 1440 (24 hours)');
      return;
    }
    setLoading(true);
    try {
      const updated = await aiAssistantApi.updateRateLimitSettings(token, {
        enabled, requestsPerWindow: requestsN, windowMinutes: windowN,
      });
      setSaved(updated);
      setEnabled(updated.enabled);
      setRequests(String(updated.requestsPerWindow));
      setWindowMinutes(String(updated.windowMinutes));
      showToast('success', 'AI Assistant usage limit updated');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  const inputS: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 13, color: 'var(--txt)', width: 100, boxSizing: 'border-box' };

  if (!canManage) {
    // Defensive only — the tab itself is hidden for this role, so reaching here means a direct
    // navigation rather than the normal flow.
    return (
      <div style={{ padding: 18, maxWidth: 560 }}>
        <p style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Only a Super Admin can view or change this setting.</p>
      </div>
    );
  }

  return (
    <div style={{ padding: 18, maxWidth: 560 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>AI Assistant Usage Limit</h2>
      <p style={{ margin: '0 0 18px', fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55 }}>
        Each assistant message costs a real embedding call and a real completion call against a
        metered API. This limits how many messages one employee can send in a given time window,
        to keep usage predictable.
      </p>
      {loadError && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5, marginBottom: 12 }}>{loadError}</div>}

      <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', marginBottom: 18, cursor: saved == null ? 'default' : 'pointer' }}>
        <input type="checkbox" checked={enabled} disabled={saved == null}
          onChange={e => setEnabled(e.target.checked)} />
        Rate limiting enabled
      </label>

      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Requests</label>
          <input type="number" min={1} max={1000} step={1} style={inputS}
            value={requests} onChange={e => setRequests(e.target.value)}
            disabled={saved == null} />
        </div>
        <div>
          <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 }}>Time window (minutes)</label>
          <input type="number" min={1} max={1440} step={1} style={inputS}
            value={windowMinutes} onChange={e => setWindowMinutes(e.target.value)}
            disabled={saved == null} />
        </div>
        <button onClick={save} disabled={loading || !dirty} style={{
          padding: '8px 16px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
          fontSize: 12.5, fontWeight: 600, cursor: loading || !dirty ? 'not-allowed' : 'pointer', opacity: loading || !dirty ? 0.6 : 1,
        }}>
          {loading ? 'Saving…' : 'Save'}
        </button>
      </div>
      {requests.trim() !== '' && windowMinutes.trim() !== '' && (
        <p style={{ margin: '14px 0 0', fontSize: 11.5, color: 'var(--txt-dim)' }}>
          Each employee can send up to {requests || '—'} assistant {Number(requests) === 1 ? 'message' : 'messages'} every {windowMinutes || '—'} {Number(windowMinutes) === 1 ? 'minute' : 'minutes'}.
        </p>
      )}
      {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginTop: 12 }}>{error}</div>}
    </div>
  );
}
