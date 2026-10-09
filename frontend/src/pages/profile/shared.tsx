import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Sparkles, Check, Pencil, RotateCcw, Briefcase } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { ProfileData } from '../../api/profile';

export type ProfileDayStatus = 'holiday' | 'weekly-off' | null;

export const WORK_MODES = ['ONSITE', 'HYBRID', 'REMOTE'] as const;
export const GENDERS = ['Male', 'Female', 'Non-binary', 'Prefer not to say'];
export const MARITAL_STATUSES = ['Single', 'Married', 'Divorced', 'Widowed', 'Prefer not to say'];

// Rejects: multiple/misplaced '@', missing local or domain part, spaces, consecutive dots in
// the domain, and trailing junk after the TLD. A syntactically well-formed but non-existent
// TLD (e.g. "gmail.comabc") is otherwise indistinguishable from a real one by format alone —
// see the explicit ".com" check in validateEmail below for the one case called out by name.
const EMAIL_RE = /^[a-zA-Z0-9](?:[a-zA-Z0-9._%+-]*[a-zA-Z0-9])?@(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$/;
// Once the TLD starts with "com", nothing may follow it (blocks "gmail.comabc" while still
// allowing "gmail.com" itself) — a deliberate special case for the specific reported input,
// not a general TLD whitelist.
const TLD_COM_WITH_TRAILING_CHARS_RE = /^com.+/i;
// Letters only, with a single space/hyphen/apostrophe/period allowed between word groups
// (e.g. "Mary Jane", "O'Brien", "Anne-Marie") — no digits, no leading/trailing/consecutive
// separators, no other symbols.
const NAME_RE = /^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$/;
export const digitsOnly = (v: string) => v.replace(/\D/g, '');
export const nameCharsOnly = (v: string) => v.replace(/[^A-Za-z '.-]/g, '');
// Strips emoji/pictographs (plus the variation-selector and zero-width-joiner marks used to
// combine them, e.g. skin-tone modifiers, flag sequences) from free-text fields like Address —
// unlike Name, Address needs to stay open to digits/punctuation/most Unicode text, so this only
// removes emoji specifically rather than restricting to an allow-list.
const EMOJI_RE = /\p{Extended_Pictographic}|\p{Emoji_Presentation}|[\u{1F1E6}-\u{1F1FF}]|[‍️]/gu;
export const stripEmoji = (v: string) => v.replace(EMOJI_RE, '');

export function validateEmail(v: string): string | null {
  if (!v.trim()) return null;
  if (!EMAIL_RE.test(v)) return 'Enter a valid email address (e.g. name@example.com).';
  const tld = v.slice(v.lastIndexOf('.') + 1);
  if (TLD_COM_WITH_TRAILING_CHARS_RE.test(tld)) return 'Enter a valid email address (e.g. name@example.com).';
  return null;
}

export function validatePhone(v: string, label: string): string | null {
  // digitsOnly(v), not v itself — a value that's only whitespace/punctuation with no actual
  // digits (e.g. a stray space left in an otherwise-untouched optional field) has nothing to
  // validate and should count as "not provided", not fail the 10-digit check below.
  if (!digitsOnly(v)) return null;
  return digitsOnly(v).length === 10 ? null : `${label} must be exactly 10 digits.`;
}

export function validateName(v: string, label: string): string | null {
  // v.trim(), not v itself — these are optional fields (e.g. Emergency Contact Name), and a
  // string that's only whitespace is truthy in JS, so a stray space left in an otherwise-empty
  // field would otherwise reach NAME_RE.test below and fail (no letters to match), incorrectly
  // blocking the whole form's save over a field the person never actually meant to fill in.
  if (!v.trim()) return null;
  return NAME_RE.test(v) ? null : `${label} can only contain letters, spaces, hyphens, apostrophes, and periods.`;
}

// Unlike validateName above, blank is NOT accepted here — First/Last Name are the one case where
// an empty value genuinely is invalid (a profile can't exist without a name), not just "not
// provided yet".
export function validateRequiredName(v: string, label: string): string | null {
  if (!v.trim()) return `${label} is required.`;
  return NAME_RE.test(v) ? null : `${label} can only contain letters, spaces, hyphens, apostrophes, and periods.`;
}

// Display Name is derived, not a separately stored/edited field — First/Middle/Last Name
// (edited via Primary Details) are the single source of truth. Falls back to fullName for the
// rare account with no employee record / no name parts set yet.
export function computeDisplayName(profile: Pick<ProfileData, 'firstName' | 'middleName' | 'lastName' | 'fullName'>): string {
  return [profile.firstName, profile.middleName, profile.lastName].filter(Boolean).join(' ').trim() || profile.fullName;
}

// Where clicking a missing item in the Profile Completion/Health dropdowns should take the
// employee — 'about'/'profile' switch ProfilePage's tab, 'photo' opens the photo modal directly
// (there's no tab for it; it lives on the header itself).
export type ProfileFieldTarget = 'about' | 'profile' | 'photo';

export interface ProfileFieldStatus {
  label: string;
  target: ProfileFieldTarget;
}

interface CompletionFieldMeta { key: keyof ProfileData; label: string; target: ProfileFieldTarget; }

// Fields the employee can actually fill in themselves, that are genuinely useful to have on
// file — deliberately excludes passportNumber/nationalId/bank details (sensitive, often not
// applicable to every employee, and already called out elsewhere as "no format validators
// beyond required-ness... consistent with plain columns, UI masking only") and middleName
// (frequently and legitimately blank, not a sign of an incomplete profile).
const PROFILE_COMPLETION_FIELDS: CompletionFieldMeta[] = [
  { key: 'photoDataUrl', label: 'Profile Photo', target: 'photo' },
  { key: 'phone', label: 'Mobile Number', target: 'profile' },
  { key: 'dateOfBirth', label: 'Date of Birth', target: 'profile' },
  { key: 'gender', label: 'Gender', target: 'profile' },
  { key: 'maritalStatus', label: 'Marital Status', target: 'profile' },
  { key: 'personalEmail', label: 'Personal Email', target: 'profile' },
  { key: 'address', label: 'Current Address', target: 'profile' },
  { key: 'permanentAddress', label: 'Permanent Address', target: 'profile' },
  { key: 'emergencyContactName', label: 'Emergency Contact Name', target: 'profile' },
  { key: 'emergencyContactPhone', label: 'Emergency Contact Phone', target: 'profile' },
  { key: 'emergencyContactRelationship', label: 'Emergency Contact Relationship', target: 'profile' },
  { key: 'bio', label: 'About Me / Bio', target: 'about' },
];

function isBlank(v: unknown): boolean {
  return typeof v === 'string' ? !v.trim() : !v;
}

export interface ProfileCompletionDetail {
  percent: number;
  missing: ProfileFieldStatus[];
}

// hasEducation isn't part of ProfileData — it's a separate list (EducationEntry[], fetched via
// profileEducationApi), not a single field, so it's passed in rather than read off profile
// directly. Counts as "complete" once at least one entry exists, same all-or-nothing shape as
// every other item here.
export function computeProfileCompletion(profile: ProfileData, hasEducation: boolean): ProfileCompletionDetail {
  const missing: ProfileFieldStatus[] = PROFILE_COMPLETION_FIELDS
    .filter(f => isBlank(profile[f.key]))
    .map(({ label, target }) => ({ label, target }));
  if (!hasEducation) missing.push({ label: 'Education History', target: 'profile' });

  const totalFields = PROFILE_COMPLETION_FIELDS.length + 1;
  const percent = Math.round(((totalFields - missing.length) / totalFields) * 100);
  return { percent, missing };
}

// Shared "click to see a dropdown of what's missing" shell for both cards below — closes on
// picking an item, or clicking anywhere outside it (a borderless full-viewport backdrop behind
// the dropdown, same trick used for the app's other popovers).
//
// Rendered through a portal to document.body, positioned via `anchorRect` (the trigger button's
// own getBoundingClientRect(), captured by the caller right as it opens) rather than a plain
// `position: absolute` next to the button — the profile hero card this sits inside has its own
// `overflow: hidden` (to round the cover banner's corners), which would otherwise silently clip
// off whichever items don't fit before that card's bottom edge.
function MissingItemsDropdown({ items, anchorRect, onJumpTo, onCloseRequest }: {
  items: ProfileFieldStatus[];
  anchorRect: DOMRect;
  onJumpTo: (target: ProfileFieldTarget) => void;
  onCloseRequest: () => void;
}) {
  return createPortal(
    <>
      <div style={{ position: 'fixed', inset: 0, zIndex: 1000 }} onClick={onCloseRequest} />
      <div
        role="menu"
        style={{
          position: 'fixed', top: anchorRect.bottom + 6, left: anchorRect.left, zIndex: 1001,
          background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8,
          boxShadow: '0 8px 24px rgba(0,0,0,.3)', minWidth: 230, maxHeight: '60vh', overflowY: 'auto',
          padding: 6,
        }}
      >
        <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em', padding: '4px 8px 6px' }}>
          Still missing
        </div>
        {items.map(item => (
          <button
            key={item.label}
            type="button"
            onClick={() => { onJumpTo(item.target); onCloseRequest(); }}
            style={{
              display: 'block', width: '100%', textAlign: 'left', padding: '7px 8px',
              background: 'none', border: 'none', borderRadius: 6, fontSize: 12.5,
              color: 'var(--txt)', cursor: 'pointer',
            }}
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'none')}
          >
            {item.label}
          </button>
        ))}
      </div>
    </>,
    document.body
  );
}

/** Sits beside the identity block in the profile header, on the solid panel background below
 * the (user-uploadable, unpredictable-contrast) cover image — never overlaid on the image
 * itself, unlike the reference mockup's clean gradient banner, since a real photo cover could
 * make overlaid text illegible. Clickable: shows exactly which fields are still missing, and
 * clicking one jumps straight to where it's edited. */
export function ProfileCompletionCard({ detail, onJumpTo }: { detail: ProfileCompletionDetail; onJumpTo: (target: ProfileFieldTarget) => void }) {
  const [open, setOpen] = useState(false);
  const [anchorRect, setAnchorRect] = useState<DOMRect | null>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const hasMissing = detail.missing.length > 0;

  function toggleOpen() {
    if (!hasMissing) return;
    if (!open && btnRef.current) setAnchorRect(btnRef.current.getBoundingClientRect());
    setOpen(o => !o);
  }

  return (
    <div className="nf-profile-completion" style={{ position: 'relative', alignSelf: 'center' }}>
      <button
        ref={btnRef}
        type="button"
        onClick={toggleOpen}
        style={{
          all: 'unset', display: 'block', boxSizing: 'border-box', minWidth: 200,
          background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 10,
          padding: '10px 16px', cursor: hasMissing ? 'pointer' : 'default',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 7 }}>
          <span style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>
            Profile Completion
          </span>
          <span style={{ fontSize: 13, fontWeight: 800, color: 'var(--brand-bright)', fontVariantNumeric: 'tabular-nums' }}>
            {detail.percent}%
          </span>
        </div>
        <div style={{ height: 6, borderRadius: 4, background: 'var(--raised2)', overflow: 'hidden' }}>
          <div style={{
            height: '100%', width: `${detail.percent}%`, borderRadius: 4,
            background: 'linear-gradient(90deg, var(--brand) 0%, var(--brand-bright) 100%)',
            transition: 'width .4s ease',
          }} />
        </div>
        <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 7, textAlign: 'left' }}>
          {hasMissing ? `${detail.missing.length} item${detail.missing.length === 1 ? '' : 's'} left — click to see` : "You're all set!"}
        </div>
      </button>
      {open && hasMissing && anchorRect && (
        <MissingItemsDropdown items={detail.missing} anchorRect={anchorRect} onJumpTo={onJumpTo} onCloseRequest={() => setOpen(false)} />
      )}
    </div>
  );
}

