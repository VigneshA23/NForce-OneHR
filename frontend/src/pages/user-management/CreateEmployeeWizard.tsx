import { useEffect, useState } from 'react';
import { CheckCircle2, UserPlus, ArrowLeft, ArrowRight, Check as CheckIcon } from 'lucide-react';
import './createEmployee.css';
import { usersApi, employeesApi, type EmployeeRecord, type CreateUserPayload } from '../../api/employees';
import { onboardingApi } from '../../api/onboarding';
import { useToast } from '../../context/ToastContext';
import { businessTodayIsoDate } from '../../utils/businessDate';
import { ROLES, fmtEffectiveDate, type OrgOptions } from './orgOptionsHelpers';
import { WizardSteps } from './WizardSteps';
import { BasicInfoStep } from './steps/BasicInfoStep';
import { EmploymentStep } from './steps/EmploymentStep';
import { WorkPolicyStep } from './steps/WorkPolicyStep';
import { CompensationFollowUp } from './CompensationFollowUp';
import { validateStep1, validateStep2, validateStep3, buildCreateUserPayload } from './wizardLogic';

// Full-page view (replaces the earlier AddModal-style dialog) — rendered inline by
// UserManagementPage.tsx in place of its normal table content, still inside the Shell's existing
// sidebar/topbar chrome. No createPortal, no backdrop, no centered card: this is plain page
// content. Shell's own topbar is `position: sticky; top: 0`, 56px tall (components/Shell.tsx) —
// this page's own sticky header/footer offset against that height, not the bare viewport edge, so
// they sit just below/above it instead of rendering underneath/competing with it.
const SHELL_TOPBAR_HEIGHT = 56;
// Approximate, not pixel-exact (Shell's <main> padding is itself responsive — 26/18/14px at
// different breakpoints) — just enough so a short step still reads as a full page with its
// footer pinned near the bottom, rather than a short stub with a visible gap of <main>'s
// background below it. A few tens of pixels of slack here is harmless.
const pageMinHeight = `calc(100dvh - ${SHELL_TOPBAR_HEIGHT}px)`;

// Fixed-length mask, independent of the real password's length — a cheap extra guard against
// leaking length info to anyone glancing at the screen while it's hidden.
const MASKED_PASSWORD = '••••••••••';

function OverviewField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 13.5, color: 'var(--txt)' }}>{value}</div>
    </div>
  );
}

