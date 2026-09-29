import { describe, expect, it } from 'vitest';
import { canNavigateTo, resolvePageTarget } from './pageTargets';
import { NAV, isNavItemDisabled } from '../nav.config';
import type { Role } from '../nav.config';

/**
 * Navigation resolution, per role.
 *
 * The server authorises the pageId before it is ever returned; this is the second check, and the
 * one that keeps the assistant honest about a role's own sidebar.
 */
describe('resolvePageTarget', () => {
  it('resolves a page the role has in its sidebar', () => {
    expect(resolvePageTarget('leave', 'EMPLOYEE')).toEqual({ route: '/leave', label: 'Leave & Holidays' });
  });

  it('uses the label the role actually sees, not a single global one', () => {
    // /requests renders MyRequestsPage for an employee and the HR ticket queue for HR Admin, under
    // different names. Offering "Open My Requests" to an HR Admin would describe a page that is
    // not what they are about to get.
    expect(resolvePageTarget('requests', 'EMPLOYEE')?.label).toBe('My Requests');
    expect(resolvePageTarget('requests', 'HR_ADMIN')?.label).toBe('HR Service Requests');
  });

  it('refuses a page the role does not have', () => {
    // Shell.tsx falls back to `navItems[0]` for an unmatched path and still renders the route, so
    // the frontend is not a safety net here. Refusing is the point.
    expect(resolvePageTarget('access', 'EMPLOYEE')).toBeNull();
    expect(resolvePageTarget('employees', 'EMPLOYEE')).toBeNull();
  });

  it('allows an admin the pages that are genuinely theirs', () => {
    expect(resolvePageTarget('access', 'SUPER_ADMIN')).toEqual({ route: '/access', label: 'User Management' });
  });

  it('refuses every Phase 2 placeholder, for every role', () => {
    // These render a roadmap placeholder rather than the module. "Let me take you there" landing on
    // "Ships in Phase 2" reads as the assistant being wrong.
    const roles: Array<[Role, string]> = [
      ['Employee', 'EMPLOYEE'], ['Manager', 'MANAGER'],
      ['HR Admin', 'HR_ADMIN'], ['Super Admin', 'SUPER_ADMIN'],
    ];
    for (const [uiRole, dbRole] of roles) {
      for (const item of NAV[uiRole]) {
        if (isNavItemDisabled(item)) {
          expect(resolvePageTarget(item.key, dbRole), `${uiRole}/${item.key}`).toBeNull();
        }
      }
    }
  });

  it('resolves every enabled nav item for its own role', () => {
    // A drift guard with teeth: if NAV grows a key whose path stops matching, or the resolution
    // changes shape, this fails rather than the assistant quietly declining to navigate anywhere.
    const roles: Array<[Role, string]> = [
      ['Employee', 'EMPLOYEE'], ['Manager', 'MANAGER'],
      ['HR Admin', 'HR_ADMIN'], ['Super Admin', 'SUPER_ADMIN'],
    ];
    let checked = 0;
    for (const [uiRole, dbRole] of roles) {
      for (const item of NAV[uiRole]) {
        if (isNavItemDisabled(item)) continue;
        expect(resolvePageTarget(item.key, dbRole), `${uiRole}/${item.key}`)
          .toEqual({ route: item.path, label: item.label });
        checked += 1;
      }
    }
    // Guards against a vacuous pass if NAV were ever empty or all-disabled.
    expect(checked).toBeGreaterThan(40);
  });

  it('resolves the pages that exist outside the sidebar', () => {
    expect(resolvePageTarget('profile', 'EMPLOYEE')).toEqual({ route: '/profile', label: 'My Profile' });
    expect(resolvePageTarget('notifications', 'MANAGER')).toEqual({ route: '/notifications', label: 'Notifications' });
  });

  it("resolves My Team's own sections to the same /my-team route with the tab/category it opens on", () => {
    // My Team is one NAV item with client-side ?tab=/?category= state, not separate routes (ONEHR
    // - My Team AI access) - these piggyback on that path rather than inventing a new route.
    expect(resolvePageTarget('my-team-efforts', 'MANAGER')).toEqual({ route: '/my-team?tab=effort', label: 'My Team → Efforts' });
    expect(resolvePageTarget('my-team-negligence', 'HR_ADMIN')).toEqual({ route: '/my-team?tab=negligence', label: 'My Team → Negligence' });
    expect(resolvePageTarget('my-team-penalties', 'HR_ADMIN')).toEqual({ route: '/my-team?tab=penalties', label: 'My Team → Penalties' });
    expect(resolvePageTarget('my-team-assignments', 'MANAGER')?.route).toBe('/my-team?tab=assignments');
    expect(resolvePageTarget('my-team-reports', 'MANAGER')?.route).toBe('/my-team?tab=reports');
    expect(resolvePageTarget('my-team-reports-attendance', 'MANAGER')?.route)
      .toBe('/my-team?tab=reports&category=Attendance%20Reports');
    expect(resolvePageTarget('my-team-reports-punctuality', 'MANAGER')?.route)
      .toBe('/my-team?tab=reports&category=Punctuality%20Reports');
    expect(resolvePageTarget('my-team-reports-negligence', 'MANAGER')?.route)
      .toBe('/my-team?tab=reports&category=Negligence%20Reports');
    expect(resolvePageTarget('my-team-reports-requests', 'MANAGER')?.route)
      .toBe('/my-team?tab=reports&category=Attendance%20Request%20Reports');
  });

  it('refuses anything not in the sidebar or that short list', () => {
    // The allowlist is closed. A model-invented pageId, or a real route that was never meant as an
    // assistant target, resolves to nothing rather than to a guessed `/${pageId}`.
    expect(resolvePageTarget('payroll', 'SUPER_ADMIN')).toBeNull();
    expect(resolvePageTarget('../admin', 'SUPER_ADMIN')).toBeNull();
    expect(resolvePageTarget('', 'EMPLOYEE')).toBeNull();
    expect(resolvePageTarget(null, 'EMPLOYEE')).toBeNull();
  });

  it('treats an unknown role as an employee', () => {
    // toShellRole folds LEADERSHIP, FINANCE and DELIVERY into Employee, and defaults anything it
    // does not recognise the same way. A role string nobody expected must not widen access.
    expect(resolvePageTarget('access', 'LEADERSHIP')).toBeNull();
    expect(resolvePageTarget('access', undefined)).toBeNull();
    expect(resolvePageTarget('leave', 'FINANCE')?.route).toBe('/leave');
  });

  it('canNavigateTo agrees with resolvePageTarget', () => {
    expect(canNavigateTo('leave', 'EMPLOYEE')).toBe(true);
    expect(canNavigateTo('access', 'EMPLOYEE')).toBe(false);
  });
});
