import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { X, CheckCircle2, XCircle, type LucideIcon } from 'lucide-react';
import { EmployeeAvatar } from '../../components/EmployeeAvatar';
import { EmpCode } from '../../components/EmpCode';
import '../attendanceRequests.css';

/*
 * Shared shell for the request detail drawers (Overtime, Work From Home, Partial Day). Presentation
 * only: each drawer keeps its own data, actions and rules and just lays them out with these parts.
 */

/** Drawer frame: header band (icon tile, title, requested date, status), employee card, body, footer. */
export function RequestDrawer({ title, icon: Icon, userId, employeeName, requestedOn, statusNode, onClose, footer, children }: {
  title: string;
  icon: LucideIcon;
  userId: string;
  employeeName: string;
  requestedOn: string;
  statusNode?: React.ReactNode;
  onClose: () => void;
  footer?: React.ReactNode;
  children: React.ReactNode;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Portaled to the body so a transformed ancestor can never trap the fixed panel.
  return createPortal(
    <div className="nf-qd-overlay" onClick={onClose}>
      <aside role="dialog" aria-modal="true" aria-label={title} className="nf-qd" onClick={e => e.stopPropagation()}>
        <div className="nf-qd-head">
          <span className="nf-qd-ico"><Icon size={19} /></span>
          <div className="nf-qd-titles">
            <div className="nf-qd-title">{title}</div>
            <div className="nf-qd-sub">Requested on {requestedOn}</div>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="nf-qd-x"><X size={16} /></button>
        </div>

        <div className="nf-qd-body">
          <div className="nf-qd-who">
            <EmployeeAvatar userId={userId} name={employeeName} size={44} fontSize={14} background="var(--brand)" />
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="nf-qd-name">{employeeName}</div>
              <EmpCode userId={userId} />
            </div>
            {statusNode}
          </div>
          {children}
        </div>

        {footer && <div className="nf-qd-foot">{footer}</div>}
      </aside>
    </div>,
    document.body,
  );
}

export function RdSection({ title, icon: Icon, children }: { title: string; icon?: LucideIcon; children: React.ReactNode }) {
  return (
    <section className="nf-qd-sec">
      <div className="nf-qd-sec-title">{Icon && <Icon size={13} />}{title}</div>
      {children}
    </section>
  );
}

export function RdFacts({ items }: { items: { label: string; value: React.ReactNode }[] }) {
  return (
    <div className="nf-qd-facts">
      {items.map(i => (
        <div key={i.label} className="nf-qd-fact">
          <small>{i.label}</small>
          <b>{i.value}</b>
        </div>
      ))}
    </div>
  );
}

/** One line per day (date on the left, detail on the right). */
export function RdRows({ rows }: { rows: { left: React.ReactNode; right?: React.ReactNode }[] }) {
  return (
    <div className="nf-qd-rows">
      {rows.map((r, i) => (
        <div key={i} className="nf-qd-row">
          <span>{r.left}</span>
          {r.right !== undefined && <b>{r.right}</b>}
        </div>
      ))}
    </div>
  );
}

export function RdQuote({ children }: { children: React.ReactNode }) {
  return <div className="nf-qd-quote">{children}</div>;
}

/** The approve / reject outcome, once someone has acted. */
export function RdDecision({ rejected, by, at, comment }: { rejected: boolean; by: string; at?: string; comment?: string | null }) {
  return (
    <div className={`nf-qd-decision ${rejected ? 'is-bad' : 'is-ok'}`}>
      <span className="nf-qd-decision-ico">{rejected ? <XCircle size={17} /> : <CheckCircle2 size={17} />}</span>
      <div>
        <b>{rejected ? 'Rejected' : 'Approved'} by {by}</b>
        {at && <small>{at}</small>}
        {comment && <p>{comment}</p>}
      </div>
    </div>
  );
}

/** Comment box plus Cancel and confirm buttons, shown while an approve or reject is being made. */
export function RdActionForm({ mode, comment, setComment, submitting, onClose, onConfirmAction }: {
  mode: 'APPROVE' | 'REJECT';
  comment: string;
  setComment: (v: string) => void;
  submitting: boolean;
  onClose: () => void;
  onConfirmAction: () => void;
}) {
  return (
    <div className="nf-qd-form">
      <label>{mode === 'APPROVE' ? 'Comment (optional)' : 'Reason for rejection *'}</label>
      <textarea value={comment} onChange={e => setComment(e.target.value)} rows={3} placeholder={mode === 'APPROVE' ? 'Add a note for the employee' : 'Tell the employee why'} />
      <div className="nf-qd-form-btns">
        <button type="button" onClick={onClose} className="nf-qd-ghost">Cancel</button>
        <button type="button" onClick={onConfirmAction} disabled={submitting} className={mode === 'APPROVE' ? 'nf-qd-ok' : 'nf-qd-danger'}>
          {submitting ? 'Submitting…' : mode === 'APPROVE' ? 'Confirm Approval' : 'Reject Request'}
        </button>
      </div>
    </div>
  );
}
