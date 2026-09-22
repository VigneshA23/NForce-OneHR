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

export const transcriptStyle: React.CSSProperties = {
  flex: 1,
  overflowY: 'auto',
  padding: '14px 14px 6px',
  background: 'var(--shell)',
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
};

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
