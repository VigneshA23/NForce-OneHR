import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Bell, Check, Info, Monitor, Moon, Palette, Sun, SlidersHorizontal, Accessibility, Mail, Smartphone, Megaphone, CalendarDays, ClipboardCheck, FileText, Contrast, Wind, Keyboard } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import './userPreferences.css';
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
const TAB_META: Record<Tab, { icon: LucideIcon; sub: string }> = {
  Appearance: { icon: Palette, sub: 'Mode, colour and density' },
  Notifications: { icon: Bell, sub: 'What you hear about' },
  Accessibility: { icon: Accessibility, sub: 'Readability and focus' },
};

// ── Shared row/field building blocks ─────────────────────────────────────────────

function Section({ icon: Icon, title, sub, children }: { icon: LucideIcon; title: string; sub: string; children: React.ReactNode }) {
  return (
    <section className="nf-up-sec">
      <div className="nf-up-sec-head">
        <span className="nf-up-ico"><Icon size={17} /></span>
        <div>
          <div className="nf-up-sec-title">{title}</div>
          <div className="nf-up-sec-sub">{sub}</div>
        </div>
      </div>
      {children}
    </section>
  );
}

function PlaceholderNote({ children }: { children: React.ReactNode }) {
  return <p className="nf-up-note"><Info size={14} /><span>{children}</span></p>;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="nf-up-flabel">{label}</div>
      {children}
    </div>
  );
}

function Segmented<T extends string>({ label, value, options, onChange }: {
  label: string; value: T; options: readonly T[]; onChange: (v: T) => void;
}) {
  return (
    <div className="nf-up-seg" role="radiogroup" aria-label={label}>
      {options.map(o => (
        <button key={o} type="button" role="radio" aria-checked={value === o} className={value === o ? 'is-on' : ''} onClick={() => onChange(o)}>
          {value === o && <Check size={13} aria-hidden="true" />}{o}
        </button>
      ))}
    </div>
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
      className={`nf-up-switch${checked ? ' is-on' : ''}`}
    >
      <span aria-hidden="true" />
    </button>
  );
}

