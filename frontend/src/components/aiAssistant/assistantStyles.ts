import type React from 'react';

/**
 * Style constants for the assistant, in the idiom the rest of OneHR uses: inline styles over CSS
 * custom properties. MUI and Emotion are installed but imported nowhere in this codebase, and
 * `HelpDeskPage.tsx` says it outright — there is no shared component library, every page
 * re-declares what it needs. Building the assistant in a different idiom would make it the one
 * surface that does not follow the theme.
 *
 * The z-index values are the part worth reading. OneHR already has a ladder — topbar 30, mobile
 * scrim 90, sidebar 100, dropdowns 200, modals 500, portals 999, toasts 9999 — and the assistant
 * slots into it rather than topping it:
 *
 *  - launcher at 80, BELOW the mobile scrim, so the off-canvas nav correctly covers it;
 *  - panel at 110, above the sidebar but below dropdowns and modals, so an open modal still owns
 *    the screen and a toast is still readable over the panel.
 */
export const Z_LAUNCHER = 80;
export const Z_PANEL = 110;

export const launcherStyle: React.CSSProperties = {
  position: 'fixed',
  right: 22,
  bottom: 22,
  zIndex: Z_LAUNCHER,
  width: 50,
  height: 50,
  borderRadius: '50%',
  border: '1px solid rgba(177,17,22,.35)',
  background: 'var(--brand)',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer',
  boxShadow: '0 10px 28px rgba(0,0,0,.45)',
  transition: 'transform 150ms ease',
};

/** The launcher's permanent ambient halo — present on every page, indefinitely, so it's a much
 *  slower and fainter breathe than the header badge's (nf-assistant-launcher-breathe, not
 *  glow-pulse). `pointerEvents: none` and `zIndex` one below the button keep it purely visual. */
export const launcherGlowStyle: React.CSSProperties = {
  position: 'fixed',
  right: 22,
  bottom: 22,
  zIndex: Z_LAUNCHER - 1,
  width: 50,
  height: 50,
  borderRadius: '50%',
  pointerEvents: 'none',
  background: 'radial-gradient(circle, color-mix(in srgb, var(--brand-bright) 55%, transparent) 0%, transparent 70%)',
  animation: 'nf-assistant-launcher-breathe 3.4s ease-in-out infinite',
};

/** The one-time "try me" bubble (AssistantLauncher's first-session invite). */
export const inviteBubbleStyle: React.CSSProperties = {
  position: 'fixed',
  right: 22,
  bottom: 82,
  zIndex: Z_LAUNCHER,
  maxWidth: 208,
  background: 'var(--panel)',
  border: '1px solid color-mix(in srgb, var(--brand) 25%, var(--line))',
  borderRadius: 10,
  padding: '9px 12px',
  fontSize: 12,
  color: 'var(--txt)',
  boxShadow: '0 10px 30px rgba(0,0,0,.35)',
  animation: 'nf-assistant-invite-in 260ms ease-out',
};

/** "Ask NORA" — the small, plain hover/focus label shown beside the launcher, distinct from the
 *  one-time invite bubble above (which only ever appears once per session). */
export const launcherTooltipStyle: React.CSSProperties = {
  position: 'fixed',
  right: 78,
  bottom: 36,
  zIndex: Z_LAUNCHER,
  background: 'var(--panel)',
  border: '1px solid var(--line)',
  borderRadius: 7,
  padding: '5px 10px',
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--txt)',
  whiteSpace: 'nowrap',
  boxShadow: '0 6px 18px rgba(0,0,0,.3)',
  pointerEvents: 'none',
  animation: 'nf-assistant-invite-in 160ms ease-out',
};

