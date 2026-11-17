import type { ReactNode } from 'react';

/**
 * Display helpers for HR-authored text (announcements, policy descriptions). The stored text is
 * never changed; it is cleaned when shown: long dashes become a plain hyphen, and Markdown-style
 * **bold** markers are removed (the popup renders them as real bold instead).
 */
export function tidyDashes(s: string): string {
  return s.replace(/\s*[\u2014\u2013]\s*/g, ' - ').replace(/ - (?=\d)/g, '-');
}

/** One line of preview text: no Markdown markers, no long dashes. */
export function plainText(s: string): string {
  return tidyDashes(s).replace(/\*\*(.+?)\*\*/gs, '$1').replace(/__(.+?)__/gs, '$1').replace(/\*\*/g, '');
}

/** Full text for the popup: **bold** shown as bold, long dashes tidied, line breaks kept by the caller's CSS. */
export function richText(s: string): ReactNode[] {
  const t = tidyDashes(s);
  return t.split(/(\*\*.+?\*\*)/gs).map((part, i) =>
    part.startsWith('**') && part.endsWith('**') && part.length > 4
      ? <strong key={i}>{part.slice(2, -2)}</strong>
      : <span key={i}>{part.replace(/\*\*/g, '')}</span>,
  );
}
