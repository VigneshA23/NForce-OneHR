import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Check, Monitor, Moon, Sun } from 'lucide-react';
import { useTheme, type ThemeMode } from '../lib/theme';
import { ACCENT_SWATCHES, useAccentColor, type AccentColor } from '../lib/accentColor';
import { useAccessibilityPrefs, type FontSize } from '../lib/accessibilityPrefs';

// ── Generic persisted-preference helper (Notifications tab only — see its own note below) ──

function usePersistedBool(key: string, fallback: boolean): [boolean, (v: boolean) => void] {
  const [value, setValue] = useState<boolean>(() => {
    try {
      const v = window.localStorage.getItem(key);
      return v === null ? fallback : v === 'true';
    } catch {
      return fallback;
    }
  });
  function update(next: boolean) {
    setValue(next);
    try { window.localStorage.setItem(key, String(next)); } catch { /* best effort */ }
  }
  return [value, update];
}

// ── Field option lists ────────────────────────────────────────────────────────────

const DENSITIES = ['Compact', 'Comfortable', 'Spacious'] as const;
const FONT_SIZES: readonly FontSize[] = ['Small', 'Default', 'Large'];

const DISPLAY_MODES: { key: ThemeMode; label: string; icon: typeof Sun }[] = [
  { key: 'light', label: 'Light', icon: Sun },
  { key: 'dark',  label: 'Dark',  icon: Moon },
  { key: 'auto',  label: 'Auto',  icon: Monitor },
];

const TABS = ['Appearance', 'Notifications', 'Accessibility'] as const;
type Tab = typeof TABS[number];

// ── Shared row/field building blocks ─────────────────────────────────────────────

const cardStyle: React.CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
};
const rowLabelStyle: React.CSSProperties = { fontSize: 12, color: 'var(--txt-dim)', marginBottom: 6 };
const rowValueStyle: React.CSSProperties = { fontSize: 14, color: 'var(--txt)', fontWeight: 500 };
const selectStyle: React.CSSProperties = {
  ...rowValueStyle, background: 'var(--raised2)', border: '1px solid var(--line)',
  borderRadius: 7, padding: '6px 10px', cursor: 'pointer', width: '100%',
};

function SectionIntro({ title, sub }: { title: string; sub: string }) {
  return (
    <div style={{ marginBottom: 22 }}>
      <div style={{ fontSize: 14.5, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>{title}</div>
      <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{sub}</div>
    </div>
  );
}

function PlaceholderNote({ children }: { children: React.ReactNode }) {
  return (
    <p style={{ fontSize: 11.5, color: 'var(--txt-dim)', fontStyle: 'italic', margin: '14px 0 0' }}>
      {children}
    </p>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div style={rowLabelStyle}>{label}</div>
      {children}
    </div>
  );
}

function SelectField<T extends string>({ label, value, options, onChange }: {
  label: string; value: T; options: readonly T[]; onChange: (v: T) => void;
}) {
  return (
    <Field label={label}>
      <select value={value} onChange={e => onChange(e.target.value as T)} style={selectStyle}>
        {options.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
    </Field>
  );
}

function Switch({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      style={{
        width: 38, height: 22, borderRadius: 999, border: 'none', cursor: 'pointer', padding: 0,
        background: checked ? 'var(--brand)' : 'var(--line2)', position: 'relative', flexShrink: 0,
        transition: 'background 150ms ease',
      }}
    >
      <span
        aria-hidden="true"
        style={{
          position: 'absolute', top: 2, left: checked ? 18 : 2, width: 18, height: 18, borderRadius: '50%',
          background: '#fff', transition: 'left 150ms ease', boxShadow: '0 1px 2px rgba(0,0,0,.3)',
        }}
      />
    </button>
  );
}

function ToggleRow({ label, sub, checked, onChange, last }: {
  label: string; sub?: string; checked: boolean; onChange: (v: boolean) => void; last?: boolean;
}) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16,
      padding: '11px 0', borderBottom: last ? 'none' : '1px solid var(--line)',
    }}>
      <div>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--txt)' }}>{label}</div>
        {sub && <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2 }}>{sub}</div>}
      </div>
      <Switch checked={checked} onChange={onChange} label={label} />
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────────