export const panelStyle: React.CSSProperties = {
  position: 'fixed',
  right: 22,
  bottom: 22,
  zIndex: Z_PANEL,
  width: 396,
  // Not a fixed height: the panel should shrink on a short window rather than run off the bottom.
  maxHeight: 'min(620px, calc(var(--app-height, 100dvh) - 96px))',
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--panel)',
  border: '1px solid var(--line)',
  borderRadius: 12,
  boxShadow: '0 24px 64px rgba(0,0,0,.55)',
  overflow: 'hidden',
  // The panel fully mounts/unmounts on open/close (see AssistantPanel) rather than toggling a
  // `hidden` prop, so this plays fresh on every open with no key trick needed.
  animation: 'nf-assistant-panel-in 240ms cubic-bezier(.16,1,.3,1)',
};

export const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  padding: '12px 14px',
  borderBottom: '1px solid var(--line)',
  background: 'var(--raised)',
  flexShrink: 0,
};

/** Outer wrapper for the header badge — sized to leave a couple of px around the icon circle for
 *  the spinning ring (headerBadgeRingStyle) to show in, plus the pulsing glow behind both. */
export const headerBadgeWrapStyle: React.CSSProperties = {
  position: 'relative',
  width: 30,
  height: 30,
  flexShrink: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
};

/** Soft ambient halo behind the badge, breathing via the same `glow-pulse` keyframe BrandMark's
 *  logo already uses — one shared animation vocabulary for "this is a living AI presence" across
 *  the app, rather than a one-off invented just for this panel. */
export const headerBadgeGlowStyle: React.CSSProperties = {
  position: 'absolute',
  inset: -6,
  borderRadius: '50%',
  background: 'radial-gradient(circle, color-mix(in srgb, var(--brand-bright) 35%, transparent) 0%, transparent 72%)',
  animation: 'glow-pulse 2.4s ease-in-out infinite',
  pointerEvents: 'none',
};

/** A slow (7s), calm conic-gradient ring that rotates behind the icon circle — the one place in
 *  this panel that moves on its own without a user action, so the assistant reads as alert rather
 *  than static chrome. Deliberately not on the always-visible launcher: a ring spinning forever in
 *  the corner of every page would wear out its welcome fast; reserved for while the panel is open. */
export const headerBadgeRingStyle: React.CSSProperties = {
  position: 'absolute',
  inset: -3,
  borderRadius: '50%',
  background: 'conic-gradient(from 0deg, var(--brand-bright), transparent 30%, transparent 70%, var(--brand-bright))',
  animation: 'nf-assistant-ring-spin 7s linear infinite',
  opacity: 0.65,
};

export const headerBadgeStyle: React.CSSProperties = {
  position: 'relative',
  width: 26,
  height: 26,
  borderRadius: '50%',
  flexShrink: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'color-mix(in srgb, var(--brand) 16%, var(--panel))',
  color: 'var(--brand-bright)',
};

/** A faint, non-interactive accent texture behind the transcript — built from `color-mix(var(
 *  --brand), X%, transparent)` rather than a raster image, so it flips correctly between light
 *  and dark Display Mode. Sits behind the message list, never affects text contrast. */
export const transcriptGlowStyle: React.CSSProperties = {
  position: 'absolute',
  inset: 0,
  // Negative, not 0: an absolutely-positioned element with z-index:0 paints ABOVE static in-flow
  // siblings under normal CSS stacking rules (it stops being a flex/flow item once absolute), so
  // 0 here would put this glow on top of the message bubbles instead of behind them. -1 keeps it
  // above the transcript's own solid background but below every real message.
  zIndex: -1,
  pointerEvents: 'none',
  backgroundImage: [
    'radial-gradient(260px circle at 100% 0%, color-mix(in srgb, var(--brand) 14%, transparent) 0%, transparent 72%)',
    'radial-gradient(300px circle at 0% 100%, color-mix(in srgb, var(--brand) 9%, transparent) 0%, transparent 72%)',
    'radial-gradient(circle, color-mix(in srgb, var(--brand) 9%, transparent) 1px, transparent 1.6px)',
  ].join(', '),
  backgroundSize: 'auto, auto, 22px 22px',
  backgroundRepeat: 'no-repeat, no-repeat, repeat',
};

export const transcriptStyle: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  overflowY: 'auto',
  padding: '14px 14px 6px',
  background: 'var(--shell)',
};