export const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin',
  HR_ADMIN: 'HR Admin',
  MANAGER: 'Manager',
  EMPLOYEE: 'Employee',
};

export function SectionHeader({ title, badge, icon: Icon }: { title: string; badge?: string; icon?: LucideIcon }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 18 }}>
      {Icon && (
        <span style={{
          width: 22, height: 22, borderRadius: 6, flexShrink: 0,
          background: 'color-mix(in srgb, var(--brand) 14%, var(--raised2))',
          color: 'var(--brand-bright)', display: 'grid', placeItems: 'center',
        }}>
          <Icon size={12} />
        </span>
      )}
      <h2 style={{ margin: 0, fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', textTransform: 'uppercase', letterSpacing: '.06em' }}>
        {title}
      </h2>
      {badge && (
        <span style={{ fontSize: 10, fontWeight: 600, padding: '2px 7px', borderRadius: 20, background: 'rgba(107,114,128,.15)', color: 'var(--txt-dim)', letterSpacing: '.04em', textTransform: 'uppercase' }}>
          {badge}
        </span>
      )}
    </div>
  );
}

export function ReadField({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 13, color: value ? 'var(--txt)' : 'var(--txt-dim)', fontStyle: value ? 'normal' : 'italic', minHeight: 20 }}>
        {value || 'Not set'}
      </div>
    </div>
  );
}