export default function UserPreferencesPage() {
  const navigate = useNavigate();
  const { mode, setMode } = useTheme();
  const { accent, setAccent } = useAccentColor();
  const {
    reduceAnimations, keyboardNavFocus, highContrast, fontSize,
    setReduceAnimations, setKeyboardNavFocus, setHighContrast, setFontSize,
  } = useAccessibilityPrefs();
  const [tab, setTab] = useState<Tab>('Appearance');

  // Appearance
  const [density, setDensity] = useState<typeof DENSITIES[number]>(() => {
    try {
      const v = window.localStorage.getItem('onehr.density');
      return (DENSITIES as readonly string[]).includes(v ?? '') ? (v as typeof DENSITIES[number]) : 'Comfortable';
    } catch {
      return 'Comfortable';
    }
  });
  function handleDensityChange(next: typeof DENSITIES[number]) {
    setDensity(next);
    try { window.localStorage.setItem('onehr.density', next); } catch { /* best effort */ }
  }

  // Notifications — all placeholders (no backend notification-preferences endpoint exists yet)
  const [emailNotif, setEmailNotif]           = usePersistedBool('onehr.notif.email', true);
  const [pushNotif, setPushNotif]             = usePersistedBool('onehr.notif.push', true);
  const [hrAnnouncements, setHrAnnouncements] = usePersistedBool('onehr.notif.hrAnnouncements', true);
  const [leaveUpdates, setLeaveUpdates]       = usePersistedBool('onehr.notif.leaveUpdates', true);
  const [approvalUpdates, setApprovalUpdates] = usePersistedBool('onehr.notif.approvalUpdates', true);
  const [docReminders, setDocReminders]       = usePersistedBool('onehr.notif.docReminders', true);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 900 }}>
      <div>
        <button
          onClick={() => navigate(-1)}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 6, background: 'none', border: 'none',
            cursor: 'pointer', color: 'var(--brand-bright)', fontSize: 12.5, padding: 0, marginBottom: 10,
          }}
        >
          <ArrowLeft size={14} aria-hidden="true" /> Back to Application
        </button>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
          User Preferences
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>
          Here you can manage all your personal settings like appearance, notifications, etc.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '200px 1fr', gap: 16, alignItems: 'start' }}>
        <div style={{ ...cardStyle, padding: 6, display: 'flex', flexDirection: 'column', gap: 2 }}>
          {TABS.map(t => (
            <button
              key={t}
              onClick={() => setTab(t)}
              style={{
                textAlign: 'left', padding: '9px 12px', borderRadius: 7, border: 'none', cursor: 'pointer',
                fontSize: 13, fontWeight: tab === t ? 600 : 500,
                background: tab === t ? 'color-mix(in srgb, var(--brand) 14%, var(--raised2))' : 'transparent',
                color: tab === t ? 'var(--brand-bright)' : 'var(--txt-mut)',
              }}
            >
              {t}
            </button>
          ))}
        </div>

        <div style={{ ...cardStyle, padding: 22 }}>
          {tab === 'Appearance' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 26 }}>
              <div>
                <div style={{ fontSize: 14.5, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>Display mode</div>
                <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 14 }}>
                  Choose how NForce OneHR looks to you. "Auto" follows your system setting.
                </div>
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  {DISPLAY_MODES.map(({ key, label, icon: Icon }) => (
                    <button
                      key={key}
                      onClick={() => setMode(key)}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 8, padding: '10px 16px', borderRadius: 8,
                        border: mode === key ? '1.5px solid var(--brand)' : '1px solid var(--line)',
                        background: mode === key ? 'color-mix(in srgb, var(--brand) 10%, var(--raised2))' : 'var(--raised2)',
                        color: 'var(--txt)', cursor: 'pointer', fontSize: 13, fontWeight: 500,
                      }}
                    >
                      <Icon size={15} aria-hidden="true" />
                      {label}
                      {mode === key && <Check size={14} style={{ color: 'var(--brand-bright)' }} aria-hidden="true" />}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div style={{ fontSize: 14.5, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>Theme color</div>
                <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 14 }}>
                  Pick the accent color used across buttons, links, and highlights.
                </div>
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  {(Object.entries(ACCENT_SWATCHES) as [AccentColor, typeof ACCENT_SWATCHES[AccentColor]][]).map(([key, swatch]) => (
                    <button
                      key={key}
                      onClick={() => setAccent(key)}
                      title={swatch.label}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 8, padding: '10px 16px', borderRadius: 8,
                        border: accent === key ? '1.5px solid var(--brand)' : '1px solid var(--line)',
                        background: accent === key ? 'color-mix(in srgb, var(--brand) 10%, var(--raised2))' : 'var(--raised2)',
                        color: 'var(--txt)', cursor: 'pointer', fontSize: 13, fontWeight: 500,
                      }}
                    >
                      <span style={{ width: 14, height: 14, borderRadius: '50%', background: swatch.brand, flexShrink: 0 }} />
                      {swatch.label}
                      {accent === key && <Check size={14} style={{ color: 'var(--brand-bright)' }} aria-hidden="true" />}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div style={{ fontSize: 14.5, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>Density</div>
                <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 14 }}>
                  How tightly packed lists, tables, and cards should feel.
                </div>
                <div style={{ maxWidth: 220 }}>
                  <select value={density} onChange={e => handleDensityChange(e.target.value as typeof density)} style={selectStyle}>
                    {DENSITIES.map(d => <option key={d} value={d}>{d}</option>)}
                  </select>
                </div>
                <PlaceholderNote>Saved, but no page adjusts its spacing based on this yet.</PlaceholderNote>
              </div>
            </div>
          )}

          {tab === 'Notifications' && (
            <div>
              <SectionIntro
                title="Notifications"
                sub="Choose what NForce OneHR notifies you about."
              />
              <ToggleRow label="Email notifications" sub="Receive a copy of your notifications by email" checked={emailNotif} onChange={setEmailNotif} />
              <ToggleRow label="Push notifications" sub="Browser/device push alerts when the app is in the background" checked={pushNotif} onChange={setPushNotif} />
              <ToggleRow label="HR announcements" sub="Company-wide announcements and policy updates" checked={hrAnnouncements} onChange={setHrAnnouncements} />
              <ToggleRow label="Leave updates" sub="Your leave requests being approved, rejected, or commented on" checked={leaveUpdates} onChange={setLeaveUpdates} />
              <ToggleRow label="Approval updates" sub="Items in your Approval Center changing status" checked={approvalUpdates} onChange={setApprovalUpdates} />
              <ToggleRow label="Document reminders" sub="Pending document uploads or verifications" checked={docReminders} onChange={setDocReminders} last />
              <PlaceholderNote>
                These switches are saved but not yet enforced — every notification type above still
                arrives today regardless of what's toggled off here. Muting a category would need a
                matching preference on the backend's notification pipeline.
              </PlaceholderNote>
            </div>
          )}

          {tab === 'Accessibility' && (
            <div>
              <SectionIntro title="Accessibility" sub="Make NForce OneHR easier to read and navigate." />
              <div style={{ maxWidth: 220, marginBottom: 18 }}>
                <SelectField label="Font size" value={fontSize} options={FONT_SIZES} onChange={setFontSize} />
              </div>
              <ToggleRow
                label="High contrast"
                sub="Increase text and border contrast throughout the app"
                checked={highContrast}
                onChange={setHighContrast}
              />
              <ToggleRow
                label="Reduce animations"
                sub="Turn off pulsing/sliding effects — everything appears instantly instead"
                checked={reduceAnimations}
                onChange={setReduceAnimations}
              />
              <ToggleRow
                label="Keyboard navigation"
                sub="Show a strong highlight around whatever you've focused with Tab"
                checked={keyboardNavFocus}
                onChange={setKeyboardNavFocus}
                last
              />
              <PlaceholderNote>
                All four of these are live app-wide — try them now.
              </PlaceholderNote>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
