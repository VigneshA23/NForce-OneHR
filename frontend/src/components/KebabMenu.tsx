import {
  Archive, ArchiveRestore, Check, Copy, Download, Eye, EyeOff, FileText, History, Pencil, Power, PowerOff, Repeat,
  RotateCcw, Send, Trash2, Undo2, UserPlus, X, type LucideIcon,
} from 'lucide-react';
import { ActionDock } from './ActionDock';

export interface KebabItem {
  label: string;
  onClick: () => void;
  danger?: boolean;
  dividerBefore?: boolean;
  // Optional — when absent an icon is picked from the label (see iconFor); an action whose label matches
  // nothing still renders, as a small text button, so every action stays visible and labelled.
  icon?: LucideIcon;
}

// Label keyword -> icon, so every module's row actions look the same without touching each call site.
const ICON_BY_KEYWORD: Array<[RegExp, LucideIcon]> = [
  [/^(delete|remove|discard)/i, Trash2],
  [/^(edit|rename|update|change)/i, Pencil],
  [/^(deactivate|disable|suspend)/i, PowerOff],
  [/^(activate|reactivate|enable)/i, Power],
  [/^(unpublish|hide)/i, EyeOff],
  [/^publish/i, Send],
  [/history/i, History],
  [/^(view|open|show|details)/i, Eye],
  [/^(archive)/i, Archive],
  [/^(restore|unarchive)/i, ArchiveRestore],
  [/^(reassign)/i, Repeat],
  [/^(assign|add)/i, UserPlus],
  [/^(return|withdraw|revoke)/i, Undo2],
  [/^(copy|duplicate|clone)/i, Copy],
  [/^(download|export)/i, Download],
  [/^(approve|accept|verify)/i, Check],
  [/^(reject|decline|cancel)/i, X],
  [/^(reset|retry)/i, RotateCcw],
  [/(document|policy|file)/i, FileText],
];

function iconFor(label: string): LucideIcon | undefined {
  return ICON_BY_KEYWORD.find(([re]) => re.test(label))?.[1];
}

/**
 * Kept under its old name so every existing call site (same `items` shape) now renders the app's one
 * row-actions control, the ActionDock, instead of a 3-dots popup menu. Display only.
 */
export function KebabMenu({ items }: { items: KebabItem[]; minWidth?: number }) {
  if (items.length === 0) return null;
  return (
    <ActionDock
      items={items.map(i => ({ label: i.label, icon: i.icon ?? iconFor(i.label), onClick: i.onClick, danger: i.danger }))}
    />
  );
}
