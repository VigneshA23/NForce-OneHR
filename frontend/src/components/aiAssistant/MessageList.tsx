import { ArrowRight, Sparkles, ThumbsDown, ThumbsUp } from 'lucide-react';
import type { AssistantMessageView } from './assistantState';
import { bubbleStyle, metaTextStyle, navActionStyle } from './assistantStyles';

/**
 * The transcript.
 *
 * Modelled on `ReplyBubble` in `HelpDeskPage.tsx` — OneHR already has a message list, and the
 * assistant reading like the Help Desk thread is the point rather than a coincidence.
 */

/** `**bold**` spans within one line, as plain React text/element nodes — never raw HTML. */
function renderInlineBold(text: string): React.ReactNode {
  if (!text.includes('**')) return text;
  const parts = text.split(/(\*\*[^*]+\*\*)/g).filter((part) => part !== '');
  return parts.map((part, index) =>
    part.startsWith('**') && part.endsWith('**') && part.length > 4
      ? <strong key={index}>{part.slice(2, -2)}</strong>
      : <span key={index}>{part}</span>,
  );
}

/**
 * The two markdown shapes the model actually produces: `**bold**` spans, and `"- "`-prefixed
 * bullet lines — the same format the live-data providers already emit (e.g. "- Annual: 12 of 18
 * days remaining"), which the model often echoes back verbatim.
 *
 * Deliberately not a real markdown parser and never `dangerouslySetInnerHTML` — this only ever
 * builds React elements from plain-text pieces, so it adds no XSS surface, just readable output
 * in place of literal asterisks and dashes.
 */
function AssistantText({ text }: { text: string }) {
  return (
    <>
      {text.split('\n').map((line, index) => {
        const trimmed = line.trimStart();
        if (trimmed.startsWith('- ')) {
          return (
            <div key={index} style={{ display: 'flex', gap: 6 }}>
              <span aria-hidden="true" style={{ flexShrink: 0 }}>•</span>
              <span>{renderInlineBold(trimmed.slice(2))}</span>
            </div>
          );
        }
        if (trimmed === '') {
          return <div key={index} style={{ height: 8 }} />;
        }
        return <div key={index}>{renderInlineBold(line)}</div>;
      })}
    </>
  );
}

interface MessageListProps {
  messages: AssistantMessageView[];
  ratings: Record<string, 'UP' | 'DOWN'>;
  onNavigate: (pageId: string) => void;
  onRate: (messageId: string, rating: 'UP' | 'DOWN') => void;
  /** Resolves a pageId to the label this user's sidebar uses, or null if unreachable. */
  resolveLabel: (pageId: string) => string | null;
  /** Sends a related question exactly as if the user had typed and submitted it. */
  onAsk: (question: string) => void;
  /** A question is already in flight — related questions are shown disabled rather than queued. */
  sending: boolean;
}

export function MessageList({ messages, ratings, onNavigate, onRate, resolveLabel, onAsk, sending }: MessageListProps) {
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
          onAsk={onAsk}
          sending={sending}
        />
      ))}
    </>
  );
}

