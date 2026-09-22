import type React from 'react';

/**
 * Style constants for the assistant, in the idiom the rest of OneHR uses: inline styles over CSS
 * custom properties. MUI and Emotion are installed but imported nowhere in this codebase, and
 * `HelpDeskPage.tsx` says it outright — there is no shared component library, every page
 * re-declares what it needs. Building the assistant in a different idiom would make it the one
 * surface that does not follow the theme.
 *
 * Every accent tint below goes through `color-mix(in srgb, var(--brand) X%, ...)` rather than a
 * literal `rgba(177,17,22,X)` — that literal is this app's shipped red hardcoded, so it used to be
 * the one surface that stayed red under every other Theme color. `color-mix` against `var(--brand)`
 * is the same pattern SidebarNav's active-item bar and DashboardPage's stat-tile icons already use.
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
  border: '1px solid color-mix(in srgb, var(--brand-bright) 40%, transparent)',
  background: 'linear-gradient(150deg, var(--brand-bright) 0%, var(--brand) 55%, var(--brand-deep) 100%)',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer',
  boxShadow: '0 10px 28px rgba(0,0,0,.45), 0 0 0 1px color-mix(in srgb, var(--brand-bright) 18%, transparent)',
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

/** "Ask OneHR" — the small, plain hover label (distinct from the one-time invite bubble above,
 *  which only ever appears once per session). Reuses the same entrance keyframe since both are
 *  the same shape of thing: a small label settling in beside the launcher. */
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
  border: '1px solid color-mix(in srgb, var(--brand) 20%, var(--line))',
  borderRadius: 14,
  boxShadow: '0 24px 64px rgba(0,0,0,.5), 0 4px 18px color-mix(in srgb, var(--brand) 12%, transparent)',
  overflow: 'hidden',
};

export const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  padding: '12px 14px',
  borderBottom: '1px solid var(--line)',
  background: 'linear-gradient(135deg, color-mix(in srgb, var(--brand) 16%, var(--raised)) 0%, var(--raised) 65%)',
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

/** A faint, non-interactive accent texture behind the transcript — the same "designed background,
 *  not a blank surface" move as the sidebar's decorative artwork, but deliberately quieter: two
 *  soft corner glows plus a barely-there dot grid, all built from `color-mix(var(--brand), X%,
 *  transparent)` rather than a raster image. That's the key difference from the sidebar version —
 *  the sidebar is always dark, so a fixed PNG works there, but this panel's background flips
 *  between --shell light and dark with the user's Display Mode. Compositing true alpha against
 *  transparent (instead of a color baked over a fixed dark ground) means the browser blends it
 *  against whatever --shell actually is, so it reads equally faint in both without any separate
 *  per-theme values needed. Sits behind the message list, never affects text contrast. */
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

/** Wraps the panel's header/transcript/composer, keyed by AssistantLauncher's `openToken` in
 *  AssistantPanel. The panel itself now stays mounted across close/reopen — see the comment on
 *  AssistantLauncher's `hidden` prop for why — so this inner wrapper is what actually re-mounts on
 *  every open, which is what lets `nf-assistant-panel-in` replay each time instead of only once
 *  on the very first open ever. */
export const panelBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  flex: 1,
  minHeight: 0,
  animation: 'nf-assistant-panel-in 240ms cubic-bezier(.16,1,.3,1)',
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
  border: '1px solid color-mix(in srgb, var(--brand) 30%, transparent)',
  background: 'linear-gradient(150deg, var(--brand-bright) 0%, var(--brand) 100%)',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  cursor: 'pointer',
  flexShrink: 0,
  transition: 'transform 120ms ease, opacity 160ms ease',
};

/** One dot of the "thinking" indicator (see TypingIndicator in MessageList). `delay` offsets each
 *  dot's bounce so they ripple left-to-right instead of bouncing in lockstep. */
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
    background: isUser ? 'color-mix(in srgb, var(--brand) 9%, var(--panel))' : 'var(--raised)',
    border: `1px solid ${isUser ? 'color-mix(in srgb, var(--brand) 25%, transparent)' : 'var(--line)'}`,
    borderRadius: 10,
    padding: '10px 12px',
    fontSize: 13,
    color: 'var(--txt)',
    // Assistant prose is plain text: there is no markdown renderer anywhere in this codebase, and
    // adding one would mean a new dependency and an XSS surface for model output. Structure comes
    // from the `steps` array instead, which is rendered as a real list.
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  };
}

export const metaTextStyle: React.CSSProperties = {
  fontSize: 10.5,
  color: 'var(--txt-dim)',
  marginTop: 3,
};

export const navActionStyle: React.CSSProperties = {
  marginTop: 10,
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  background: 'color-mix(in srgb, var(--brand) 10%, transparent)',
  border: '1px solid color-mix(in srgb, var(--brand) 30%, transparent)',
  borderRadius: 999,
  padding: '6px 12px',
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--txt)',
  cursor: 'pointer',
};

/** Active-state styling for the thumbs-up/down rate buttons in MessageList — split out so the
 *  color-mix accent tint lives with the rest of this file's theme logic. */
export function rateButtonStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? 'color-mix(in srgb, var(--brand) 12%, transparent)' : 'none',
    border: `1px solid ${active ? 'color-mix(in srgb, var(--brand) 30%, transparent)' : 'transparent'}`,
    borderRadius: 5,
    padding: '3px 5px',
    color: active ? 'var(--txt)' : 'var(--txt-dim)',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
  };
}

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
