import { ArrowRight, Sparkles, ThumbsDown, ThumbsUp } from 'lucide-react';
import type { AssistantMessageView } from './assistantState';
import { bubbleStyle, metaTextStyle, navActionStyle } from './assistantStyles';

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

function MessageBubble({ message, rating, onNavigate, onRate, resolveLabel }: {
  message: AssistantMessageView;
  rating?: 'UP' | 'DOWN';
  onNavigate: (pageId: string) => void;
  onRate: (messageId: string, rating: 'UP' | 'DOWN') => void;
  resolveLabel: (pageId: string) => string | null;
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
        {message.content}

        {message.steps && message.steps.length > 0 && (
          <ol style={{ margin: '10px 0 0', paddingLeft: 18, display: 'grid', gap: 5 }}>
            {message.steps.map((step, index) => (
              <li key={index} style={{ fontSize: 12.5, lineHeight: 1.5, color: 'var(--txt-mut)' }}>{step}</li>
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
          <div style={{ marginTop: 10, fontSize: 11.5, color: 'var(--txt-dim)' }}>
            Related: {message.related.map((item) => item.label).join(' · ')}
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

/** Shown before the first question. Suggestions are static, so they cost nothing to display. */
export function AssistantEmptyState({ onPick }: { onPick: (question: string) => void }) {
  const suggestions = [
    'How do I apply for leave?',
    'How do I fix a missed punch?',
    'What happens after I submit a request?',
  ];

  return (
    <div style={{ padding: '18px 4px', textAlign: 'center' }}>
      <Sparkles size={22} color="var(--brand-bright)" aria-hidden="true" />
      <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--txt)', margin: '10px 0 4px' }}>
        Ask about OneHR
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
