import { describe, expect, it } from 'vitest';
import {
  assistantReducer,
  canSend,
  initialAssistantState,
  lastAssistantMessage,
  MAX_MESSAGE_CHARS,
} from './assistantState';
import type { AssistantResponse } from '../../api/aiAssistant';

function answer(overrides: Partial<AssistantResponse> = {}): AssistantResponse {
  return {
    type: 'HOW_TO',
    answer: 'Open Leave & Holidays, then choose Apply for Leave.',
    steps: ['Open Leave & Holidays', 'Choose Apply for Leave'],
    navigation: { pageId: 'leave', label: 'Leave & Holidays' },
    related: [],
    confidence: 'HIGH',
    conversationId: 'conv-1',
    ...overrides,
  };
}

function afterAsking(text = 'how do I apply for leave?') {
  return assistantReducer(initialAssistantState, { type: 'ASK', id: 'q1', pendingId: 'a1', text });
}

describe('assistantReducer', () => {
  it('adds the question and a pending answer together', () => {
    const state = afterAsking();
    expect(state.sending).toBe(true);
    expect(state.messages).toHaveLength(2);
    expect(state.messages[0]).toMatchObject({ sender: 'USER', content: 'how do I apply for leave?' });
    expect(state.messages[1]).toMatchObject({ sender: 'ASSISTANT', pending: true });
  });

  it('replaces the placeholder with the answer, in place', () => {
    const state = assistantReducer(afterAsking(), { type: 'ANSWER', pendingId: 'a1', response: answer() });

    expect(state.sending).toBe(false);
    // Replaced rather than appended: an extra message would leave the typing indicator sitting
    // above the answer forever.
    expect(state.messages).toHaveLength(2);
    expect(state.messages[1]).toMatchObject({ sender: 'ASSISTANT', responseType: 'HOW_TO' });
    // Rebuilt rather than spread, so the pending flag is gone entirely rather than set to false.
    expect(state.messages[1].pending).toBeFalsy();
    expect(state.messages[1].steps).toHaveLength(2);
    expect(state.messages[1].navigation?.pageId).toBe('leave');
  });

  it('adopts the conversation id the server assigned', () => {
    const state = assistantReducer(afterAsking(), { type: 'ANSWER', pendingId: 'a1', response: answer() });
    expect(state.conversationId).toBe('conv-1');
  });

  it('keeps the existing conversation across later turns', () => {
    const first = assistantReducer(afterAsking(), { type: 'ANSWER', pendingId: 'a1', response: answer() });
    const second = assistantReducer(first, { type: 'ASK', id: 'q2', pendingId: 'a2', text: 'and after that?' });
    const third = assistantReducer(second, { type: 'ANSWER', pendingId: 'a2', response: answer() });

    expect(third.conversationId).toBe('conv-1');
    expect(third.messages).toHaveLength(4);
  });

  it('turns the placeholder into the failure rather than dropping it', () => {
    const state = assistantReducer(afterAsking(), { type: 'FAIL', pendingId: 'a1', message: 'Network error' });

    expect(state.sending).toBe(false);
    expect(state.error).toBe('Network error');
    // The user's own question must not be left looking answered.
    expect(state.messages[1]).toMatchObject({ failed: true, pending: false, content: 'Network error' });
  });

  it('clears a stale error when the next question is asked', () => {
    const failed = assistantReducer(afterAsking(), { type: 'FAIL', pendingId: 'a1', message: 'Network error' });
    const retried = assistantReducer(failed, { type: 'ASK', id: 'q2', pendingId: 'a2', text: 'again?' });
    expect(retried.error).toBeNull();
  });

  it('an UNKNOWN answer is a success, not a failure', () => {
    const state = assistantReducer(afterAsking(), {
      type: 'ANSWER',
      pendingId: 'a1',
      response: answer({ type: 'UNKNOWN', steps: [], navigation: null, confidence: 'LOW' }),
    });

    // The server returns UNKNOWN with HTTP 200 on purpose - a controlled decline is an answer, and
    // rendering it as an error would tell the user something broke when nothing did.
    expect(state.error).toBeNull();
    expect(state.messages[1].failed).toBeUndefined();
    expect(state.messages[1].responseType).toBe('UNKNOWN');
  });

  it('clearing empties the transcript but keeps the conversation', () => {
    const answered = assistantReducer(afterAsking(), { type: 'ANSWER', pendingId: 'a1', response: answer() });
    const cleared = assistantReducer(answered, { type: 'CLEAR' });

    expect(cleared.messages).toEqual([]);
    expect(cleared.ratings).toEqual({});
    // Matches the server's clear endpoint, which empties the conversation and keeps its id.
    expect(cleared.conversationId).toBe('conv-1');
  });

  it('restoring a conversation does not resurrect navigation', () => {
    const state = assistantReducer(initialAssistantState, {
      type: 'RESTORE',
      conversationId: 'conv-9',
      messages: [
        { sender: 'USER', content: 'how do I apply for leave?', responseType: null, createdAt: '2026-01-01T10:00:00Z' },
        { sender: 'ASSISTANT', content: 'Open Leave & Holidays.', responseType: 'HOW_TO', createdAt: '2026-01-01T10:00:02Z' },
      ],
    });

    expect(state.conversationId).toBe('conv-9');
    expect(state.messages).toHaveLength(2);
    // Only the prose was stored. Re-offering a destination without the reasoning that produced it
    // would be worse than not offering one.
    expect(state.messages[1].navigation).toBeUndefined();
  });

  it('records a rating per message', () => {
    const answered = assistantReducer(afterAsking(), { type: 'ANSWER', pendingId: 'a1', response: answer() });
    const rated = assistantReducer(answered, { type: 'RATE', id: 'a1', rating: 'DOWN' });
    expect(rated.ratings).toEqual({ a1: 'DOWN' });

    const changed = assistantReducer(rated, { type: 'RATE', id: 'a1', rating: 'UP' });
    expect(changed.ratings).toEqual({ a1: 'UP' });
  });

  it('does not mutate the state it is given', () => {
    const before = afterAsking();
    const snapshot = JSON.stringify(before);
    assistantReducer(before, { type: 'ANSWER', pendingId: 'a1', response: answer() });
    expect(JSON.stringify(before)).toBe(snapshot);
  });
});

describe('canSend', () => {
  it('requires something to send', () => {
    expect(canSend('', false)).toBe(false);
    expect(canSend('   ', false)).toBe(false);
    expect(canSend('hello', false)).toBe(true);
  });

  it('blocks while a question is in flight', () => {
    // Every message costs a real embedding call and a real completion call, and the server rate
    // limits per user per hour. Double-sending spends that budget for nothing.
    expect(canSend('hello', true)).toBe(false);
  });

  it('enforces the same length limit as the server', () => {
    expect(canSend('x'.repeat(MAX_MESSAGE_CHARS), false)).toBe(true);
    expect(canSend('x'.repeat(MAX_MESSAGE_CHARS + 1), false)).toBe(false);
  });
});

describe('lastAssistantMessage', () => {
  it('finds the newest completed answer', () => {
    const state = assistantReducer(afterAsking(), { type: 'ANSWER', pendingId: 'a1', response: answer() });
    expect(lastAssistantMessage(state)?.id).toBe('a1');
  });

  it('ignores a pending or failed turn', () => {
    expect(lastAssistantMessage(afterAsking())).toBeNull();
    const failed = assistantReducer(afterAsking(), { type: 'FAIL', pendingId: 'a1', message: 'nope' });
    expect(lastAssistantMessage(failed)).toBeNull();
  });
});