export const INPUT_STYLE: React.CSSProperties = {
  width: '100%', boxSizing: 'border-box',
  background: 'var(--raised)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '7px 10px', fontSize: 13, color: 'var(--txt)', outline: 'none',
};

export function FieldLabel({ children }: { children: React.ReactNode }) {
  return (
    <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>
      {children}
    </label>
  );
}

// How long a "that character isn't allowed" hint stays visible after the last rejected
// keystroke — long enough to read, short enough to not linger once the person's moved on.
const REJECT_HINT_MS = 2000;

export function EditField({ label, value, onChange, type = 'text', placeholder, error, filter, filterHint }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  placeholder?: string;
  error?: string | null;
  // Previously callers wrapped onChange themselves (e.g. `onChange={v => setX(nameCharsOnly(v))}`)
  // — invisible characters just vanished with no feedback. Passing the filter in here instead
  // lets the field notice when a keystroke actually got rejected and show filterHint briefly,
  // rather than the input silently ignoring what was typed.
  filter?: (v: string) => string;
  filterHint?: string;
}) {
  const [showHint, setShowHint] = useState(false);
  const hintTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  function handleChange(raw: string) {
    if (!filter) { onChange(raw); return; }
    const filtered = filter(raw);
    if (filtered.length < raw.length) {
      setShowHint(true);
      clearTimeout(hintTimer.current);
      hintTimer.current = setTimeout(() => setShowHint(false), REJECT_HINT_MS);
    }
    onChange(filtered);
  }

  return (
    <div>
      <FieldLabel>{label}</FieldLabel>
      <input type={type} value={value} onChange={e => handleChange(e.target.value)} placeholder={placeholder}
        style={{ ...INPUT_STYLE, ...(error ? { borderColor: 'var(--risk)' } : {}) }} />
      {error ? (
        <div style={{ fontSize: 11, color: 'var(--risk)', marginTop: 4 }}>{error}</div>
      ) : showHint && filterHint ? (
        <div style={{ fontSize: 11, color: 'var(--warn)', marginTop: 4 }}>{filterHint}</div>
      ) : null}
    </div>
  );
}

