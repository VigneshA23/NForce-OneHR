import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssistantRateLimitedError, formatRetryEstimate, sendMessage } from './aiAssistant';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

describe('sendMessage error handling', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('parses a 429 with the assistant rate-limit code into AssistantRateLimitedError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(429, {
      message: 'You have reached the AI Assistant usage limit. Please try again shortly.',
      code: 'AI_ASSISTANT_RATE_LIMIT_EXCEEDED',
      lockedUntil: '2026-01-01T00:05:00Z',
    })));

    await expect(sendMessage('token', { message: 'hi' })).rejects.toMatchObject({
      name: 'AssistantRateLimitedError',
      retryAt: '2026-01-01T00:05:00Z',
    });
  });

  it('a 429 without the assistant rate-limit code is a plain Error, not the typed one', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(429, { message: 'Too many requests' })));

    const error = await sendMessage('token', { message: 'hi' }).catch((e) => e);
    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(AssistantRateLimitedError);
  });

  it('a non-429 failure still throws a plain Error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(503, { message: 'unavailable' })));

    await expect(sendMessage('token', { message: 'hi' })).rejects.toThrow('unavailable');
  });
});

describe('formatRetryEstimate', () => {
  it('rounds up to whole minutes so it never reads "0 minutes" while still limited', () => {
    const retryAt = new Date(Date.now() + 59_000).toISOString();
    expect(formatRetryEstimate(retryAt)).toBe('Please try again in about 1 minute.');
  });

  it('pluralises for more than one minute', () => {
    const retryAt = new Date(Date.now() + 3 * 60_000).toISOString();
    expect(formatRetryEstimate(retryAt)).toMatch(/about 3 minutes\.$/);
  });

  it('falls back to a generic message with no retryAt', () => {
    expect(formatRetryEstimate(null)).toBe('Please try again shortly.');
  });

  it('a retryAt already in the past reads as "now"', () => {
    const retryAt = new Date(Date.now() - 5_000).toISOString();
    expect(formatRetryEstimate(retryAt)).toBe('Please try again now.');
  });
});
