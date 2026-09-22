import { useEffect, useRef, useState } from 'react';
import { ArrowRight, Bell, Calendar, Check, ClipboardCheck, Clock, Copy, FileText, Package, Sparkles, ThumbsDown, ThumbsUp, User } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { AssistantMessageView } from './assistantState';
import { useAccessibilityPrefs } from '../../lib/accessibilityPrefs';
import { bubbleStyle, messageEnterStyle, metaTextStyle, navActionStyle, rateButtonStyle, typingDotStyle } from './assistantStyles';

/**
 * The transcript.
 *
 * Modelled on `ReplyBubble` in `HelpDeskPage.tsx` — OneHR already has a message list, and the
 * assistant reading like the Help Desk thread is the point rather than a coincidence.
 */

interface MessageListProps {
  messages: AssistantMessageView[];
  ratings: Record<string, 'UP' | 'DOWN'>;
  onNavigate: (pageId: string) => void;
  onRate: (messageId: string, rating: 'UP' | 'DOWN') => void;
  /** Resolves a pageId to the label this user's sidebar uses, or null if unreachable. */
  resolveLabel: (pageId: string) => string | null;
}

export function MessageList({ messages, ratings, onNavigate, onRate, resolveLabel }: MessageListProps) {
  return (
    <>
      {messages.map((message) => (
        <MessageBubble
          key={message.id}
          message={message}
          rating={ratings[message.id]}
          onNavigate={onNavigate}
          onRate={onRate}
          resolveLabel={resolveLabel}
        />
      ))}
    </>
  );
}

/** Turns `**bold**` runs into real `<strong>` nodes — a plain string split, not a markdown parser
 *  or `dangerouslySetInnerHTML`, so there is no new XSS surface: every piece the model produced
 *  still passes through React's own text-node escaping exactly as `message.content` always did. */
function renderInlineBold(text: string): React.ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*)/g);
  if (parts.length === 1) return text;
  return parts.map((part, i) =>
    part.startsWith('**') && part.endsWith('**') && part.length > 4
      ? <strong key={i}>{part.slice(2, -2)}</strong>
      : part,
  );
}

/** Answer prose, lightly structured: a run of consecutive `- `/`• ` lines becomes a real `<ul>`,
 *  everything else stays one `pre-wrap` text block (so blank lines and paragraph breaks inside it
 *  still render exactly as the plain-string version always did). Deliberately not a markdown
 *  renderer — no headings, links or nested lists — just the two patterns the model's answers
 *  actually use, each one a safe React-node transform rather than an HTML string. */
function FormattedAnswer({ text }: { text: string }) {
  const lines = text.split('\n');
  const blocks: Array<{ type: 'text'; content: string } | { type: 'bullets'; items: string[] }> = [];
  let textBuffer: string[] = [];
  let bulletBuffer: string[] = [];

  const flushText = () => { if (textBuffer.length) { blocks.push({ type: 'text', content: textBuffer.join('\n') }); textBuffer = []; } };
  const flushBullets = () => { if (bulletBuffer.length) { blocks.push({ type: 'bullets', items: bulletBuffer }); bulletBuffer = []; } };

  for (const line of lines) {
    const bulletMatch = /^[-•]\s+(.*)/.exec(line.trim());
    if (bulletMatch) { flushText(); bulletBuffer.push(bulletMatch[1]); }
    else { flushBullets(); textBuffer.push(line); }
  }
  flushText();
  flushBullets();

  return (
    <>
      {blocks.map((block, i) => block.type === 'bullets' ? (
        <ul key={i} style={{ margin: '6px 0', paddingLeft: 18, display: 'grid', gap: 3 }}>
          {block.items.map((item, j) => <li key={j} style={{ lineHeight: 1.5 }}>{renderInlineBold(item)}</li>)}
        </ul>
      ) : (
        <span key={i}>{renderInlineBold(block.content)}</span>
      ))}
    </>
  );
}

