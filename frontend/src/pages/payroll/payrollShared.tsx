import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Wallet, FileCheck2, Receipt, ClipboardList, PauseCircle, Undo2, Search, HelpCircle, ChevronDown } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { useAuthStore } from '../../store/authStore';
import { employeesApi, type EmployeeRecord } from '../../api/employees';
import './payroll.css';

/** Payslips/review items/ad-hoc entries carry only an employeeId — this resolves it to a name for
 * display and for the employee pickers on the ad-hoc payment/deduction/hold forms. One fetch per
 * page mount (the full org roster — this console is HR Admin/Super Admin only, who already have
 * org-wide employee visibility), not per row. */
export function useEmployeeDirectory() {
  const token = useAuthStore(s => s.token) ?? '';
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);

  useEffect(() => {
    if (!token) return;
    employeesApi.list(token).then(setEmployees).catch(() => {});
  }, [token]);

  const nameFor = (userId: string) => employees.find(e => e.userId === userId)?.fullName ?? userId;
  return { employees, nameFor };
}

// ── Shared styles (same shape as pages/assets/shared.tsx / pages/bench/ui.tsx) ──

export const panelStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', overflow: 'hidden' };
export const thStyle: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 800, color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.09em', borderBottom: '1px solid color-mix(in srgb, var(--brand) 22%, var(--line))', whiteSpace: 'nowrap', background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))' };
export const tdStyle: React.CSSProperties = { padding: '13px 16px', fontSize: 13.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
export const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
export const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
export const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
export const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 480, boxShadow: '0 24px 64px rgba(0,0,0,.55)', maxHeight: '90vh', overflowY: 'auto' };

export function fmtCurrency(n?: number | null) {
  if (n == null) return '–';
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n);
}