/** Per-message entrance — applied to each bubble row's outer div in MessageList so a new turn
 *  (yours or the assistant's) settles in rather than snapping into place. */
export const messageEnterStyle: React.CSSProperties = {
  animation: 'nf-assistant-msg-in 220ms ease-out',
};

export const composerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-end',
  gap: 8,
  padding: 12,
  borderTop: '1px solid var(--line)',
  background: 'var(--panel)',
  flexShrink: 0,
};

export const textareaStyle: React.CSSProperties = {
  flex: 1,
  background: 'var(--shell)',
  border: '1px solid var(--line2)',
  borderRadius: 8,
  padding: '9px 11px',
  color: 'var(--txt)',
  fontSize: 13,
  fontFamily: 'inherit',
  lineHeight: 1.45,
  resize: 'none',
  outline: 'none',
  boxSizing: 'border-box',
  maxHeight: 120,
  transition: 'border-color 180ms ease, box-shadow 180ms ease',
};

export const sendButtonStyle: React.CSSProperties = {
  width: 36,
  height: 36,
  borderRadius: 8,
  border: '1px solid rgba(177,17,22,.3)',
  background: 'var(--brand)',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer',
  flexShrink: 0,
  transition: 'transform 120ms ease, opacity 160ms ease',
};

/** One dot of the "thinking" indicator (see the pending state in MessageList). `delay` offsets
 *  each dot's bounce so they ripple left-to-right instead of bouncing in lockstep. */
export function typingDotStyle(delayMs: number): React.CSSProperties {
  return {
    width: 6,
    height: 6,
    borderRadius: '50%',
    background: 'var(--brand-bright)',
    animation: `nf-assistant-typing-bounce 1.1s ease-in-out ${delayMs}ms infinite`,
  };
}

export const iconButtonStyle: React.CSSProperties = {
  background: 'none',
  border: 'none',
  cursor: 'pointer',
  color: 'var(--txt-dim)',
  padding: 4,
  borderRadius: 4,
  display: 'flex',
  alignItems: 'center',
};

/** Modelled on ReplyBubble in HelpDeskPage — the transcript OneHR already has. */
export function bubbleStyle(isUser: boolean): React.CSSProperties {
  return {
    maxWidth: '88%',
    background: isUser ? 'rgba(177,17,22,.08)' : 'var(--raised)',
    border: `1px solid ${isUser ? 'rgba(177,17,22,.25)' : 'var(--line)'}`,
    borderRadius: 10,
    padding: '10px 12px',
    fontSize: 13,
    color: 'var(--txt)',
    // Assistant prose gets a small, dependency-free treatment (see AssistantText/renderInlineBold
    // in MessageList.tsx) for **bold** and "- " bullet lines only — never a real markdown parser
    // and never dangerouslySetInnerHTML, so there is still no new XSS surface for model output.
    // Structure beyond that still comes from the `steps` array, rendered as a real list.
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  };
}

export const metaTextStyle: React.CSSProperties = {
  fontSize: 10.5,
  color: 'var(--txt-dim)',
  marginTop: 3,
};

/** Active-state styling shared by the thumbs-up/down rate buttons and the copy button in
 *  MessageList, split out so both stay visually identical. */
export function rateButtonStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? 'rgba(177,17,22,.12)' : 'none',
    border: `1px solid ${active ? 'rgba(177,17,22,.30)' : 'transparent'}`,
    borderRadius: 5,
    padding: '3px 5px',
    color: active ? 'var(--txt)' : 'var(--txt-dim)',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
  };
}

export const navActionStyle: React.CSSProperties = {
  marginTop: 10,
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  background: 'rgba(177,17,22,.10)',
  border: '1px solid rgba(177,17,22,.30)',
  borderRadius: 7,
  padding: '6px 11px',
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--txt)',
  cursor: 'pointer',
};

export const errorBannerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 12px',
  background: 'rgba(239,68,68,.10)',
  borderTop: '1px solid rgba(239,68,68,.28)',
  color: 'var(--risk)',
  fontSize: 12,
  flexShrink: 0,
};
