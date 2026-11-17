import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules(); });

async function load(buildId: string | undefined) {
  if (buildId !== undefined) vi.stubGlobal('__BUILD_ID__', buildId);
  return import('./versionCheck');
}

describe('version check', () => {
  it('never reports an update in dev', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const m = await load(undefined);
    expect(await m.isNewVersionAvailable()).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reports an update when the server build id differs', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'new123' }) }));
    const m = await load('old456');
    expect(await m.isNewVersionAvailable()).toBe(true);
  });

  it('reports no update when the ids match', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'same1' }) }));
    const m = await load('same1');
    expect(await m.isNewVersionAvailable()).toBe(false);
  });

  it('ignores network errors and non-JSON responses', async () => {
    const m = await load('old456');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    expect(await m.isNewVersionAvailable()).toBe(false);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => { throw new Error('not json'); } }));
    expect(await m.isNewVersionAvailable()).toBe(false);
  });
});
