import { describe, it, expect } from 'vitest';
import { clampPage, pageWindow, paginate } from './TablePagination';

describe('clampPage', () => {
  it('keeps an in-range page', () => expect(clampPage(3, 100, 10)).toBe(3));
  it('falls back to the last page when the list shrinks', () => expect(clampPage(5, 41, 10)).toBe(5));
  it('clamps past the end', () => expect(clampPage(5, 40, 10)).toBe(4));
  it('returns 1 for an empty list', () => expect(clampPage(3, 0, 10)).toBe(1));
  it('never goes below 1', () => expect(clampPage(0, 20, 10)).toBe(1));
});

describe('pageWindow', () => {
  it('shows every page when there are few', () => expect(pageWindow(1, 3)).toEqual([1, 2, 3]));
  it('starts at 1 near the beginning', () => expect(pageWindow(2, 39)).toEqual([1, 2, 3, 4, 5]));
  it('centres on the current page', () => expect(pageWindow(10, 39)).toEqual([8, 9, 10, 11, 12]));
  it('ends at the last page near the end', () => expect(pageWindow(39, 39)).toEqual([35, 36, 37, 38, 39]));
});

describe('paginate', () => {
  const rows = Array.from({ length: 23 }, (_, i) => i);
  it('slices the requested page', () => expect(paginate(rows, 2, 10)).toEqual([10, 11, 12, 13, 14, 15, 16, 17, 18, 19]));
  it('returns a partial last page', () => expect(paginate(rows, 3, 10)).toEqual([20, 21, 22]));
});