function MessageBubble({ message, rating, onNavigate, onRate, resolveLabel, onAsk, sending }: {
  message: AssistantMessageView;
  rating?: 'UP' | 'DOWN';
  onNavigate: (pageId: string) => void;
  onRate: (messageId: string, rating: 'UP' | 'DOWN') => void;
  resolveLabel: (pageId: string) => string | null;
  onAsk: (question: string) => void;
  sending: boolean;
}) {
  const isUser = message.sender === 'USER';

  if (message.pending) {
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 12 }}>
        <div style={{ ...bubbleStyle(false), color: 'var(--txt-dim)', fontStyle: 'italic' }}>
          Looking through OneHR…
        </div>
      </div>
    );
  }

  // Resolved here rather than trusted from the response: the server authorises the pageId, and
  // this checks the same user's own sidebar can actually reach it. If it cannot, the answer still
  // shows — only the button is dropped.
  const targetLabel = message.navigation ? resolveLabel(message.navigation.pageId) : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: isUser ? 'flex-end' : 'flex-start', marginBottom: 12 }}>
      <div style={message.failed
        ? { ...bubbleStyle(false), borderColor: 'rgba(239,68,68,.35)', color: 'var(--risk)' }
        : bubbleStyle(isUser)}
      >
        {isUser || message.failed ? message.content : <AssistantText text={message.content} />}

        {message.steps && message.steps.length > 0 && (
          <ol style={{ margin: '10px 0 0', paddingLeft: 18, display: 'grid', gap: 5 }}>
            {message.steps.map((step, index) => (
              <li key={index} style={{ fontSize: 12.5, lineHeight: 1.5, color: 'var(--txt-mut)' }}>
                {renderInlineBold(step)}
              </li>
            ))}
          </ol>
        )}

        {message.navigation && targetLabel && (
          <div>
            <button
              type="button"
              onClick={() => onNavigate(message.navigation!.pageId)}
              style={navActionStyle}
            >
              Open {targetLabel} <ArrowRight size={13} />
            </button>
          </div>
        )}

        {message.related && message.related.length > 0 && (
          <div style={{ marginTop: 10 }}>
            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginBottom: 5 }}>Related</div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {message.related.map((item, index) => (
                <button
                  key={index}
                  type="button"
                  onClick={() => onAsk(item.label)}
                  disabled={sending}
                  // Asks the related question exactly as if the user had typed and sent it
                  // themselves - same send() path, same budget, same conversation.
                  aria-label={`Ask: ${item.label}`}
                  style={{
                    background: 'var(--raised)',
                    border: '1px solid var(--line2)',
                    borderRadius: 999,
                    padding: '4px 10px',
                    fontSize: 11.5,
                    color: 'var(--txt-mut)',
                    cursor: sending ? 'default' : 'pointer',
                    opacity: sending ? 0.55 : 1,
                    textAlign: 'left',
                  }}
                >
                  {item.label}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {!isUser && !message.failed && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
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
          {message.confidence === 'LOW' && message.responseType !== 'UNKNOWN' && (
            // Surfaced only when it is low. A confidence badge on every answer trains people to
            // ignore it; one that appears rarely is read.
            <span style={metaTextStyle}>Low confidence — worth checking</span>
          )}
        </div>
      )}
    </div>
  );
}

function RateButton({ active, label, onClick, children }: {
  active: boolean;
  label: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      aria-pressed={active}
      style={{
        background: active ? 'rgba(177,17,22,.12)' : 'none',
        border: `1px solid ${active ? 'rgba(177,17,22,.30)' : 'transparent'}`,
        borderRadius: 5,
        padding: '3px 5px',
        color: active ? 'var(--txt)' : 'var(--txt-dim)',
        cursor: 'pointer',
        display: 'flex',
        alignItems: 'center',
      }}
    >
      {children}
    </button>
  );
}

/**
 * Shown before the first question — every time the panel opens, since it unmounts on close and
 * carries no transcript across reopens. This doubles as NORA's greeting.
 */
export function AssistantEmptyState({ onPick, userName }: {
  onPick: (question: string) => void;
  /** The signed-in user's full name, for "Hi, {first name}". Omitted gracefully if absent. */
  userName?: string;
}) {
  const suggestions = [
    'How do I apply for leave?',
    'How do I fix a missed punch?',
    'What happens after I submit a request?',
  ];
  const firstName = userName?.trim().split(/\s+/)[0];

  return (
    <div style={{ padding: '18px 4px', textAlign: 'center' }}>
      <Sparkles size={22} color="var(--brand-bright)" aria-hidden="true" />
      <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', margin: '10px 0 4px' }}>
        Hi{firstName ? ` ${firstName}` : ''}, I'm NORA
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-dim)', lineHeight: 1.5, marginBottom: 14 }}>
        I can explain how things work and show you where to go. I cannot make changes or act on
        your behalf.
      </div>
      <div style={{ display: 'grid', gap: 6 }}>
        {suggestions.map((question) => (
          <button
            key={question}
            type="button"
            onClick={() => onPick(question)}
            style={{
              background: 'var(--raised)',
              border: '1px solid var(--line)',
              borderRadius: 8,
              padding: '8px 11px',
              fontSize: 12.5,
              color: 'var(--txt-mut)',
              cursor: 'pointer',
              textAlign: 'left',
            }}
          >
            {question}
          </button>
        ))}
      </div>
    </div>
  );
}
