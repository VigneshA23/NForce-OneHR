import { CheckCircle2, Mail, Hash, Building2, BadgeCheck, ShieldCheck, CalendarDays, Wallet, Rocket, KeyRound, Eye, EyeOff, Copy, Check, AlertTriangle, Loader2 } from 'lucide-react';
import { EmployeeAvatar } from '../../components/EmployeeAvatar';
import { ROLES, fmtEffectiveDate } from './orgOptionsHelpers';
import type { EmployeeRecord } from '../../api/employees';
import './createEmployee.css';

// Fixed-length mask, independent of the real password's length — a cheap extra guard against
// leaking length info to anyone glancing at the screen while it's hidden.
const MASKED_PASSWORD = '••••••••••';

type OnboardingOutcome = 'started' | 'skipped' | 'failed' | null;

function Fact({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="nf-cr-fact">
      <span className="nf-cr-fact-ico">{icon}</span>
      <div>
        <small>{label}</small>
        <b>{value}</b>
      </div>
    </div>
  );
}

/**
 * The screen shown after an employee is created: overview, one-time credentials, compensation and
 * onboarding status. Presentation only — all state and actions live in CreateEmployeeWizard, which
 * passes them in (the preview page passes sample values).
 */
export function CreateEmployeeResult({
  created, passwordVisible, onTogglePassword, passwordCopied, onCopyPassword,
  onboardingOutcome, onboardingError, retryingOnboarding, onRetryOnboarding, onDone, minHeight,
}: {
  created: EmployeeRecord;
  passwordVisible: boolean;
  onTogglePassword: () => void;
  passwordCopied: boolean;
  onCopyPassword: () => void;
  onboardingOutcome: OnboardingOutcome;
  onboardingError: string | null;
  retryingOnboarding: boolean;
  onRetryOnboarding: () => void;
  onDone: () => void;
  minHeight?: string;
}) {
  const roleLabel = ROLES.find(r => r.value === created.role)?.label ?? created.role;
  return (
    <div className="nf-um-page" style={{ minHeight }}>
      <div className="nf-ce-okhead">
        <span className="nf-ce-okico"><CheckCircle2 size={22} /></span>
        <div style={{ minWidth: 0 }}>
          <div className="nf-ce-title">Employee Created Successfully</div>
          <div className="nf-ce-sub">{created.fullName}'s account is ready.</div>
        </div>
      </div>

      <div className="nf-cr-grid nf-grid-2col-collapse">
        {/* Employee overview */}
        <section className="nf-ce-sec nf-cr-main">
          <div className="nf-cr-who">
            <EmployeeAvatar userId={created.userId} name={created.fullName} size={56} fontSize={18} background="var(--brand)" />
            <div style={{ minWidth: 0 }}>
              <h2>{created.fullName}</h2>
              <div className="nf-cr-chips">
                <span className="nf-cr-chip is-brand">{roleLabel}</span>
                {created.designationName && <span className="nf-cr-chip">{created.designationName}</span>}
                {created.departmentName && <span className="nf-cr-chip">{created.departmentName}</span>}
              </div>
            </div>
          </div>
          <div className="nf-cr-facts">
            <Fact icon={<Hash size={15} />} label="Employee Code" value={created.employeeCode} />
            <Fact icon={<Mail size={15} />} label="Company Email" value={created.email} />
            {created.departmentName && <Fact icon={<Building2 size={15} />} label="Department" value={created.departmentName} />}
            {created.designationName && <Fact icon={<BadgeCheck size={15} />} label="Designation" value={created.designationName} />}
            <Fact icon={<ShieldCheck size={15} />} label="Role" value={roleLabel} />
            <Fact icon={<CalendarDays size={15} />} label="Joining Date" value={fmtEffectiveDate(created.joiningDate)} />
          </div>
        </section>

        {/* Credentials, compensation and onboarding status */}
        <div className="nf-cr-side">
          {created.tempPassword && (
            <div className="nf-ce-cred">
              <div className="nf-cr-cred-head"><KeyRound size={15} /> Account Credentials</div>
              <div className="nf-cr-label">Login Email</div>
              <div className="nf-cr-value">{created.email}</div>
              <div className="nf-cr-label">Temporary Password</div>
              <div className="nf-cr-pwd">
                <code style={{ userSelect: passwordVisible ? 'all' : 'none' }}>{passwordVisible ? created.tempPassword : MASKED_PASSWORD}</code>
                <button type="button" onClick={onTogglePassword} className="nf-cr-mini">
                  {passwordVisible ? <><EyeOff size={13} /> Hide</> : <><Eye size={13} /> Reveal</>}
                </button>
                <button type="button" onClick={onCopyPassword} className="nf-cr-mini is-danger">
                  {passwordCopied ? <><Check size={13} /> Copied</> : <><Copy size={13} /> Copy</>}
                </button>
              </div>
              <div className="nf-cr-warn">Share this once, through a secure channel. It won't be shown again after you leave this screen.</div>
            </div>
          )}

          <div className="nf-cr-note">
            <span className="nf-cr-note-ico"><Wallet size={15} /></span>
            <div><b>Compensation</b><span>Not configured yet, can be set up later.</span></div>
          </div>

          {onboardingOutcome === null && (
            <div className="nf-cr-note"><span className="nf-cr-note-ico"><Loader2 size={15} /></span><div><b>Onboarding</b><span>Starting onboarding checklist…</span></div></div>
          )}
          {onboardingOutcome === 'started' && (
            <div className="nf-cr-note is-info">
              <span className="nf-cr-note-ico"><Rocket size={15} /></span>
              <div><b>Onboarding started</b><span>Find it under Onboarding, then Onboarding Started.</span></div>
            </div>
          )}
          {onboardingOutcome === 'failed' && (
            <div className="nf-cr-note is-warn">
              <span className="nf-cr-note-ico"><AlertTriangle size={15} /></span>
              <div>
                <b>Onboarding didn't start{onboardingError ? ` (${onboardingError})` : ''}</b>
                <span>
                  <button type="button" onClick={onRetryOnboarding} disabled={retryingOnboarding} className="nf-cr-retry">
                    {retryingOnboarding ? 'Retrying…' : 'Retry now'}
                  </button>
                  {' '}or start it manually from the Onboarding page later.
                </span>
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="nf-cr-done">
        <button type="button" onClick={onDone} className="nf-ce-next">Done</button>
      </div>
    </div>
  );
}