export function fmtDate(s?: string | null) {
  if (!s) return '–';
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function fmtDateTime(s?: string | null) {
  if (!s) return '–';
  return new Date(s).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

// Semantic status/decision → the app's theme tokens (--ok/--warn/--risk/--info), never a fixed hex,
// so badges follow the selected accent color and light/dark mode. Covers both PayrollRun statuses
// and the C22/C23 review decisions (same badge component for both — they never appear in the same
// column, so there's no ambiguity in sharing one map).
const STATUS_TONE: Record<string, string> = {
  DRAFT: 'var(--txt-dim)',
  SUBMITTED: 'var(--warn)',
  APPROVED: 'var(--ok)',
  DISBURSED: 'var(--ok)',
  PENDING: 'var(--warn)',
  REJECTED: 'var(--risk)',
  MARK_LOP: 'var(--ok)',
  DEDUCT_FROM_LEAVE: 'var(--info)',
  NO_ACTION: 'var(--info)',
  ACTIVE: 'var(--warn)',
  RELEASED: 'var(--ok)',
  CANCELLED: 'var(--txt-dim)',
  COMPLETED: 'var(--ok)',
  APPLIED: 'var(--ok)',
};

export function StatusBadge({ status }: { status: string }) {
  const tone = STATUS_TONE[status] ?? 'var(--txt-dim)';
  return (
    <span className="nf-asset-pill" style={{
      background: `color-mix(in srgb, ${tone} 13%, transparent)`, color: tone,
      border: `1px solid color-mix(in srgb, ${tone} 30%, transparent)`,
    }}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}

export function SectionHead({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
      <h2 style={{ margin: 0, fontSize: 18, fontWeight: 800, letterSpacing: '-.01em', fontFamily: 'Inter, sans-serif', color: 'var(--txt)' }}>{title}</h2>
      {action}
    </div>
  );
}

// Same categorical order already validated and in use for the talent module's pie charts
// (talent/DashboardTab.tsx's PIE_PALETTE) — reused rather than re-picked, so color identity for
// "series N" stays consistent app-wide instead of drifting per feature.
export const PIE_PALETTE = ['#B11116', '#4C8DD6', '#2FB67C', '#E0A93B', '#8B5FBF', 'var(--raised2)'];
export const chartTooltipStyle = { contentStyle: { background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7, fontSize: 12, color: 'var(--txt)' } };

export function ChartCard({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <div style={{ ...panelStyle, boxShadow: 'none', padding: '16px 18px' }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{title}</div>
      {hint && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2, marginBottom: 10 }}>{hint}</div>}
      {!hint && <div style={{ marginBottom: 12 }} />}
      {children}
    </div>
  );
}

// Consistent "you can look but not touch" wording — several tabs used to just silently omit
// their Add/Create button for a non-configuring viewer with no explanation at all, while others
// (SalaryRevisionTab) already explained why. Same message everywhere now.
export function NoPermissionNote({ action }: { action: string }) {
  return <span style={{ fontSize: 13, color: 'var(--txt-dim)' }}>View only — you don't have permission to {action}.</span>;
}

// The hand-rolled "Select… + employees.map(...)" dropdown used to be copy-pasted near-identically
// across half a dozen tabs (Ad-hoc Payments/Deductions, Salary Holds, Statutory Override, Employee
// Compensation, Salary Revision) — some scoped to the full org directory, others to just this
// run's own employees. `options` takes whichever list the caller already has and how it wants each
// one labelled, so this stays agnostic to that distinction rather than picking one for everybody.
export function EmployeePicker({ value, onChange, options }: {
  value: string; onChange: (id: string) => void; options: { id: string; label: string }[];
}) {
  return (
    <select value={value} onChange={e => onChange(e.target.value)} style={inputStyle}>
      <option value="">Select…</option>
      {options.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
    </select>
  );
}

// The filter/search strip that sits above a list table (Keka's "filter bar" pattern) — a search
// box plus whatever filter controls the caller already has real data for. Deliberately doesn't
// fabricate dropdowns the underlying data can't back; `children` is where a caller adds its own
// (e.g. a status <select>) using the same inputStyle-less, borderless look as the search box.
export function FilterBar({ search, onSearchChange, placeholder = 'Search employee', children }: {
  search?: string; onSearchChange?: (v: string) => void; placeholder?: string; children?: ReactNode;
}) {
  return (
    <div className="nf-pr-filterbar">
      {children}
      {onSearchChange && (
        <label className="nf-pr-filterbar-search">
          <Search size={14} />
          <input value={search ?? ''} onChange={e => onSearchChange(e.target.value)} placeholder={placeholder} />
        </label>
      )}
    </div>
  );
}

/** Overview's step-card grid tile — a round pastel-tinted icon, a primary-coloured title link and
 * a grey caption, matching Keka's "Run Payroll" step cards. Reuses the same --mk tone convention
 * as StatTile, just without the big KPI number (this is a nav link, not a readout). */
export function StepCard({ icon, tone, label, caption, onClick, disabled, disabledReason }: {
  icon: ReactNode; tone: string; label: string; caption?: string; onClick: () => void; disabled?: boolean; disabledReason?: string;
}) {
  return (
    <button
      type="button" className="nf-pr-stepcard" style={{ ['--mk' as string]: tone }}
      onClick={onClick} disabled={disabled} title={disabled ? disabledReason : undefined}
    >
      <span className="nf-pr-stepcard-ico">{icon}</span>
      <span>
        <div className="nf-pr-stepcard-title">{label}</div>
        {caption && <div className="nf-pr-stepcard-caption">{caption}</div>}
      </span>
    </button>
  );
}

export function EmptyRow({ cols, msg }: { cols: number; msg: string }) {
  return (
    <tr>
      <td colSpan={cols} style={{ padding: '36px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>{msg}</td>
    </tr>
  );
}

/** KPI tile: label and icon on top, the number at the bottom, a coloured edge on the left. */
// onClick is optional — most StatTile uses are pure KPI display (the run console's
// Employees/Gross/Deductions/Net row has nowhere to navigate to). Where a tile corresponds to an
// actual section elsewhere on the page (Payroll Setup's per-section counts), passing onClick turns
// it into a quick-nav button too — same visual, doubling as the thing the user asked for either way.
export function StatTile({ label, value, hint, icon, tone, onClick }: { label: string; value: string; hint?: string; icon: ReactNode; tone: string; onClick?: () => void }) {
  const content = (
    <>
      <div className="nf-pr-tile-top">
        <span className="nf-pr-tile-label">{label}</span>
        <span className="nf-pr-tile-ico">{icon}</span>
      </div>
      <div className="nf-pr-tile-val">{value}</div>
      {hint && <div className="nf-pr-tile-hint">{hint}</div>}
    </>
  );
  if (onClick) {
    return (
      <button type="button" className="nf-pr-tile" style={{ ['--mk' as string]: tone, cursor: 'pointer', textAlign: 'left', width: '100%', font: 'inherit', color: 'inherit' }} onClick={onClick}>
        {content}
      </button>
    );
  }
  return <div className="nf-pr-tile" style={{ ['--mk' as string]: tone }}>{content}</div>;
}

// ── Modal wrapper ─────────────────────────────────────────

function modalIconFor(title: string): LucideIcon {
  const t = title.toLowerCase();
  if (t.includes('rollback')) return Undo2;
  if (t.includes('hold')) return PauseCircle;
  if (t.includes('deduction')) return ClipboardList;
  if (t.includes('payment')) return Receipt;
  if (t.includes('override') || t.includes('lop')) return FileCheck2;
  return Wallet;
}

// A plain yes/no confirmation for an irreversible action with no other input to collect (removing
// an ad-hoc payment, cancelling a deduction, releasing a hold, clearing an override) — distinct
// from the Reason-collecting modals (Rollback, Reject) elsewhere in this module, which already
// have their own built-in pause-before-acting step. None of ActionDock's own danger-flagged items
// had one until now; a misclick used to fire immediately.
export function ConfirmDialog({ title, message, confirmLabel = 'Confirm', onConfirm, onClose }: {
  title: string; message: string; confirmLabel?: string; onConfirm: () => Promise<void> | void; onClose: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  async function handleConfirm() {
    setSubmitting(true);
    try {
      await onConfirm();
      onClose();
    } finally {
      setSubmitting(false);
    }
  }
  return (
    <Modal title={title} onClose={onClose}>
      <div style={{ fontSize: 13.5, color: 'var(--txt-mut)', marginBottom: 18 }}>{message}</div>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={handleConfirm} disabled={submitting}>{submitting ? 'Working…' : confirmLabel}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function Modal({ title, subtitle, onClose, children, wide }: { title: string; subtitle?: string | null; onClose: () => void; children: ReactNode; wide?: boolean }) {
  const Icon = modalIconFor(title);
  return (
    <div style={overlayStyle} className="nf-modal-overlay-in nf-pr-dlg">
      <div style={modalStyle} className={`nf-modal-panel-in nf-pr-card${wide ? ' nf-pr-card--wide' : ''}`}>
        <div className="nf-pr-head">
          <span className="nf-pr-ico"><Icon size={20} /></span>
          <div className="nf-pr-titles">
            <div className="nf-pr-title">{title}</div>
            {subtitle && <div className="nf-pr-sub">{subtitle}</div>}
          </div>
          <button onClick={onClose} className="nf-pr-x" aria-label="Close"><X size={16} /></button>
        </div>
        <div className="nf-pr-body">{children}</div>
      </div>
    </div>
  );
}

// A focused full-screen task (Keka's pattern for big edits — Update Salary Structure, Revise
// Salary): hides everything else, title + close top-left/right, caller-supplied action buttons
// (Cancel/Save) in a sticky header bar instead of a modal footer. Scrollable body below.
//
// Rendered via a portal straight onto <body> (same escape hatch TeamGrowthModal already uses) —
// this component is always mounted several levels deep inside a tab's own page content, and
// `position: fixed` only measures against the true viewport when NO ancestor between it and
// <body> has its own transform/filter/will-change (several of which this app's animated sections
// apply elsewhere — see the .nf-section-enter comment in index.css for the same class of bug).
// A portal makes that ancestry irrelevant instead of having to prove none of it ever will.
export function FullScreenTask({ title, subtitle, onClose, actions, children }: {
  title: string; subtitle?: ReactNode; onClose: () => void; actions: ReactNode; children: ReactNode;
}) {
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);

  return createPortal(
    <div className="nf-pr-fullscreen">
      <div className="nf-pr-fullscreen-head">
        <div>
          <div className="nf-pr-fullscreen-title">{title}</div>
          {subtitle && <div className="nf-pr-fullscreen-subtitle">{subtitle}</div>}
        </div>
        <div className="nf-pr-fullscreen-actions">
          {actions}
          <button type="button" className="nf-pr-fullscreen-close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
      </div>
      <div className="nf-pr-fullscreen-body">{children}</div>
    </div>,
    document.body,
  );
}

// Right-side slide-in panel for editing a single record (Keka's drawer pattern, §1.4) — a grey
// read-only block for fields that can't change after creation, a scrollable form body, and a
// sticky Discard/Update footer. Portalled onto <body> for the same reason FullScreenTask is.
export function Drawer({ title, readOnlyBlock, onClose, children, footer }: {
  title: string; readOnlyBlock?: ReactNode; onClose: () => void; children: ReactNode; footer: ReactNode;
}) {
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);

  return createPortal(
    <div className="nf-pr-drawer-overlay" onClick={onClose}>
      <div className="nf-pr-drawer-panel" role="dialog" aria-label={title} onClick={e => e.stopPropagation()}>
        <div className="nf-pr-drawer-head">
          <div className="nf-pr-drawer-title">{title}</div>
          <button type="button" className="nf-pr-drawer-close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        {readOnlyBlock && <div className="nf-pr-drawer-readonly">{readOnlyBlock}</div>}
        <div className="nf-pr-drawer-body">{children}</div>
        <div className="nf-pr-drawer-footer">{footer}</div>
      </div>
    </div>,
    document.body,
  );
}

// A pill toggle (Keka's switch pattern, §1.4) — --primary when on, light grey when off. A plain
// checkbox styled as a switch would be simpler, but role="switch" semantics communicate "this
// flips a setting" better than a checkbox does for things like "Enabled"/"Active".
export function ToggleSwitch({ checked, onChange, label, disabled }: {
  checked: boolean; onChange: (v: boolean) => void; label?: ReactNode; disabled?: boolean;
}) {
  function toggle() { if (!disabled) onChange(!checked); }
  return (
    <label style={{ display: 'inline-flex', alignItems: 'center', gap: 10, cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? .6 : 1 }}>
      <span
        role="switch" aria-checked={checked} aria-disabled={disabled} tabIndex={disabled ? -1 : 0}
        onClick={toggle}
        onKeyDown={e => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); toggle(); } }}
        style={{
          width: 36, height: 20, borderRadius: 999, flexShrink: 0, position: 'relative', display: 'inline-block',
          background: checked ? 'var(--brand)' : 'var(--line2)', transition: 'background .15s',
        }}
      >
        <span style={{
          position: 'absolute', top: 2, left: checked ? 18 : 2, width: 16, height: 16, borderRadius: '50%',
          background: '#fff', transition: 'left .15s', boxShadow: '0 1px 3px rgba(0,0,0,.3)',
        }} />
      </span>
      {label}
    </label>
  );
}

// Keka's import-wizard stepper (§1.4/§3.4) — a horizontal row of numbered circles, the active
// step filled, earlier ones also filled (done), later ones outline-only.
export function Stepper({ steps, activeIndex }: { steps: string[]; activeIndex: number }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', gap: 36, marginBottom: 28 }}>
      {steps.map((label, i) => {
        const reached = i <= activeIndex;
        return (
          <div key={label} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
            <div style={{
              width: 32, height: 32, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 13, fontWeight: 700,
              background: reached ? 'var(--brand)' : 'var(--panel)',
              color: reached ? '#fff' : 'var(--txt-dim)',
              border: reached ? 'none' : '1px solid var(--line2)',
            }}>
              {i + 1}
            </div>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: i === activeIndex ? 'var(--brand)' : 'var(--txt-dim)' }}>{label}</div>
          </div>
        );
      })}
    </div>
  );
}

