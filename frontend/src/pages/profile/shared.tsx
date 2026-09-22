import { X, Sparkles, Check } from 'lucide-react';
import type { ProfileData } from '../../api/profile';

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
  if (!v) return null;
  if (!EMAIL_RE.test(v)) return 'Enter a valid email address (e.g. name@example.com).';
  const tld = v.slice(v.lastIndexOf('.') + 1);
  if (TLD_COM_WITH_TRAILING_CHARS_RE.test(tld)) return 'Enter a valid email address (e.g. name@example.com).';
  return null;
}

export function validatePhone(v: string, label: string): string | null {
  if (!v) return null;
  return digitsOnly(v).length === 10 ? null : `${label} must be exactly 10 digits.`;
}

export function validateName(v: string, label: string): string | null {
  if (!v) return null;
  return NAME_RE.test(v) ? null : `${label} can only contain letters, spaces, hyphens, apostrophes, and periods.`;
}

// Display Name is derived, not a separately stored/edited field — First/Middle/Last Name
// (edited via Primary Details) are the single source of truth. Falls back to fullName for the
// rare account with no employee record / no name parts set yet.
export function computeDisplayName(profile: Pick<ProfileData, 'firstName' | 'middleName' | 'lastName' | 'fullName'>): string {
  return [profile.firstName, profile.middleName, profile.lastName].filter(Boolean).join(' ').trim() || profile.fullName;
}

export const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin',
  HR_ADMIN: 'HR Admin',
  MANAGER: 'Manager',
  EMPLOYEE: 'Employee',
};

export function SectionHeader({ title, badge }: { title: string; badge?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 18 }}>
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
      <div style={{ fontSize: 13, color: value ? 'var(--txt)' : 'var(--txt-dim)', minHeight: 20 }}>{value || '—'}</div>
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

export function EditField({ label, value, onChange, type = 'text', placeholder, error }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  placeholder?: string;
  error?: string | null;
}) {
  return (
    <div>
      <FieldLabel>{label}</FieldLabel>
      <input type={type} value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        style={{ ...INPUT_STYLE, ...(error ? { borderColor: 'var(--risk)' } : {}) }} />
      {error && <div style={{ fontSize: 11, color: 'var(--risk)', marginTop: 4 }}>{error}</div>}
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
  return (
    <div>
      <FieldLabel>{label}</FieldLabel>
      <input
        type="tel"
        inputMode="numeric"
        maxLength={10}
        value={value}
        onChange={e => onChange(digitsOnly(e.target.value).slice(0, 10))}
        placeholder={placeholder}
        style={{ ...INPUT_STYLE, ...(error ? { borderColor: 'var(--risk)' } : {}) }}
      />
      {error && <div style={{ fontSize: 11, color: 'var(--risk)', marginTop: 4 }}>{error}</div>}
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
  return (
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
    </div>
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