export function PhoneField({ label, value, onChange, placeholder, error }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  error?: string | null;
}) {
  const [showHint, setShowHint] = useState(false);
  const hintTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  function handleChange(raw: string) {
    const filtered = digitsOnly(raw).slice(0, 10);
    // Same reasoning as EditField's filter above — this field has always silently stripped
    // non-digits and truncated past 10, with no feedback for either case.
    if (/\D/.test(raw) || raw.length > 10) {
      setShowHint(true);
      clearTimeout(hintTimer.current);
      hintTimer.current = setTimeout(() => setShowHint(false), REJECT_HINT_MS);
    }
    onChange(filtered);
  }

  return (
    <div>
      <FieldLabel>{label}</FieldLabel>
      <input
        type="tel"
        inputMode="numeric"
        maxLength={10}
        value={value}
        onChange={e => handleChange(e.target.value)}
        placeholder={placeholder}
        style={{ ...INPUT_STYLE, ...(error ? { borderColor: 'var(--risk)' } : {}) }}
      />
      {error ? (
        <div style={{ fontSize: 11, color: 'var(--risk)', marginTop: 4 }}>{error}</div>
      ) : showHint ? (
        <div style={{ fontSize: 11, color: 'var(--warn)', marginTop: 4 }}>Only numbers are allowed (10 digits).</div>
      ) : null}
    </div>
  );
}