export function FormRow({ children }: { children: ReactNode }) {
  return <div style={{ marginBottom: 14 }}>{children}</div>;
}

export function BtnPrimary({ children, onClick, disabled, type = 'button' }: { children: ReactNode; onClick?: () => void; disabled?: boolean; type?: 'button' | 'submit' }) {
  return (
    // display:inline-flex + alignItems:center — Tailwind's preflight (this app imports
    // tailwindcss in index.css) resets every <svg> to display:block; inside a plain inline-block
    // button that forces a hard line break between an icon prefix and the label that follows it
    // (an icon with no following inline siblings is unaffected, which is why this went unnoticed
    // in buttons that are icon-only). Every call site elsewhere that mixes an icon with text
    // already works around this per-button (e.g. PayrollRunsPage's "Generate New Run"); fixing it
    // here covers every BtnPrimary/BtnGhost + icon combination in the payroll module at once.
    <button type={type} onClick={onClick} disabled={disabled} style={{ display: 'inline-flex', alignItems: 'center', background: disabled ? 'var(--raised)' : 'var(--brand)', color: disabled ? 'var(--txt-dim)' : '#fff', border: 'none', borderRadius: 7, padding: '9px 18px', fontSize: 13, fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer' }}>
      {children}
    </button>
  );
}

export function BtnGhost({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{ display: 'inline-flex', alignItems: 'center', background: 'var(--raised)', color: disabled ? 'var(--txt-dim)' : 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 16px', fontSize: 13, cursor: disabled ? 'not-allowed' : 'pointer' }}>
      {children}
    </button>
  );
}

export type FaqItem = { q: string; a: ReactNode };

/** A right-docked "how do I do this" panel — static, hand-written per page. Not the AI
 * assistant (AssistantLauncher) and not the Help Desk knowledge base; a page-scoped quick
 * reference for the exact actions that page offers. Sticky so it stays in view while the page's
 * own content scrolls past it. All items start closed; an employee opens only what they need. */
export function FaqPanel({ items, title = 'FAQ' }: { items: FaqItem[]; title?: string }) {
  const [openIndex, setOpenIndex] = useState<number | null>(null);
  return (
    <div style={{ ...panelStyle, width: 300, flexShrink: 0, position: 'sticky', top: 16, padding: '16px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 18px 14px', borderBottom: '1px solid var(--line)' }}>
        <HelpCircle size={15} style={{ color: 'var(--brand)' }} />
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>{title}</div>
      </div>
      <div>
        {items.map((item, i) => {
          const open = openIndex === i;
          return (
            <div key={i} style={{ borderBottom: i < items.length - 1 ? '1px solid var(--line)' : 'none' }}>
              <button
                type="button"
                onClick={() => setOpenIndex(open ? null : i)}
                style={{
                  width: '100%', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10,
                  background: 'none', border: 'none', textAlign: 'left', cursor: 'pointer',
                  padding: '12px 18px', fontSize: 12.5, fontWeight: 600, color: 'var(--txt)',
                }}
              >
                <span>{item.q}</span>
                <ChevronDown size={14} style={{ color: 'var(--txt-dim)', flexShrink: 0, transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .15s' }} />
              </button>
              {open && (
                <div style={{ padding: '0 18px 14px', fontSize: 12, lineHeight: 1.6, color: 'var(--txt-mut)' }}>
                  {item.a}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** Wraps a page's existing content with the sticky FAQ panel on the right, so every payroll tab
 * can add one without re-deriving its own grid layout — the page's own content takes the
 * remaining space at `flex: 1`, the panel holds a fixed width beside it. */
export function WithFaqPanel({ items, title, children }: { items: FaqItem[]; title?: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 24, alignItems: 'flex-start' }}>
      <div style={{ flex: 1, minWidth: 0 }}>{children}</div>
      <FaqPanel items={items} title={title} />
    </div>
  );
}