function ToggleRow({ icon: Icon, label, sub, checked, onChange }: {
  icon: LucideIcon; label: string; sub?: string; checked: boolean; onChange: (v: boolean) => void;
}) {
  return (
    <div className={`nf-up-row${checked ? ' is-on' : ''}`}>
      <span className="nf-up-row-ico"><Icon size={16} /></span>
      <div className="nf-up-row-txt">
        <div className="nf-up-row-label">{label}</div>
        {sub && <div className="nf-up-row-sub">{sub}</div>}
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
    <div className="nf-up" style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 980 }}>
      <div>
        <button onClick={() => navigate(-1)} className="nf-up-back">
          <ArrowLeft size={14} aria-hidden="true" /> Back to Application
        </button>
        <PageTitleBlock card icon={SlidersHorizontal}>
          <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
            User Preferences
          </h1>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>
            Here you can manage all your personal settings like appearance, notifications, etc.
          </p>
        </PageTitleBlock>
      </div>

      <div className="nf-up-grid">
        <nav className="nf-up-nav" aria-label="Preference sections">
          {TABS.map(t => {
            const { icon: Icon, sub } = TAB_META[t];
            return (
              <button key={t} onClick={() => setTab(t)} className={`nf-up-tab${tab === t ? ' is-on' : ''}`} aria-current={tab === t ? 'page' : undefined}>
                <span className="nf-up-tab-ico"><Icon size={16} /></span>
                <span className="nf-up-tab-txt"><b>{t}</b><span>{sub}</span></span>
              </button>
            );
          })}
        </nav>

        <div className="nf-up-content">
          {tab === 'Appearance' && (
            <>
              <Section icon={Sun} title="Display mode" sub={'Choose how NForce OneHR looks to you. "Auto" follows your system setting.'}>
                <div className="nf-up-modes">
                  {DISPLAY_MODES.map(({ key, label, icon: Icon }) => (
                    <button key={key} onClick={() => setMode(key)} className={`nf-up-mode${mode === key ? ' is-on' : ''}`} aria-pressed={mode === key}>
                      <span className={`nf-up-prev nf-up-prev--${key}`} aria-hidden="true"><i /><i /><i /></span>
                      <span className="nf-up-mode-label"><Icon size={14} aria-hidden="true" />{label}{mode === key && <Check size={14} className="nf-up-check" aria-hidden="true" />}</span>
                    </button>
                  ))}
                </div>
              </Section>

              <Section icon={Palette} title="Theme color" sub="Pick the accent color used across buttons, links, and highlights.">
                <div className="nf-up-swatches">
                  {(Object.entries(ACCENT_SWATCHES) as [AccentColor, typeof ACCENT_SWATCHES[AccentColor]][]).map(([key, swatch]) => (
                    <button key={key} onClick={() => setAccent(key)} title={swatch.label} aria-pressed={accent === key} className={`nf-up-swatch${accent === key ? ' is-on' : ''}`}>
                      <span className="nf-up-dot" style={{ background: swatch.brand }}>{accent === key && <Check size={14} aria-hidden="true" />}</span>
                      {swatch.label}
                    </button>
                  ))}
                </div>
              </Section>

              <Section icon={SlidersHorizontal} title="Density" sub="How tightly packed lists, tables, and cards should feel.">
                <Segmented label="Density" value={density} options={DENSITIES} onChange={handleDensityChange} />
                <PlaceholderNote>Saved, but no page adjusts its spacing based on this yet.</PlaceholderNote>
              </Section>
            </>
          )}

          {tab === 'Notifications' && (
            <Section icon={Bell} title="Notifications" sub="Choose what NForce OneHR notifies you about.">
              <div className="nf-up-rows">
                <ToggleRow icon={Mail} label="Email notifications" sub="Receive a copy of your notifications by email" checked={emailNotif} onChange={setEmailNotif} />
                <ToggleRow icon={Smartphone} label="Push notifications" sub="Browser/device push alerts when the app is in the background" checked={pushNotif} onChange={setPushNotif} />
                <ToggleRow icon={Megaphone} label="HR announcements" sub="Company-wide announcements and policy updates" checked={hrAnnouncements} onChange={setHrAnnouncements} />
                <ToggleRow icon={CalendarDays} label="Leave updates" sub="Your leave requests being approved, rejected, or commented on" checked={leaveUpdates} onChange={setLeaveUpdates} />
                <ToggleRow icon={ClipboardCheck} label="Approval updates" sub="Items in your Approval Center changing status" checked={approvalUpdates} onChange={setApprovalUpdates} />
                <ToggleRow icon={FileText} label="Document reminders" sub="Pending document uploads or verifications" checked={docReminders} onChange={setDocReminders} />
              </div>
              <PlaceholderNote>
                These switches are saved but not yet enforced - every notification type above still
                arrives today regardless of what's toggled off here. Muting a category would need a
                matching preference on the backend's notification pipeline.
              </PlaceholderNote>
            </Section>
          )}

          {tab === 'Accessibility' && (
            <Section icon={Accessibility} title="Accessibility" sub="Make NForce OneHR easier to read and navigate.">
              <Field label="Font size">
                <Segmented label="Font size" value={fontSize} options={FONT_SIZES} onChange={setFontSize} />
              </Field>
              <div className="nf-up-rows" style={{ marginTop: 18 }}>
                <ToggleRow icon={Contrast} label="High contrast" sub="Increase text and border contrast throughout the app" checked={highContrast} onChange={setHighContrast} />
                <ToggleRow icon={Wind} label="Reduce animations" sub="Turn off pulsing/sliding effects - everything appears instantly instead" checked={reduceAnimations} onChange={setReduceAnimations} />
                <ToggleRow icon={Keyboard} label="Keyboard navigation" sub="Show a strong highlight around whatever you've focused with Tab" checked={keyboardNavFocus} onChange={setKeyboardNavFocus} />
              </div>
            </Section>
          )}
        </div>
      </div>
    </div>
  );
}