export function SelectField({ label, value, onChange, options, placeholder = 'Select…' }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: readonly string[];
  placeholder?: string;
}) {
  return (
    <div>
      <FieldLabel>{label}</FieldLabel>
      <select value={value} onChange={e => onChange(e.target.value)} style={{ ...INPUT_STYLE }}>
        <option value="">{placeholder}</option>
        {options.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
    </div>
  );
}

export function TextAreaField({ label, value, onChange, rows = 3, placeholder }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  rows?: number;
  placeholder?: string;
}) {
  return (
    <div>
      <FieldLabel>{label}</FieldLabel>
      <textarea value={value} onChange={e => onChange(stripEmoji(e.target.value))}
        placeholder={placeholder} rows={rows}
        style={{ ...INPUT_STYLE, resize: 'vertical', fontFamily: 'inherit' }} />
    </div>
  );
}

// Generic per-section edit modal shell — fixed overlay, own card, Cancel/Save footer. Every new
// My Profile section modal (Primary Details, Contact, Addresses, Education, Identity &
// Statutory) is built from this, following the same overlay pattern PhotoModal/AvatarPickerModal
// already established in this file.
export function EditModal({ title, onClose, onSave, saving, saveDisabled, children, width = 560 }: {
  title: string;
  onClose: () => void;
  onSave: () => void;
  saving: boolean;
  saveDisabled?: boolean;
  children: React.ReactNode;
  width?: number;
}) {
  // Without this, scrolling the modal's own body once it runs out of content chains into the
  // page behind the fixed overlay — the page jumps/re-renders under your cursor mid-scroll,
  // which reads as the whole modal "glitching". Locking body scroll while open stops that;
  // restoring the previous value (not assuming '') avoids clobbering another modal's own lock.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);

  // Portaled to <body>: every caller renders this inline inside a `.nf-profile-card`, which has
  // its own `:hover { transform: translateY(-1px) }`. A `transform` on any ancestor becomes the
  // positioning reference for a `position: fixed` descendant instead of the viewport — so with
  // the cursor still resting over the card right after clicking Edit (hover active), this overlay
  // would render positioned relative to the card, then visibly snap to the real viewport-centered
  // position the instant the mouse leaves the card. Portaling escapes that entirely — the same
  // fix MissingItemsDropdown already uses in this file for the same reason.
  return createPortal(
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600, padding: 'clamp(16px, 4vw, 40px)' }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 12,
          width: `clamp(320px, 60vw, ${width}px)`, maxWidth: '95vw', maxHeight: '90vh',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
          boxShadow: '0 24px 64px rgba(0,0,0,.55)',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '18px 22px', borderBottom: '1px solid var(--line)' }}>
          <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{title}</span>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>
        <div style={{ padding: 22, overflowY: 'auto' }}>{children}</div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, padding: '14px 22px', borderTop: '1px solid var(--line)' }}>
          <button onClick={onClose} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            Cancel
          </button>
          <button onClick={onSave} disabled={saving || saveDisabled}
            style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: (saving || saveDisabled) ? 'not-allowed' : 'pointer', opacity: (saving || saveDisabled) ? .7 : 1 }}>
            {saving ? 'Saving…' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

// My Profile page header background. `coverDataUrl` (an uploaded custom cover) always wins when
// present; `themeBackground` is the existing theme-color banner CSS value (ProfilePage's
// `heroBackground`) used as the fallback for every account that hasn't uploaded one — so a
// profile with no custom cover renders pixel-identical to before this feature existed. Entirely
// independent of the avatar: no shared state, no mutual exclusivity, unlike photoDataUrl/avatarUrl.
// The pencil control mirrors the avatar's own edit button (same shape/placement idiom, `Camera`
// swapped for `Pencil`) so it reads as part of the same page rather than a bolted-on control; the
// small secondary "restore default" button only appears once a custom cover is actually set.
export function ProfileCoverBanner({ coverDataUrl, themeBackground, editable, uploading, removing, onUpload, onRemove }: {
  coverDataUrl: string | null;
  themeBackground: string;
  editable: boolean;
  uploading: boolean;
  removing: boolean;
  onUpload: (file: File) => void;
  onRemove: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const busy = uploading || removing;
  // object-position: center keeps the natural crop centered regardless of the uploaded image's
  // own aspect ratio — no stretching, matches `cover`'s usual behavior for a wide, short banner.
  const background = coverDataUrl
    ? `url(${coverDataUrl}) center center / cover no-repeat`
    : themeBackground;

  return (
    <div style={{ position: 'relative', height: 'clamp(130px, 14vw, 160px)' }}>
      <div style={{ position: 'absolute', inset: 0, background }} />
      <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, transparent 85%, var(--panel) 100%)' }} />

      {editable && (
        <div style={{ position: 'absolute', top: 12, right: 12, display: 'flex', gap: 8 }}>
          {coverDataUrl && (
            <button
              type="button"
              onClick={onRemove}
              disabled={busy}
              aria-label="Remove custom cover and restore default"
              title="Restore default cover"
              style={{
                width: 30, height: 30, borderRadius: '50%', background: 'rgba(20,20,22,.55)',
                border: '1px solid rgba(255,255,255,.35)', backdropFilter: 'blur(2px)',
                display: 'grid', placeItems: 'center', cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? .6 : 1,
              }}
            >
              <RotateCcw size={13} color="#fff" aria-hidden="true" />
            </button>
          )}
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            disabled={busy}
            aria-label={coverDataUrl ? 'Change cover image' : 'Add cover image'}
            title={coverDataUrl ? 'Change cover image' : 'Add cover image'}
            style={{
              width: 30, height: 30, borderRadius: '50%', background: 'var(--brand)',
              border: '1px solid rgba(255,255,255,.35)',
              display: 'grid', placeItems: 'center', cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? .6 : 1,
            }}
          >
            <Pencil size={13} color="#fff" aria-hidden="true" />
          </button>
          <input
            ref={inputRef}
            type="file"
            accept="image/*"
            style={{ display: 'none' }}
            onChange={e => {
              const file = e.target.files?.[0];
              if (file) onUpload(file);
              e.target.value = '';
            }}
          />
        </div>
      )}
    </div>
  );
}

// Small work/role icon beside the designation line — purely decorative (aria-hidden), same
// idiom as the sub-meta bar's Mail/Phone/MapPin icons elsewhere on this page, just applied to
// the designation itself. Kept as its own component so both ProfilePage.tsx and its preview
// render identical markup.
export function DesignationLine({ text }: { text: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 13.5, color: 'var(--txt-mut)', marginBottom: 8 }}>
      <Briefcase size={12} aria-hidden="true" style={{ flexShrink: 0 }} />
      <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{text}</span>
    </div>
  );
}

