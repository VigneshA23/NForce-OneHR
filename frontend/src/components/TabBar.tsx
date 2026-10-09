import { useRef, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react';

/* Shared tab controls — the Assets & Expenses underline tab bar, lifted into one place so every
   page draws the same thing. Visuals live in index.css (.nf-tabs / .nf-chips and the --tabbar-* /
   --chip-* tokens); this file only owns markup, ARIA and keyboard behaviour.

   TabBar    -> content navigation (switches which panel is shown): role="tablist"/"tab".
   ChipGroup -> filters / view toggles / period selectors over the same content: aria-pressed
                buttons in a role="group", deliberately NOT tabs. */

export interface TabDef<K extends string = string> {
  key: K;
  label: ReactNode;
  /** Shown as a badge after the label when > 0 — same rule as the original Assets bar. */
  count?: number | null;
  icon?: ReactNode;
  disabled?: boolean;
  title?: string;
}

interface TabBarProps<K extends string> {
  tabs: TabDef<K>[];
  active: K;
  onChange: (key: K) => void;
  /** 'sm' for tabs nested inside another tab's panel. */
  size?: 'md' | 'sm';
  ariaLabel?: string;
  className?: string;
  style?: CSSProperties;
  /** Right-aligned extras inside the bar (e.g. an action button). */
  trailing?: ReactNode;
}

export function TabBar<K extends string>({ tabs, active, onChange, size = 'md', ariaLabel, className, style, trailing }: TabBarProps<K>) {
  const ref = useRef<HTMLDivElement>(null);

  // WAI-ARIA tabs pattern: arrows/Home/End move between tabs and select them.
  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if ((e.target as HTMLElement).getAttribute('role') !== 'tab') return;
    const enabled = tabs.filter(t => !t.disabled);
    const i = enabled.findIndex(t => t.key === active);
    let next: number | null = null;
    if (e.key === 'ArrowRight') next = (i + 1) % enabled.length;
    else if (e.key === 'ArrowLeft') next = (i - 1 + enabled.length) % enabled.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = enabled.length - 1;
    if (next === null || !enabled.length) return;
    e.preventDefault();
    const key = enabled[next].key;
    onChange(key);
    ref.current?.querySelector<HTMLButtonElement>(`[data-tab-key="${CSS.escape(key)}"]`)?.focus();
  }

  return (
    <div
      ref={ref}
      role="tablist"
      aria-label={ariaLabel}
      className={`nf-tabs${size === 'sm' ? ' nf-tabs--sm' : ''}${className ? ` ${className}` : ''}`}
      style={style}
      onKeyDown={onKeyDown}
    >
      {tabs.map(t => {
        const selected = t.key === active;
        return (
          <button
            key={t.key}
            type="button"
            role="tab"
            data-tab-key={t.key}
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            disabled={t.disabled}
            title={t.title}
            className="nf-tab"
            onClick={() => onChange(t.key)}
          >
            {t.icon}
            {t.label}
            {t.count != null && t.count > 0 && <span className="nf-tab-count">{t.count}</span>}
          </button>
        );
      })}
      {trailing && <div className="nf-tabs-trailing">{trailing}</div>}
    </div>
  );
}

interface ChipGroupProps<K extends string> {
  options: TabDef<K>[];
  value: K;
  onChange: (key: K) => void;
  ariaLabel?: string;
  className?: string;
  style?: CSSProperties;
}

export function ChipGroup<K extends string>({ options, value, onChange, ariaLabel, className, style }: ChipGroupProps<K>) {
  return (
    <div role="group" aria-label={ariaLabel} className={`nf-chips${className ? ` ${className}` : ''}`} style={style}>
      {options.map(o => (
        <button
          key={o.key}
          type="button"
          aria-pressed={o.key === value}
          disabled={o.disabled}
          title={o.title}
          className="nf-chip"
          onClick={() => onChange(o.key)}
        >
          {o.icon}
          {o.label}
          {o.count != null && o.count > 0 && <span className="nf-tab-count">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}