function MessageBubble({ message, rating, onNavigate, onRate, resolveLabel }: {
  message: AssistantMessageView;
  rating?: 'UP' | 'DOWN';
  onNavigate: (pageId: string) => void;
  onRate: (messageId: string, rating: 'UP' | 'DOWN') => void;
  resolveLabel: (pageId: string) => string | null;
}) {
  const isUser = message.sender === 'USER';
  const { reduceAnimations } = useAccessibilityPrefs();

  if (message.pending) {
    return (
      <div style={{ ...messageEnterStyle, display: 'flex', gap: 8, marginBottom: 12 }}>
        <AssistantAvatar />
        <div style={{ ...bubbleStyle(false), display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ display: 'flex', gap: 3 }} aria-hidden="true">
            <span style={typingDotStyle(0)} />
            <span style={typingDotStyle(150)} />
            <span style={typingDotStyle(300)} />
          </span>
          <span className="sr-only" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0,0,0,0)' }}>
            Looking through OneHR…
          </span>
        </div>
      </div>
    );
  }

  // Resolved here rather than trusted from the response: the server authorises the pageId, and
  // this checks the same user's own sidebar can actually reach it. If it cannot, the answer still
  // shows — only the button is dropped.
  const targetLabel = message.navigation ? resolveLabel(message.navigation.pageId) : null;

  const extrasStyle: React.CSSProperties = { animation: 'nf-assistant-msg-in 260ms ease-out' };

  // Assistant replies get a small avatar to their left, same visual weight as a real chat product
  // — user messages don't, since they already read as "mine" by sitting flush right.
  const bubbleColumn = (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: isUser ? 'flex-end' : 'flex-start', minWidth: 0 }}>
      <div
        style={message.failed
          ? { ...bubbleStyle(false), borderColor: 'rgba(239,68,68,.35)', color: 'var(--risk)' }
          : bubbleStyle(isUser)}
        className={!message.failed && isUser ? 'nf-ai-bubble-user' : undefined}
        onMouseEnter={(e) => { if (!message.failed) (e.currentTarget as HTMLDivElement).style.boxShadow = '0 4px 16px color-mix(in srgb, var(--brand) 10%, transparent)'; }}
        onMouseLeave={(e) => { (e.currentTarget as HTMLDivElement).style.boxShadow = 'none'; }}
      >
        {isUser || message.failed ? message.content : <FormattedAnswer text={message.content} />}

        {message.steps && message.steps.length > 0 && (
          <ol style={{ margin: '10px 0 0', paddingLeft: 18, display: 'grid', gap: 5, ...extrasStyle }}>
            {message.steps.map((step, index) => (
              <li key={index} style={{ fontSize: 12.5, lineHeight: 1.5, color: 'var(--txt-mut)' }}>{step}</li>
            ))}
          </ol>
        )}

        {message.navigation && targetLabel && (
          <div style={extrasStyle}>
            <button
              type="button"
              onClick={() => onNavigate(message.navigation!.pageId)}
              style={navActionStyle}
              className="nf-ai-chip-solid"
            >
              Open {targetLabel} <ArrowRight size={13} />
            </button>
          </div>
        )}

        {message.related && message.related.length > 0 && (
          <div style={{ marginTop: 10, fontSize: 11.5, color: 'var(--txt-dim)', ...extrasStyle }}>
            Related: {message.related.map((item) => item.label).join(' · ')}
          </div>
        )}
      </div>

      {!isUser && !message.failed && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4, ...extrasStyle }}>
          <RateButton
            active={rating === 'UP'}
            label="This answer helped"
            onClick={() => onRate(message.id, 'UP')}
          >
            <ThumbsUp size={12} />
          </RateButton>
          <RateButton
            active={rating === 'DOWN'}
            label="This answer did not help"
            onClick={() => onRate(message.id, 'DOWN')}
          >
            <ThumbsDown size={12} />
          </RateButton>
          <CopyButton text={message.content} />
          {message.confidence === 'LOW' && message.responseType !== 'UNKNOWN' && (
            // Surfaced only when it is low. A confidence badge on every answer trains people to
            // ignore it; one that appears rarely is read.
            <span style={metaTextStyle}>Low confidence — worth checking</span>
          )}
        </div>
      )}
    </div>
  );

  if (isUser) {
    return (
      <div style={{ ...messageEnterStyle, display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
        {bubbleColumn}
      </div>
    );
  }

  return (
    <div style={{ ...messageEnterStyle, display: 'flex', gap: 8, marginBottom: 12, position: 'relative' }}>
      <AssistantAvatar />
      {bubbleColumn}
      {!message.failed && <SparkleBurst triggerKey={message.id} reduceMotion={reduceAnimations} />}
    </div>
  );
}

/** A one-shot, four-dot flourish that plays once near the assistant avatar right as an answer
 *  arrives, then removes itself — small enough for a professional HR tool, not a confetti
 *  cannon. `triggerKey` re-arms it: a fresh id means a fresh answer, so the effect plays again;
 *  the same id (a re-render from rating/copy clicks) does not replay it. */
function SparkleBurst({ triggerKey, reduceMotion }: { triggerKey: string; reduceMotion: boolean }) {
  const [visible, setVisible] = useState(true);
  const playedFor = useRef<string | null>(null);

  useEffect(() => {
    if (reduceMotion || playedFor.current === triggerKey) { setVisible(false); return; }
    playedFor.current = triggerKey;
    setVisible(true);
    const timer = setTimeout(() => setVisible(false), 650);
    return () => clearTimeout(timer);
  }, [triggerKey, reduceMotion]);

  if (!visible) return null;

  const offsets: Array<[number, number]> = [[-14, -10], [12, -14], [-10, 10], [14, 8]];

  return (
    <span aria-hidden="true" style={{ position: 'absolute', left: 11, top: 2, width: 0, height: 0 }}>
      {offsets.map(([sx, sy], index) => (
        <span
          key={index}
          style={{
            position: 'absolute',
            width: 4, height: 4, borderRadius: '50%',
            background: 'var(--brand-bright)',
            ['--sx' as string]: `${sx}px`,
            ['--sy' as string]: `${sy}px`,
            animation: `nf-assistant-sparkle-burst 550ms ease-out ${index * 40}ms both`,
          }}
        />
      ))}
    </span>
  );
}

function RateButton({ active, label, onClick, children }: {
  active: boolean;
  label: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button type="button" onClick={onClick} aria-label={label} aria-pressed={active} style={rateButtonStyle(active)} className="nf-ai-chip">
      {children}
    </button>
  );
}

/** Copies the plain-text answer — handy for pasting a regularization/leave "how to" into a ticket
 *  or a message to a teammate. `navigator.clipboard` can throw (insecure context, denied
 *  permission), so this fails silently rather than surfacing an error banner over an answer the
 *  user can already read and select manually. */
function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch { /* clipboard unavailable — nothing to recover into */ }
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      aria-label={copied ? 'Copied' : 'Copy this answer'}
      aria-pressed={copied}
      style={rateButtonStyle(copied)}
      className="nf-ai-chip"
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
    </button>
  );
}