// "Today" status badge — only rendered for a weekly-off or holiday day (null renders nothing, so
// a normal working day looks exactly as it did before this feature). Deliberately just another
// entry in ProfilePage's existing flex-wrap badge row (Role/Active/In-Out/Employee Code) rather
// than a separately-positioned element, so it inherits that row's already-responsive wrapping
// instead of needing its own overlap-avoidance logic on narrow screens.
export function ProfileDayStatusBadge({ status }: { status: ProfileDayStatus }) {
  if (!status) return null;
  const isHoliday = status === 'holiday';
  return (
    <span style={{
      fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 20,
      textTransform: 'uppercase', letterSpacing: '.03em', whiteSpace: 'nowrap',
      background: isHoliday ? 'rgba(76,141,214,.15)' : 'rgba(155,161,172,.15)',
      color: isHoliday ? '#4C8DD6' : '#9BA1AC',
    }}>
      {isHoliday ? 'Holiday' : 'Weekly Off'}
    </span>
  );
}

// Profile photo popup — expands the avatar into a preview with Remove/Edit actions below it.
// Every size (dialog width/padding, the enlarged photo, button type) scales continuously with
// `clamp()` driven by `vw`, with no max-width media query gating it, so it resizes smoothly at
// every viewport width — phone through ultrawide desktop — rather than jumping between a
// handful of fixed breakpoint sizes.
export function PhotoModal({ photoDataUrl, initials, uploading, removing, onEditClick, onRemove, onChooseAvatarClick, onClose }: {
  photoDataUrl: string | null;
  initials: string;
  uploading: boolean;
  removing: boolean;
  onEditClick: () => void;
  onRemove: () => void;
  onChooseAvatarClick: () => void;
  onClose: () => void;
}) {
  const busy = uploading || removing;
  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600, padding: 'clamp(16px, 4vw, 40px)' }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 'clamp(10px, 1.4vw, 16px)',
          padding: 'clamp(20px, 3.4vw, 32px)', display: 'flex', flexDirection: 'column', alignItems: 'center',
          gap: 'clamp(14px, 2.4vw, 22px)', width: 'clamp(240px, 34vw, 420px)', maxWidth: '92vw',
          boxShadow: '0 24px 64px rgba(0,0,0,.55)',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
          <span style={{ fontSize: 'clamp(13px, 1.4vw, 15px)', fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            Profile Photo
          </span>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>

        {photoDataUrl ? (
          <img
            src={photoDataUrl}
            alt="Profile"
            style={{
              width: 'clamp(140px, 24vw, 280px)', height: 'clamp(140px, 24vw, 280px)',
              borderRadius: '50%', objectFit: 'cover', border: '3px solid var(--line2)',
            }}
          />
        ) : (
          <div
            style={{
              width: 'clamp(140px, 24vw, 280px)', height: 'clamp(140px, 24vw, 280px)', borderRadius: '50%',
              background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff',
              fontSize: 'clamp(32px, 6vw, 64px)', fontWeight: 700, border: '3px solid rgba(177,17,22,.4)',
            }}
          >
            {initials}
          </div>
        )}

        <div style={{ display: 'flex', gap: 'clamp(8px, 1.4vw, 12px)', width: '100%' }}>
          {photoDataUrl && (
            <button
              onClick={onRemove}
              disabled={busy}
              style={{
                flex: 1, padding: 'clamp(8px, 1.4vw, 10px) clamp(10px, 2vw, 16px)', background: 'var(--raised)',
                border: '1px solid var(--line2)', borderRadius: 7, fontSize: 'clamp(12px, 1.3vw, 13.5px)',
                fontWeight: 600, color: 'var(--risk)', cursor: busy ? 'not-allowed' : 'pointer',
                opacity: busy ? .7 : 1,
              }}
            >
              {removing ? 'Removing…' : 'Remove'}
            </button>
          )}
          <button
            onClick={onEditClick}
            disabled={busy}
            style={{
              flex: 1, padding: 'clamp(8px, 1.4vw, 10px) clamp(10px, 2vw, 16px)', background: 'var(--brand)',
              border: 'none', borderRadius: 7, fontSize: 'clamp(12px, 1.3vw, 13.5px)', fontWeight: 600,
              color: '#fff', cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? .7 : 1,
            }}
          >
            {uploading ? 'Uploading…' : 'Edit'}
          </button>
        </div>

        <button
          onClick={onChooseAvatarClick}
          disabled={busy}
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, width: '100%',
            padding: 'clamp(8px, 1.4vw, 10px) clamp(10px, 2vw, 16px)', background: 'none',
            border: '1px dashed var(--line2)', borderRadius: 7, fontSize: 'clamp(12px, 1.3vw, 13.5px)',
            fontWeight: 600, color: 'var(--txt-mut)', cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? .7 : 1,
          }}
        >
          <Sparkles size={14} aria-hidden="true" /> Choose an avatar instead
        </button>
      </div>
    </div>
  );
}

