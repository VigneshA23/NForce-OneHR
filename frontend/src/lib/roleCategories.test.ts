import { describe, expect, it } from 'vitest';
import { categoriesOf, roleCountsOf, roleOptionsOf } from './roleCategories';

const cat = (code: string, name: string) => ({ code, name });

describe('role categories', () => {
  it('an administrator is only in their own list, never also in Employee', () => {
    const sa = { role: 'SUPER_ADMIN', categories: [cat('SUPER_ADMIN', 'Super Admin')] };
    expect(categoriesOf(sa).map(c => c.code)).toEqual(['SUPER_ADMIN']);
  });

  it('a person with no additional role is an Employee', () => {
    expect(categoriesOf({ role: 'EMPLOYEE', categories: [cat('EMPLOYEE', 'Employee')] }).map(c => c.code)).toEqual(['EMPLOYEE']);
  });

  it('falls back to the primary role for older responses without categories', () => {
    expect(categoriesOf({ role: 'MANAGER' }).map(c => c.code)).toEqual(['MANAGER']);
    expect(categoriesOf({}).map(c => c.code)).toEqual(['EMPLOYEE']);
  });

  it('a custom-role-only user is under that role, not Employee', () => {
    const u = { role: 'EMPLOYEE', categories: [cat('CUSTOM_PAYROLL', 'Payroll')] };
    expect(categoriesOf(u).map(c => c.code)).toEqual(['CUSTOM_PAYROLL']);
  });

  it('counts a multi-role person under each role and never as an Employee', () => {
    const users = [
      { role: 'HR_ADMIN', categories: [cat('HR_ADMIN', 'HR Admin'), cat('MANAGER', 'Manager')] },
      { role: 'EMPLOYEE', categories: [cat('EMPLOYEE', 'Employee')] },
      { role: 'SUPER_ADMIN', categories: [cat('SUPER_ADMIN', 'Super Admin')] },
    ];
    expect(roleCountsOf(users)).toEqual({ HR_ADMIN: 1, MANAGER: 1, EMPLOYEE: 1, SUPER_ADMIN: 1 });
  });

  it('lists built-in roles first, then custom roles by name', () => {
    const users = [
      { categories: [cat('CUSTOM_Z', 'Zed'), cat('CUSTOM_A', 'Alpha')] },
      { categories: [cat('MANAGER', 'Manager')] },
      { categories: [cat('SUPER_ADMIN', 'Super Admin')] },
    ];
    expect(roleOptionsOf(users).map(o => o.code)).toEqual(['SUPER_ADMIN', 'MANAGER', 'CUSTOM_A', 'CUSTOM_Z']);
  });
});
