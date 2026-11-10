import { useEffect, useState } from 'react';
import { UserPlus, ArrowLeft, ArrowRight, Check as CheckIcon } from 'lucide-react';
import './createEmployee.css';
import { usersApi, employeesApi, type EmployeeRecord, type CreateUserPayload } from '../../api/employees';
import { onboardingApi } from '../../api/onboarding';
import { useToast } from '../../context/ToastContext';
import { businessTodayIsoDate } from '../../utils/businessDate';
import type { OrgOptions } from './orgOptionsHelpers';
import { WizardSteps } from './WizardSteps';
import { CreateEmployeeResult } from './CreateEmployeeResult';
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
    return (
      <CreateEmployeeResult
        created={created}
        minHeight={pageMinHeight}
        passwordVisible={passwordVisible}
        onTogglePassword={() => setPasswordVisible(v => !v)}
        passwordCopied={passwordCopied}
        onCopyPassword={async () => {
          try {
            await navigator.clipboard.writeText(created.tempPassword!);
            setPasswordCopied(true);
            setTimeout(() => setPasswordCopied(false), 2000);
          } catch {
            showToast('error', 'Could not copy to clipboard');
          }
        }}
        onboardingOutcome={onboardingOutcome}
        onboardingError={onboardingError}
        retryingOnboarding={retryingOnboarding}
        onRetryOnboarding={() => startOnboardingFor(created.userId, created.fullName)}
        onDone={onClose}
      />
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