// DiceBear "lorelei" avatars — a fixed set of seeds so the grid is stable across renders (a random
// seed per render would make every option change every time the modal reopens). Picking one
// replaces any uploaded photo (see ProfileService#setAvatar on the backend — the two are
// mutually exclusive), matching "either a real photo or a generated avatar, not both".
const AVATAR_SEEDS = ['Aria', 'Milo', 'Nova', 'Leo', 'Zara', 'Kai', 'Luna', 'Finn', 'Iris', 'Theo', 'Maya', 'Ezra'];
// A fixed, neutral slate-gray palette (skin/hair/outline/eyes/etc. all one muted tone, light
// background) so every generated avatar reads as calm and monochrome instead of DiceBear's
// default randomized, often brightly-colored look clashing with whatever's around it.
const AVATAR_STYLE_PARAMS =
  // backgroundType=solid is required for backgroundColor to actually paint a fill — without it
  // DiceBear leaves the background transparent regardless of backgroundColor, letting whatever
  // sits behind the avatar (the topbar's gradient, a panel, etc.) show through unevenly whenever
  // the character illustration itself doesn't reach every edge of the square.
  'backgroundType=solid&backgroundColor=f0f0f2&skinColor=e4e4e6&hairColor=3f4247&outlineColor=3f4247' +
  '&eyebrowsColor=3f4247&eyesColor=3f4247&noseColor=3f4247&mouthColor=3f4247' +
  '&frecklesColor=3f4247&glassesColor=3f4247&earringsColor=3f4247&hairAccessoriesColor=3f4247';