/** The assistant's small round icon, shown to the left of every reply (not user messages) — the
 *  same accent tint as the panel header's badge, so the two read as the same "assistant" mark. */
function AssistantAvatar() {
  return (
    <div
      aria-hidden="true"
      className="nf-ai-avatar"
      style={{
        width: 22, height: 22, borderRadius: '50%', flexShrink: 0, marginTop: 1,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'color-mix(in srgb, var(--brand) 16%, var(--panel))',
        border: '1px solid color-mix(in srgb, var(--brand-bright) 30%, transparent)',
        color: 'var(--brand-bright)',
      }}
    >
      <Sparkles size={11} />
    </div>
  );
}

function greetingPrefix(): string {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 18) return 'Good afternoon';
  return 'Good evening';
}

const sectionLabelStyle: React.CSSProperties = {
  fontSize: 10.5, fontWeight: 700, color: 'var(--txt-mut)',
  textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 8, textAlign: 'left',
};

/** Direct shortcuts to other modules — distinct from the "popular questions" below, which stay
 *  inside the conversation. Each lists its candidate pageId(s) in preference order (a couple of
 *  roles reach the same idea through a differently-keyed nav item, e.g. HR's "policies" vs an
 *  Employee's "my-documents"); the chip only renders once one of them actually resolves for this
 *  user's role, so nobody sees a shortcut to a page they cannot open. There is deliberately no
 *  Payslip or Benefits chip — OneHR has no such module yet, and a shortcut to nothing is worse
 *  than no shortcut. */
