import { describe, expect, it } from 'vitest';
import { NAV } from './nav.config';

describe('NAV Policies & Announcements access', () => {
  it('grants Super Admin the same Policies & Announcements nav item as HR Admin', () => {
    const superAdminItem = NAV['Super Admin'].find(i => i.key === 'policies');
    const hrAdminItem = NAV['HR Admin'].find(i => i.key === 'policies');

    expect(superAdminItem).toBeDefined();
    expect(superAdminItem?.path).toBe('/policies');
    expect(superAdminItem?.phase).toBe(hrAdminItem?.phase);
    expect(superAdminItem?.locked).toBe(hrAdminItem?.locked);
  });

  it('leaves other roles unchanged', () => {
    expect(NAV['Manager'].some(i => i.key === 'policies')).toBe(false);
    expect(NAV['Employee'].some(i => i.key === 'policies')).toBe(false);
  });
});