export function CreateEmployeeWizard({ onClose, onCreated, token, opts, setOpts }: {
  onClose: () => void; onCreated: (e: EmployeeRecord) => void; token: string;
  opts: OrgOptions; setOpts: React.Dispatch<React.SetStateAction<OrgOptions>>;
}) {
  const { showToast } = useToast();
  const [step, setStep] = useState(0);
  // Same initial values AddModal used — Role/Employment Type/Work Mode/Business
  // Unit/Department/Designation start unselected (ONEHR — "Add User Dropdowns Display Default
  // Values Instead of Selection Placeholders"), Joining Date defaults to today, effectiveFrom
  // defaults to today (never auto-advanced).
  const [form, setForm] = useState<CreateUserPayload>({
    fullName: '', email: '', role: '', joiningDate: new Date().toISOString().slice(0, 10),
    effectiveFrom: businessTodayIsoDate(),
  });
  const [nameTouched, setNameTouched] = useState(false);
  const [startOnboarding, setStartOnboarding] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Set instead of `error` specifically when the backend rejects the submitted Employee ID as
  // already taken — same contract AddModal's handleSubmit already had.
  const [employeeCodeConflict, setEmployeeCodeConflict] = useState(false);
  const [fetchingNewId, setFetchingNewId] = useState(false);
  const [created, setCreated] = useState<EmployeeRecord | null>(null);
  const [onboardingOutcome, setOnboardingOutcome] = useState<'started' | 'skipped' | 'failed' | null>(null);
  const [onboardingError, setOnboardingError] = useState<string | null>(null);
  const [retryingOnboarding, setRetryingOnboarding] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(true);
  const [passwordCopied, setPasswordCopied] = useState(false);
  const [passwordVisible, setPasswordVisible] = useState(false);
  // Employee creation finishes at the end of Step 3; `created` being set just means the account
  // already exists — it does NOT by itself show the success screen. The admin lands on Step 4
  // (Compensation) first; only clicking "Skip for Now" there sets this and reveals the success
  // screen. No second API call happens between the two — this is a pure local view switch.
  const [showSuccessScreen, setShowSuccessScreen] = useState(false);

  useEffect(() => {
    employeesApi.previewNextCode(token)
      .then(r => setForm(f => ({ ...f, employeeCode: f.employeeCode ?? r.employeeCode })))
      .catch(() => { /* leave the field blank — the backend auto-assigns one if none is submitted */ })
      .finally(() => setPreviewLoading(false));
  }, [token]);

  async function startOnboardingFor(employeeUserId: string, name: string) {
    setRetryingOnboarding(true);
    setOnboardingOutcome(null);
    try {
      await onboardingApi.start({ employeeUserId }, token);
      setOnboardingOutcome('started');
      setOnboardingError(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Could not start onboarding';
      setOnboardingOutcome('failed');
      setOnboardingError(msg);
      showToast('error', `Onboarding didn't start for ${name}: ${msg}`);
    } finally {
      setRetryingOnboarding(false);
    }
  }

  async function handleGetNewId() {
    setFetchingNewId(true);
    try {
      const r = await employeesApi.previewNextCode(token);
      setForm(f => ({ ...f, employeeCode: r.employeeCode }));
      setEmployeeCodeConflict(false);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Could not fetch a new Employee ID';
      showToast('error', msg);
    } finally {
      setFetchingNewId(false);
    }
  }

  // Only ever called from Step 1 ("Basic Information") or Step 2 ("Employment & Job Details") —
  // Step 3 ("Work & Policy Configuration") submits directly via handleSubmit below instead of
  // advancing via validation (Step 4 is reached only after a successful creation — see handleSubmit).
  function handleContinue() {
    const stepError = step === 0 ? validateStep1(form) : validateStep2(form);
    if (stepError) { setError(stepError); return; }
    setError(null);
    setStep(s => Math.min(2, s + 1));
  }

  function handleBack() {
    setError(null);
    setStep(s => Math.max(0, s - 1));
  }

  // Triggered directly by Step 3's "Save & Continue" button — employee creation completes
  // immediately at the end of Step 3. `submitting` guards against a double-click firing a second
  // POST /api/users call while the first is in flight; once `created` is set, `step` advances to
  // 3 (Compensation) and there is no Back/Continue/Save path left that could call this again — the
  // API is called exactly once per creation, regardless of what the admin does on Step 4 afterward.
  async function handleSubmit() {
    // Defense-in-depth re-validation of every step before the actual API call — mirrors
    // AddModal's own single-shot handleSubmit validation, just run across all three input steps.
    const today = businessTodayIsoDate();
    const err = validateStep1(form) ?? validateStep2(form) ?? validateStep3(form, today);
    if (err) { setError(err); return; }
    setSubmitting(true); setError(null); setEmployeeCodeConflict(false);
    const payload = buildCreateUserPayload(form);
    try {
      const emp = await usersApi.create(payload, token);
      onCreated(emp);
      showToast('success', `${emp.fullName} created successfully`);
      setCreated(emp);
      setStep(3);
      if (startOnboarding) {
        startOnboardingFor(emp.userId, emp.fullName);
      } else {
        setOnboardingOutcome('skipped');
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Create failed';
      if (msg === 'Employee ID is unavailable. Please go back and retry.') {
        setEmployeeCodeConflict(true);
      } else {
        setError(msg);
      }
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  // Step 4's only action — a pure local view switch to the success screen below, no API call.
  function handleSkipCompensation() {
    setShowSuccessScreen(true);
  }

  if (created && showSuccessScreen) {
    const roleLabel = ROLES.find(r => r.value === created.role)?.label ?? created.role;
    return (
      <div className="nf-um-page" style={{ minHeight: pageMinHeight }}>
        {/* Success header — compact: small indicator, heading, one short line. */}
        <div className="nf-ce-okhead">
          <span className="nf-ce-okico">
            <CheckCircle2 size={22} />
          </span>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 18, fontWeight: 700, color: 'var(--txt)' }}>Employee Created Successfully</div>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 1 }}>{created.fullName}'s account is ready.</div>
          </div>
        </div>

        <div className="nf-grid-2col-collapse" style={{ padding: 24, display: 'grid', gridTemplateColumns: '1.6fr 1fr', gap: 24, alignItems: 'start', maxWidth: 1080 }}>
          {/* Main section — employee overview. */}
          <div>
            <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--txt)', marginBottom: 16 }}>{created.fullName}</div>
            <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <OverviewField label="Employee Code" value={created.employeeCode} />
              <OverviewField label="Company Email" value={created.email} />
              {created.departmentName && <OverviewField label="Department" value={created.departmentName} />}
              {created.designationName && <OverviewField label="Designation" value={created.designationName} />}
              <OverviewField label="Role" value={roleLabel} />
              <OverviewField label="Joining Date" value={fmtEffectiveDate(created.joiningDate)} />
            </div>
          </div>

          {/* Secondary section — credentials, compensation status, onboarding status. */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {created.tempPassword && (
              <div className="nf-ce-cred">
                <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.08em', marginBottom: 10 }}>Account Credentials</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Login Email</div>
                <div style={{ fontSize: 13, color: 'var(--txt)', marginBottom: 10, wordBreak: 'break-all' }}>{created.email}</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 3 }}>Temporary Password</div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                  <code style={{ fontSize: 14, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', userSelect: passwordVisible ? 'all' : 'none', flex: '1 1 auto', minWidth: 0 }}>
                    {passwordVisible ? created.tempPassword : MASKED_PASSWORD}
                  </code>
                  <button
                    type="button"
                    onClick={() => setPasswordVisible(v => !v)}
                    style={{ background: 'none', border: '1px solid var(--line2)', color: 'var(--txt-mut)', borderRadius: 6, padding: '4px 10px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer', flexShrink: 0 }}
                  >
                    {passwordVisible ? 'Hide' : 'Reveal'}
                  </button>
                  <button
                    type="button"
                    onClick={async () => {
                      try {
                        await navigator.clipboard.writeText(created.tempPassword!);
                        setPasswordCopied(true);
                        setTimeout(() => setPasswordCopied(false), 2000);
                      } catch {
                        showToast('error', 'Could not copy to clipboard');
                      }
                    }}
                    style={{ background: 'none', border: '1px solid var(--risk)', color: 'var(--risk)', borderRadius: 6, padding: '4px 10px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer', flexShrink: 0 }}
                  >
                    {passwordCopied ? 'Copied' : 'Copy'}
                  </button>
                </div>
                <div style={{ fontSize: 10.5, color: 'var(--risk)', marginTop: 8, lineHeight: 1.5 }}>
                  Share this once, through a secure channel. It won't be shown again after you leave this screen.
                </div>
              </div>
            )}

            {/* Compensation status — a single muted line, not a bordered card, so it doesn't
                visually compete with the employee overview next to it. */}
            <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
              <b style={{ color: 'var(--txt)' }}>Compensation:</b> not configured yet, can be set up later.
            </div>

            {onboardingOutcome === null && (
              <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 12, fontSize: 12.5, color: 'var(--txt-mut)' }}>
                Starting onboarding checklist…
              </div>
            )}
            {onboardingOutcome === 'started' && (
              <div style={{ background: 'rgba(76,141,214,.1)', border: '1px solid rgba(76,141,214,.25)', borderRadius: 8, padding: 12, fontSize: 12.5, color: '#4C8DD6' }}>
                Onboarding checklist started. Find it under Onboarding, then Onboarding Started.
              </div>
            )}
            {onboardingOutcome === 'failed' && (
              <div style={{ background: 'rgba(224,169,59,.1)', border: '1px solid rgba(224,169,59,.25)', borderRadius: 8, padding: 12, fontSize: 12.5, color: '#E0A93B' }}>
                <div>Onboarding didn't start{onboardingError ? ` (${onboardingError})` : ''}.</div>
                <button
                  onClick={() => startOnboardingFor(created.userId, created.fullName)}
                  disabled={retryingOnboarding}
                  style={{ marginTop: 8, background: 'none', border: '1px solid #E0A93B', color: '#E0A93B', borderRadius: 6, padding: '5px 12px', fontSize: 12, fontWeight: 600, cursor: retryingOnboarding ? 'default' : 'pointer' }}
                >
                  {retryingOnboarding ? 'Retrying…' : 'Retry now'}
                </button>
                <span style={{ marginLeft: 8 }}>or start it manually from the Onboarding page later.</span>
              </div>
            )}
          </div>
        </div>

        <div style={{ padding: '0 24px 24px', maxWidth: 1080 }}>
          <button onClick={onClose} className="nf-ce-next" style={{ width: '100%', maxWidth: 320, justifyContent: 'center' }}>Done</button>
        </div>
      </div>
    );
  }

  return (
    <div className="nf-um-page" style={{ minHeight: pageMinHeight, display: 'flex', flexDirection: 'column' }}>
      <div className="nf-ce-top" style={{ top: SHELL_TOPBAR_HEIGHT }}>
        <div className="nf-ce-head">
          <span className="nf-ce-head-ico"><UserPlus size={20} /></span>
          <div className="nf-ce-head-txt">
            <div className="nf-ce-title">Create Employee</div>
            <div className="nf-ce-sub">Add a new employee in four steps.</div>
          </div>
          <button type="button" onClick={onClose} className="nf-ce-cancel">Cancel</button>
        </div>
        <WizardSteps current={step} />
      </div>
      <div className="nf-ce-body">
        {error && <div role="alert" className="nf-ce-error">{error}</div>}
        {step === 0 && <BasicInfoStep form={form} setForm={setForm} nameTouched={nameTouched} setNameTouched={setNameTouched} />}
        {step === 1 && (
          <EmploymentStep
            form={form} setForm={setForm} opts={opts} token={token}
            employeeCodeConflict={employeeCodeConflict} fetchingNewId={fetchingNewId}
            onGetNewId={handleGetNewId} previewLoading={previewLoading}
          />
        )}
        {step === 2 && (
          <WorkPolicyStep
            form={form} setForm={setForm} opts={opts} setOpts={setOpts} token={token}
            startOnboarding={startOnboarding} setStartOnboarding={setStartOnboarding}
          />
        )}
        {/* Only reachable after a successful creation (handleSubmit sets step to 3) — never via
            handleContinue, so there's no way to land here without an employee already existing. */}
        {step === 3 && created && (
          <div className="nf-ce-sec" style={{ padding: 20 }}>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', marginBottom: 12 }}>
              {created.fullName} has been created successfully.
            </div>
            <CompensationFollowUp />
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 10 }}>
              Compensation is optional and can be configured later, it won't affect this employee's account, onboarding, or access.
            </div>
          </div>
        )}
      </div>
      <div className="nf-um-foot nf-ce-foot" style={{ justifyContent: step === 3 ? 'flex-end' : 'space-between' }}>
        {step !== 3 && (
          <button type="button" onClick={handleBack} disabled={step === 0} className="nf-ce-back">
            <ArrowLeft size={15} /> Back
          </button>
        )}
        {step === 3 ? (
          <button type="button" onClick={handleSkipCompensation} className="nf-ce-next">Skip for Now</button>
        ) : step < 2 ? (
          <button type="button" onClick={handleContinue} className="nf-ce-next">Continue <ArrowRight size={15} /></button>
        ) : (
          <button type="button" onClick={handleSubmit} disabled={submitting} className="nf-ce-next">
            {submitting ? 'Creating…' : <>Save &amp; Continue <CheckIcon size={15} /></>}
          </button>
        )}
      </div>
    </div>
  );
}
