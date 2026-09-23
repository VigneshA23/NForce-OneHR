import { afterEach, describe, expect, it, vi } from 'vitest';
import { onboardingApi } from './onboarding';

function pagedResponse(content: unknown[] = [], totalElements = content.length, totalPages = 1): Response {
  return {
    ok: true,
    status: 200,
    json: async () => ({ content, totalElements, totalPages }),
  } as Response;
}

describe('onboardingApi.queue', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('searches by employee name — status/search/page/size all land in the query string', async () => {
    const fetchMock = vi.fn().mockResolvedValue(pagedResponse());
    vi.stubGlobal('fetch', fetchMock);

    await onboardingApi.queue('IN_PROGRESS', 'Jane Doe', 0, 20, 'token');

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain('status=IN_PROGRESS');
    expect(url).toContain('search=Jane+Doe');
    expect(url).toContain('page=0');
    expect(url).toContain('size=20');
  });

  it('searches by employee code the same way as a name — just a different search value', async () => {
    const fetchMock = vi.fn().mockResolvedValue(pagedResponse());
    vi.stubGlobal('fetch', fetchMock);

    await onboardingApi.queue('COMPLETED', 'E100', 0, 20, 'token');

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain('status=COMPLETED');
    expect(url).toContain('search=E100');
  });

  it('search and pagination combine into the same request (page 2, search active)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(pagedResponse());
    vi.stubGlobal('fetch', fetchMock);

    await onboardingApi.queue('IN_PROGRESS', 'Jane', 2, 20, 'token');

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain('search=Jane');
    expect(url).toContain('page=2');
  });

  it('omits an empty search term instead of sending search=', async () => {
    const fetchMock = vi.fn().mockResolvedValue(pagedResponse());
    vi.stubGlobal('fetch', fetchMock);

    await onboardingApi.queue('COMPLETED', '', 0, 20, 'token');

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).not.toContain('search=');
  });

  it('returns the paged shape (content + totalElements + totalPages)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(pagedResponse([{ checklistId: '1' }], 41, 3)));

    const result = await onboardingApi.queue('IN_PROGRESS', '', 1, 20, 'token');

    expect(result.totalElements).toBe(41);
    expect(result.totalPages).toBe(3);
    expect(result.content).toHaveLength(1);
  });
});

describe('onboardingApi.eligibleEmployees', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('searches the Pending tab by name/code and paginates the same way as the queue endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(pagedResponse());
    vi.stubGlobal('fetch', fetchMock);

    await onboardingApi.eligibleEmployees('E200', 1, 20, 'token');

    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain('/eligible-employees');
    expect(url).toContain('search=E200');
    expect(url).toContain('page=1');
    expect(url).toContain('size=20');
  });
});

describe('onboardingApi.stats', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('hits the dedicated stats endpoint, independent of any list pagination', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        pendingCount: 3, startedCount: 5, completedCount: 8,
        overdueCount: 1, completedThisMonthCount: 2, avgCompletionDays: 4,
      }),
    } as Response);
    vi.stubGlobal('fetch', fetchMock);

    const stats = await onboardingApi.stats('token');

    expect(fetchMock.mock.calls[0][0]).toContain('/api/onboarding/stats');
    expect(stats.startedCount).toBe(5);
  });
});
