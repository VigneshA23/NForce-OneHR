import { describe, expect, it } from 'vitest';
import { addRecentSearch, clearRecentSearches, readRecentSearches } from './recentSearches';

function fakeStore() {
  const map = new Map<string, string>();
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => { map.set(k, v); },
    removeItem: (k: string) => { map.delete(k); },
  };
}

describe('recentSearches', () => {
  it('starts empty for a user with no history', () => {
    expect(readRecentSearches('alice@test.com', fakeStore())).toEqual([]);
  });

  it('adds a search to the front of the list', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', 'john', store);
    addRecentSearch('alice@test.com', 'leave policy', store);
    expect(readRecentSearches('alice@test.com', store)).toEqual(['leave policy', 'john']);
  });

  it('deduplicates case-insensitively, promoting the repeated entry back to the front', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', 'john', store);
    addRecentSearch('alice@test.com', 'leave', store);
    addRecentSearch('alice@test.com', 'JOHN', store);
    expect(readRecentSearches('alice@test.com', store)).toEqual(['JOHN', 'leave']);
  });

  it('caps history at 5 entries, dropping the oldest', () => {
    const store = fakeStore();
    ['a', 'b', 'c', 'd', 'e', 'f'].forEach(q => addRecentSearch('alice@test.com', q, store));
    expect(readRecentSearches('alice@test.com', store)).toEqual(['f', 'e', 'd', 'c', 'b']);
  });

  it('ignores blank/whitespace-only queries', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', '   ', store);
    expect(readRecentSearches('alice@test.com', store)).toEqual([]);
  });

  it('scopes history per user — one user never sees another user\'s searches', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', 'alice-search', store);
    addRecentSearch('bob@test.com', 'bob-search', store);
    expect(readRecentSearches('alice@test.com', store)).toEqual(['alice-search']);
    expect(readRecentSearches('bob@test.com', store)).toEqual(['bob-search']);
  });

  it('clearRecentSearches removes only that user\'s history', () => {
    const store = fakeStore();
    addRecentSearch('alice@test.com', 'alice-search', store);
    addRecentSearch('bob@test.com', 'bob-search', store);
    clearRecentSearches('alice@test.com', store);
    expect(readRecentSearches('alice@test.com', store)).toEqual([]);
    expect(readRecentSearches('bob@test.com', store)).toEqual(['bob-search']);
  });

  it('survives a store that throws (private-mode localStorage) by returning empty', () => {
    const throwing = {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
    };
    expect(readRecentSearches('alice@test.com', throwing)).toEqual([]);
    expect(() => addRecentSearch('alice@test.com', 'x', throwing)).not.toThrow();
  });
});