export function dicebearUrl(seed: string): string {
  return `https://api.dicebear.com/10.x/lorelei/svg?seed=${encodeURIComponent(seed)}&${AVATAR_STYLE_PARAMS}`;
}

export function AvatarPickerModal({ currentAvatarUrl, settingAvatar, onPick, onClose }: {
  currentAvatarUrl: string | null;
  settingAvatar: string | null;
  onPick: (seed: string) => void;
  onClose: () => void;
}) {
  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 610, padding: 'clamp(16px, 4vw, 40px)' }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 'clamp(10px, 1.4vw, 16px)',
          padding: 'clamp(20px, 3.4vw, 32px)', display: 'flex', flexDirection: 'column',
          gap: 'clamp(14px, 2.4vw, 20px)', width: 'clamp(280px, 42vw, 480px)', maxWidth: '92vw',
          boxShadow: '0 24px 64px rgba(0,0,0,.55)',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: 'clamp(13px, 1.4vw, 15px)', fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            Choose an avatar
          </span>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
          {AVATAR_SEEDS.map(seed => {
            const url = dicebearUrl(seed);
            const isCurrent = currentAvatarUrl === url;
            const isBusy = settingAvatar === seed;
            return (
              <button
                key={seed}
                onClick={() => onPick(seed)}
                disabled={settingAvatar !== null}
                aria-label={`Use the ${seed} avatar`}
                style={{
                  position: 'relative', padding: 6, borderRadius: '50%', aspectRatio: '1',
                  border: isCurrent ? '2px solid var(--brand)' : '2px solid var(--line)',
                  background: 'var(--raised2)', cursor: settingAvatar !== null ? 'not-allowed' : 'pointer',
                  opacity: settingAvatar !== null && !isBusy ? .5 : 1,
                }}
              >
                <img src={url} alt="" aria-hidden="true" style={{ width: '100%', height: '100%', borderRadius: '50%', display: 'block' }} />
                {isCurrent && (
                  <span style={{ position: 'absolute', bottom: -2, right: -2, width: 18, height: 18, borderRadius: '50%', background: 'var(--brand)', border: '2px solid var(--panel)', display: 'grid', placeItems: 'center' }}>
                    <Check size={10} color="#fff" aria-hidden="true" />
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
