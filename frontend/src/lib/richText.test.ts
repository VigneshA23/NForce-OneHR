import { describe, expect, it } from 'vitest';
import { plainText, tidyDashes } from './richText';

describe('richText display helpers', () => {
  it('turns long dashes into a plain hyphen', () => {
    expect(tidyDashes('Closed \u2014 Diwali')).toBe('Closed - Diwali');
    expect(tidyDashes('10\u201320 Oct')).toBe('10-20 Oct');
  });
  it('strips bold markers from previews', () => {
    expect(plainText('**Office closed** \u2014 plan ahead')).toBe('Office closed - plan ahead');
    expect(plainText('a ** b')).toBe('a  b');
  });
});
