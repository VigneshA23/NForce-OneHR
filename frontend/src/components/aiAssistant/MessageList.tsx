import { useEffect, useRef, useState } from 'react';
import { ArrowRight, Check, Copy, Sparkles, ThumbsDown, ThumbsUp } from 'lucide-react';
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
  /** Ids of assistant messages that have already played their type-on reveal once. Owned by
   *  AssistantPanel (which outlives this component across opens — see its own doc comment), so a
   *  message you've already seen renders in full immediately on reopen instead of replaying. */
  revealedIds: React.MutableRefObject<Set<string>>;
  /** Called on every character tick of a reveal in progress, so the transcript can keep following
   *  a growing bubble — see AssistantPanel's `followReveal`. */
  onReveal: () => void;
}

export function MessageList({ messages, ratings, onNavigate, onRate, resolveLabel, revealedIds, onReveal }: MessageListProps) {
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
          revealedIds={revealedIds}
          onReveal={onReveal}
        />
      ))}
    </>
  );
}

/** Reveals `text` character-by-character the first time, then instantly for every render after —
 *  the "an answer is materializing" moment that makes the assistant feel alive rather than a
 *  static FAQ lookup. Total duration is capped regardless of answer length, so a long
 *  troubleshooting answer doesn't make anyone wait. Skips straight to the full text when the
 *  viewer has Reduce Animations on, or once this message id is already in `revealedIds`. */
function useTypewriter(
  text: string,
  id: string,
  active: boolean,
  revealedIds: React.MutableRefObject<Set<string>>,
  reduceMotion: boolean,
  onTick: () => void,
) {
  const skip = !active || revealedIds.current.has(id) || reduceMotion;
  const [displayed, setDisplayed] = useState(skip ? text : '');
  const [done, setDone] = useState(skip);

  useEffect(() => {
    if (skip) { setDisplayed(text); setDone(true); return; }
    const totalMs = Math.min(900, Math.max(250, text.length * 10));
    const stepMs = Math.max(6, totalMs / Math.max(text.length, 1));
    let i = 0;
    const timer = setInterval(() => {
      i += 1;
      setDisplayed(text.slice(0, i));
      onTick();
      if (i >= text.length) {
        clearInterval(timer);
        revealedIds.current.add(id);
        setDone(true);
      }
    }, stepMs);
    return () => clearInterval(timer);
    // `active` flipping true (the pending placeholder receiving its real answer, same message id)
    // is exactly what should (re)start this — an already-mounted MessageBubble goes from a pending
    // stub to real content without remounting. Re-running per keystroke elsewhere is what `skip`
    // (checked above, not in these deps) prevents without needing text/reduceMotion/onTick listed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, active]);

  return { displayed, done };
}

function MessageBubble({ message, rating, onNavigate, onRate, resolveLabel, revealedIds, onReveal }: {
  message: AssistantMessageView;
  rating?: 'UP' | 'DOWN';
  onNavigate: (pageId: string) => void;
  onRate: (messageId: string, rating: 'UP' | 'DOWN') => void;
  resolveLabel: (pageId: string) => string | null;
  revealedIds: React.MutableRefObject<Set<string>>;
  onReveal: () => void;
}) {
  const isUser = message.sender === 'USER';
  const { reduceAnimations } = useAccessibilityPrefs();

  // Hooks must run unconditionally, so this always calls useTypewriter — `active` is false for a
  // user message or a still-pending one, and its result is simply unused below in that case.
  const isRevealableAnswer = !isUser && !message.pending && !message.failed;
  const { displayed, done: revealDone } = useTypewriter(
    message.content,
    message.id,
    isRevealableAnswer,
    revealedIds,
    reduceAnimations,
    onReveal,
  );

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

  // Steps/navigation/related/rate-row wait for the type-on reveal to finish, then fade in as one
  // group — the answer "finishes arriving" instead of the extras popping in mid-sentence.
  const extrasVisible = isUser || message.failed || revealDone;
  const extrasStyle: React.CSSProperties = extrasVisible
    ? { animation: 'nf-assistant-msg-in 260ms ease-out' }
    : { opacity: 0, height: 0, overflow: 'hidden' };

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
        {isUser || message.failed ? message.content : displayed}
        {!isUser && !message.failed && !revealDone && (
          // The blinking caret at the writing edge — the one cue that makes clear this is "typing"
          // rather than a slow network render.
          <span aria-hidden="true" style={{ display: 'inline-block', width: 2, height: 13, marginLeft: 1, verticalAlign: 'text-bottom', background: 'var(--brand-bright)', animation: 'nf-assistant-typing-bounce 0.9s ease-in-out infinite' }} />
        )}

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
      {revealDone && !message.failed && <SparkleBurst triggerKey={message.id} reduceMotion={reduceAnimations} />}
    </div>
  );
}

/** A one-shot, four-dot flourish that plays once near the assistant avatar right as an answer
 *  finishes typing itself out, then removes itself — small enough for a professional HR tool,
 *  not a confetti cannon. `triggerKey` re-arms it: a fresh id means a fresh answer, so the effect
 *  plays again; the same id (a re-render from rating/copy clicks) does not replay it. */
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

/** Shown before the first question. Suggestions are static, so they cost nothing to display. */
export function AssistantEmptyState({ onPick }: { onPick: (question: string) => void }) {
  const suggestions = [
    'How do I apply for leave?',
    'How do I fix a missed punch?',
    'What happens after I submit a request?',
  ];

  return (
    <div style={{ padding: '18px 4px', textAlign: 'center' }}>
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
      <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', margin: '10px 0 4px' }}>
        Ask about OneHR
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-dim)', lineHeight: 1.5, marginBottom: 14 }}>
        I can explain how things work and show you where to go. I cannot make changes or act on
        your behalf.
      </div>
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
  );
}
