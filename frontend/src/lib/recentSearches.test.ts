import { describe, expect, it } from 'vitest';
import { addRecentSearch, clearRecentSearches, readRecentSearches, type RecentDestination } from './recentSearches';

function fakeStore() {
  const map = new Map<string, string>();
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => { map.set(k, v); },
    removeItem: (k: string) => { map.delete(k); },
  };
}

const john: RecentDestination = { label: 'John Doe', path: '/employees/123' };
const leave: RecentDestination = { label: 'Leave & Holidays', path: '/leave' };

describe('recentSearches', () => {
  it('starts empty for a user with no history', () => {
    expect(readRecentSearches('alice@test.com', fakeStore())).toEqual([]);
  });

  it('adds a destination to the front of the list', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', john, store);
    addRecentSearch('alice@test.com', leave, store);
    expect(readRecentSearches('alice@test.com', store)).toEqual([leave, john]);
  });

  it('deduplicates by path, promoting the repeated destination back to the front even if its label changed', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', john, store);
    addRecentSearch('alice@test.com', leave, store);
    // Same path as `john`, e.g. the employee's display name changed since the last visit.
    addRecentSearch('alice@test.com', { label: 'John A. Doe', path: '/employees/123' }, store);
    expect(readRecentSearches('alice@test.com', store)).toEqual([
      { label: 'John A. Doe', path: '/employees/123' }, leave,
    ]);
  });

  it('caps history at 5 entries, dropping the oldest', () => {
    const store = fakeStore();
    ['a', 'b', 'c', 'd', 'e', 'f'].forEach(id =>
      addRecentSearch('alice@test.com', { label: id, path: `/x/${id}` }, store));
    expect(readRecentSearches('alice@test.com', store).map(d => d.label)).toEqual(['f', 'e', 'd', 'c', 'b']);
  });

  it('ignores a destination with a blank label or path', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', { label: '   ', path: '/leave' }, store);
    addRecentSearch('alice@test.com', { label: 'Leave', path: '  ' }, store);
    expect(readRecentSearches('alice@test.com', store)).toEqual([]);
  });

  it('scopes history per user — one user never sees another user\'s destinations', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', john, store);
    addRecentSearch('bob@test.com', leave, store);
    expect(readRecentSearches('alice@test.com', store)).toEqual([john]);
    expect(readRecentSearches('bob@test.com', store)).toEqual([leave]);
  });

  it('clearRecentSearches removes only that user\'s history', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', john, store);
    addRecentSearch('bob@test.com', leave, store);
    clearRecentSearches('alice@test.com', store);
    expect(readRecentSearches('alice@test.com', store)).toEqual([]);
    expect(readRecentSearches('bob@test.com', store)).toEqual([leave]);
  });

  it('survives a store that throws (private-mode localStorage) by returning empty', () => {
    const throwing = {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
    };
    expect(readRecentSearches('alice@test.com', throwing)).toEqual([]);
    expect(() => addRecentSearch('alice@test.com', john, throwing)).not.toThrow();
  });

  it('a pre-existing plain string[] from before destinations were tracked reads back as empty rather than crashing', () => {
    const store = fakeStore();
    store.setItem('onehr.search.recent:alice@test.com', JSON.stringify(['old typed query']));
    expect(readRecentSearches('alice@test.com', store)).toEqual([]);
  });
});
