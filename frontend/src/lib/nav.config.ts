import type { LucideIcon } from 'lucide-react';
import {
  Home, Clock, Calendar, HelpCircle, FileText, Users, GitBranch, Bell, Shield, Package,
} from 'lucide-react';

export type Role = 'Employee' | 'Manager' | 'HR Admin' | 'Super Admin';

export interface NavItem {
  key: string;
  label: string;
  icon: LucideIcon;
  phase: 1 | 2;
  path: string;
}

function item(key: string, label: string, icon: LucideIcon, phase: 1 | 2): NavItem {
  return { key, label, icon, phase, path: `/${key}` };
}

export const NAV: Record<Role, NavItem[]> = {
  Employee: [
    item('dashboard', 'My Dashboard', Home, 1),
    item('notifications', 'Notifications', Bell, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('attendance', 'My Attendance', Clock, 1),
    item('leave', 'Leave & Holidays', Calendar, 1),
    item('requests', 'My Requests', HelpCircle, 2),
    item('assets', 'Assets & Expenses', Package, 1),
    item('performance', 'Performance & Growth', GitBranch, 2),
    item('documents', 'My Documents & Policies', FileText, 2),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
  Manager: [
    item('dashboard', 'Manager Dashboard', Home, 1),
    item('notifications', 'Notifications', Bell, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('attendance', 'Team Attendance', Clock, 1),
    item('leave', 'Team Leave & Holidays', Calendar, 1),
    item('approvals', 'Approval Center', FileText, 1),
    item('performance', 'Team Performance', GitBranch, 2),
    item('assets', 'Team Assets & Expenses', Package, 1),
    item('reports', 'Reports & Analytics', FileText, 2),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
  'HR Admin': [
    item('dashboard', 'HR Dashboard', Home, 1),
    item('notifications', 'Notifications', Bell, 1),
    item('employees', 'Employee Master', Users, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('onboarding', 'Onboarding', Users, 2),
    item('attendance', 'Attendance Administration', Clock, 1),
    item('leave', 'Leave Administration', Calendar, 1),
    item('approvals', 'Approval Center', FileText, 1),
    item('documents', 'Documents & Compliance', FileText, 2),
    item('policies', 'Policies & Announcements', FileText, 2),
    item('organization', 'Organization Structure', GitBranch, 1),
    item('performance', 'Performance & Engagement', GitBranch, 2),
    item('assets', 'Assets & Expenses', Package, 1),
    item('requests', 'HR Service Requests', HelpCircle, 2),
    item('reports', 'Reports & Analytics', FileText, 2),
    item('audit', 'Audit History', Clock, 2),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
  'Super Admin': [
    item('dashboard', 'Admin Dashboard', Home, 1),
    item('notifications', 'Notifications', Bell, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('access', 'User Management', Shield, 1),
    item('attendance', 'Attendance Administration', Clock, 1),
    item('approvals', 'Approval Center', FileText, 1),
    item('assets', 'Assets & Expenses', Package, 1),
    item('workflows', 'Workflow Studio', GitBranch, 2),
    item('masters', 'Organization Masters', FileText, 1),
    item('templates', 'Templates & Notifications', Bell, 2),
    item('integrations', 'Integrations', FileText, 2),
    item('audit', 'Audit & Security', Clock, 2),
    item('featurelab', 'Future Feature Lab', HelpCircle, 2),
    item('reports', 'Reports & Analytics', FileText, 2),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
};

/** Maps the raw DB role on the auth store to the UI role union. */
export function toShellRole(dbRole: string | undefined): Role {
  switch (dbRole) {
    case 'SUPER_ADMIN':  return 'Super Admin';
    case 'HR_ADMIN':     return 'HR Admin';
    case 'MANAGER':      return 'Manager';
    case 'EMPLOYEE':
    case 'DELIVERY':
    case 'FINANCE':
    case 'LEADERSHIP':
    default:             return 'Employee';
  }
}

export const ROLE_LANDING: Record<Role, string> = {
  Employee: '/dashboard',
  Manager: '/dashboard',
  'HR Admin': '/dashboard',
  'Super Admin': '/dashboard',
};