const QUICK_ACTIONS: Array<{ pageIds: string[]; label: string; icon: LucideIcon }> = [
  { pageIds: ['leave'], label: 'Leave', icon: Calendar },
  { pageIds: ['attendance'], label: 'Attendance', icon: Clock },
  { pageIds: ['assets'], label: 'Assets & Expenses', icon: Package },
  { pageIds: ['policies', 'my-documents'], label: 'Policies', icon: FileText },
  { pageIds: ['profile'], label: 'My Profile', icon: User },
];

/** Shown before the first question. Suggestions are static, so they cost nothing to display. */
export function AssistantEmptyState({ onPick, onNavigate, resolveLabel, userName, unreadCount = 0, pendingPolicies = 0 }: {
  onPick: (question: string) => void;
  onNavigate: (pageId: string) => void;
  resolveLabel: (pageId: string) => string | null;
  userName?: string | null;
  /** Both already tracked elsewhere in the app (the topbar bell, the compliance banner) — surfaced
   *  here rather than re-derived, and the row for either is simply omitted at zero rather than
   *  shown empty, per "only show information that actually exists". */
  unreadCount?: number;
  pendingPolicies?: number;
}) {
  const suggestions = [
    'How do I apply for leave?',
    'How do I fix a missed punch?',
    'What happens after I submit a request?',
  ];
  const firstName = userName?.trim().split(/\s+/)[0];

  const resolvedActions = QUICK_ACTIONS
    .map(action => ({ ...action, pageId: action.pageIds.find(id => resolveLabel(id)) }))
    .filter((action): action is typeof action & { pageId: string } => !!action.pageId);

  const forYouItems = [
    unreadCount > 0 && { pageId: 'notifications', icon: Bell, text: `${unreadCount} unread notification${unreadCount === 1 ? '' : 's'}` },
    pendingPolicies > 0 && {
      pageId: resolveLabel('policies') ? 'policies' : 'my-documents',
      icon: ClipboardCheck,
      text: `${pendingPolicies} polic${pendingPolicies === 1 ? 'y' : 'ies'} awaiting acknowledgement`,
    },
  ].filter((item): item is { pageId: string; icon: LucideIcon; text: string } => !!item);

  return (
    <div style={{ padding: '18px 4px 4px', textAlign: 'center' }}>
      <div aria-hidden="true" style={{ position: 'relative', width: 40, height: 40, margin: '0 auto' }}>
        <div
          className="nf-ai-badge-glow"
          style={{
            position: 'absolute', inset: -8, borderRadius: '50%',
            background: 'radial-gradient(circle, color-mix(in srgb, var(--brand-bright) 30%, transparent) 0%, transparent 72%)',
            animation: 'glow-pulse 2.4s ease-in-out infinite',
          }}
        />
        <div
          className="nf-ai-avatar"
          style={{
            position: 'relative', width: 40, height: 40, borderRadius: '50%',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            background: 'color-mix(in srgb, var(--brand) 14%, var(--panel))',
            border: '1px solid color-mix(in srgb, var(--brand-bright) 30%, transparent)',
          }}
        >
          <Sparkles size={19} color="var(--brand-bright)" />
        </div>
      </div>
      <div style={{ fontSize: 14.5, fontWeight: 700, color: 'var(--txt)', margin: '12px 0 2px' }}>
        {greetingPrefix()}{firstName ? `, ${firstName}` : ''} 👋
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-dim)', lineHeight: 1.5, marginBottom: 18 }}>
        How can I help you today?
      </div>

      {resolvedActions.length > 0 && (
        <div style={{ marginBottom: 18 }}>
          <div style={sectionLabelStyle}>Quick Actions</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 7 }}>
            {resolvedActions.map(({ pageId, label, icon: Icon }, index) => (
              <button
                key={pageId}
                type="button"
                onClick={() => onNavigate(pageId)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 6,
                  background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 999,
                  padding: '7px 12px 7px 8px', fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)',
                  cursor: 'pointer', transition: 'border-color 120ms, color 120ms, transform 120ms',
                  animation: `nf-assistant-msg-in 260ms ease-out ${index * 50}ms backwards`,
                }}
                onMouseEnter={(e) => {
                  const el = e.currentTarget as HTMLButtonElement;
                  el.style.borderColor = 'color-mix(in srgb, var(--brand) 40%, transparent)';
                  el.style.color = 'var(--txt)';
                  el.style.transform = 'translateY(-1px)';
                }}
                onMouseLeave={(e) => {
                  const el = e.currentTarget as HTMLButtonElement;
                  el.style.borderColor = 'var(--line)';
                  el.style.color = 'var(--txt-mut)';
                  el.style.transform = 'none';
                }}
              >
                <span style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  width: 20, height: 20, borderRadius: '50%',
                  background: 'color-mix(in srgb, var(--brand) 16%, transparent)', color: 'var(--brand)', flexShrink: 0,
                }}>
                  <Icon size={11} aria-hidden="true" />
                </span>
                {label}
              </button>
            ))}
          </div>
        </div>
      )}

      {forYouItems.length > 0 && (
        <div style={{ marginBottom: 18 }}>
          <div style={sectionLabelStyle}>For You</div>
          <div style={{ display: 'grid', gap: 5 }}>
            {forYouItems.map(({ pageId, icon: Icon, text }) => (
              <button
                key={pageId}
                type="button"
                onClick={() => onNavigate(pageId)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8,
                  padding: '8px 10px', fontSize: 12, fontWeight: 600, color: 'var(--txt)',
                  cursor: 'pointer', textAlign: 'left', transition: 'border-color 120ms, transform 120ms',
                }}
                onMouseEnter={(e) => {
                  const el = e.currentTarget as HTMLButtonElement;
                  el.style.borderColor = 'color-mix(in srgb, var(--brand) 40%, transparent)';
                  el.style.transform = 'translateY(-1px)';
                }}
                onMouseLeave={(e) => {
                  const el = e.currentTarget as HTMLButtonElement;
                  el.style.borderColor = 'var(--line)';
                  el.style.transform = 'none';
                }}
              >
                <Icon size={13} style={{ color: 'var(--brand)', flexShrink: 0 }} aria-hidden="true" />
                {text}
              </button>
            ))}
          </div>
        </div>
      )}

      <div>
        <div style={sectionLabelStyle}>Popular questions</div>
        <div style={{ display: 'grid', gap: 7 }}>
          {suggestions.map((question, index) => (
            <button
              key={question}
              type="button"
              onClick={() => onPick(question)}
              style={{
                background: 'var(--raised)',
                border: '1px solid var(--line)',
                borderRadius: 999,
                padding: '9px 14px',
                fontSize: 12.5,
                color: 'var(--txt-mut)',
                cursor: 'pointer',
                textAlign: 'left',
                transition: 'border-color 120ms, color 120ms, transform 120ms',
                animation: `nf-assistant-msg-in 260ms ease-out ${index * 70}ms backwards`,
              }}
              onMouseEnter={(e) => {
                const el = e.currentTarget as HTMLButtonElement;
                el.style.borderColor = 'color-mix(in srgb, var(--brand) 40%, transparent)';
                el.style.color = 'var(--txt)';
                el.style.transform = 'translateY(-1px)';
              }}
              onMouseLeave={(e) => {
                const el = e.currentTarget as HTMLButtonElement;
                el.style.borderColor = 'var(--line)';
                el.style.color = 'var(--txt-mut)';
                el.style.transform = 'none';
              }}
            >
              {question}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
