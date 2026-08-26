import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { buildRoleNav, findActiveAncestors, type ResolvedNavNode, type ResolvedNavGroup, type Role } from '../lib/nav.config';

interface SidebarNavProps {
  role: Role;
  currentKey: string;
  /** Called when a real (non-placeholder) module link is clicked — lets the shell close the mobile drawer. */
  onNavigate?: () => void;
}

/**
 * Hierarchical sidebar navigation. Renders the same NavItems the flat sidebar
 * always has (via buildRoleNav — role visibility is untouched), grouped into
 * categories/subcategories that expand INLINE, in normal document flow,
 * pushing the rest of the list down. Click-only — there is no hover-driven
 * opening anywhere in this component, at any depth.
 */
export function SidebarNav({ role, currentKey, onNavigate }: SidebarNavProps) {
  const tree = buildRoleNav(role);
  const activeAncestors = findActiveAncestors(tree, currentKey) ?? [];

  // Which group keys are currently expanded. Independent per node (not a single-open-branch
  // chain) — expanding one category doesn't force-collapse a sibling.
  const [openKeys, setOpenKeys] = useState<Set<string>>(() => new Set(activeAncestors));

  // Whenever the active route's ancestor chain changes (route navigation, including direct URL
  // load or browser back/forward), make sure those ancestors are expanded — merged in, so it
  // never collapses categories the user opened by hand elsewhere in the tree.
  useEffect(() => {
    const ancestors = findActiveAncestors(tree, currentKey);
    if (!ancestors || ancestors.length === 0) return;
    setOpenKeys((prev) => {
      const next = new Set(prev);
      let changed = false;
      for (const key of ancestors) {
        if (!next.has(key)) { next.add(key); changed = true; }
      }
      return changed ? next : prev;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentKey]);

  function toggle(key: string) {
    setOpenKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  return (
    <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
      {tree.map((node) => (
        <NavRow
          key={node.type === 'item' ? node.item.key : node.key}
          node={node}
          depth={0}
          openKeys={openKeys}
          activeAncestors={activeAncestors}
          currentKey={currentKey}
          onToggle={toggle}
          onLeafClick={() => onNavigate?.()}
        />
      ))}
    </div>
  );
}

interface NavRowProps {
  node: ResolvedNavNode;
  depth: number;
  openKeys: Set<string>;
  activeAncestors: string[];
  currentKey: string;
  onToggle: (key: string) => void;
  onLeafClick: () => void;
}

/** Dispatches to NavLeaf/NavGroup. */
function NavRow(props: NavRowProps) {
  return props.node.type === 'item'
    ? <NavLeaf {...props} node={props.node} />
    : <NavGroup {...props} node={props.node} />;
}

function NavLeaf({ node, depth, currentKey, onLeafClick }: NavRowProps & { node: Extract<ResolvedNavNode, { type: 'item' }> }) {
  const indent = depth * 12;
  const isActive = node.item.key === currentKey;
  const isPlaceholder = node.item.phase > 1;
  const Icon = node.item.icon;
  return (
    <Link
      to={isPlaceholder ? '#' : node.item.path}
      aria-current={isActive ? 'page' : undefined}
      aria-disabled={isPlaceholder}
      onClick={(e) => { if (isPlaceholder) { e.preventDefault(); return; } onLeafClick(); }}
      className="nf-sidebar-item"
      style={{
        display: 'flex', alignItems: 'center', gap: 9,
        padding: '8px 12px', margin: '1px 8px 1px ' + (8 + indent) + 'px',
        borderRadius: 7, textDecoration: 'none', fontSize: 12.5, position: 'relative',
        cursor: isPlaceholder ? 'default' : 'pointer', opacity: isPlaceholder ? 0.55 : 1,
      }}
    >
      {isActive && (
        <span aria-hidden="true" style={{ position: 'absolute', left: -8, top: 6, bottom: 6, width: 3, background: '#E4373D', borderRadius: '0 3px 3px 0' }} />
      )}
      <Icon size={15} aria-hidden="true" />
      <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{node.item.label}</span>
      {isPlaceholder && (
        <span title={`Ships in Phase ${node.item.phase}`} style={{ fontSize: 8.5, fontWeight: 700, letterSpacing: '.04em', color: '#6B7280', background: '#20242C', padding: '2px 5px', borderRadius: 4 }}>
          P{node.item.phase}
        </span>
      )}
    </Link>
  );
}

function NavGroup({ node: group, depth, openKeys, activeAncestors, currentKey, onToggle, onLeafClick }: NavRowProps & { node: ResolvedNavGroup }) {
  const indent = depth * 12;
  const isOpen = openKeys.has(group.key);
  const isInActivePath = activeAncestors.includes(group.key);
  const Icon = group.icon;

  return (
    <div>
      <button
        type="button"
        onClick={() => onToggle(group.key)}
        aria-expanded={isOpen}
        className="nf-sidebar-item"
        style={{
          display: 'flex', alignItems: 'center', gap: 9, width: '100%', boxSizing: 'border-box',
          padding: '8px 12px', margin: '1px 8px 1px ' + (8 + indent) + 'px', maxWidth: 'calc(100% - ' + (16 + indent) + 'px)',
          borderRadius: 7, fontSize: 12.5, position: 'relative', fontFamily: 'inherit',
          cursor: 'pointer', background: 'none', border: 'none', textAlign: 'left',
          color: isInActivePath ? '#FFFFFF' : '#C8CCD2',
        }}
      >
        {isInActivePath && (
          <span aria-hidden="true" style={{ position: 'absolute', left: -8, top: 6, bottom: 6, width: 3, background: 'rgba(228,55,61,.55)', borderRadius: '0 3px 3px 0' }} />
        )}
        <Icon size={15} aria-hidden="true" />
        <span
          style={{
            flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            fontWeight: depth === 0 ? 600 : 500, letterSpacing: depth === 0 ? '.03em' : undefined,
          }}
        >
          {group.label}
        </span>
        {/* Expand/collapse indicator — rotates in place, no icon swap needed. */}
        <span
          aria-hidden="true"
          style={{
            fontSize: 11, color: '#6B7280', marginLeft: 4, display: 'inline-block',
            transition: 'transform 120ms', transform: isOpen ? 'rotate(90deg)' : 'none',
          }}
        >
          &gt;
        </span>
      </button>

      {isOpen && (
        <div>
          {group.children.map((child) => (
            <NavRow
              key={child.type === 'item' ? child.item.key : child.key}
              node={child}
              depth={depth + 1}
              openKeys={openKeys}
              activeAncestors={activeAncestors}
              currentKey={currentKey}
              onToggle={onToggle}
              onLeafClick={onLeafClick}
            />
          ))}
        </div>
      )}
    </div>
  );
}
