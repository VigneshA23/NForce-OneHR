import { useEffect, useMemo, useRef, useState } from 'react';
import { GitBranch, Search, X, Users, ChevronDown, ChevronRight } from 'lucide-react';
import { hierarchyApi, type HierarchyNode } from '../api/hierarchy';
import { useAuthStore } from '../store/authStore';

/* ── Types ─────────────────────────────────────────── */
interface TreeNode extends HierarchyNode {
  children: TreeNode[];
}

/* ── Tree building ─────────────────────────────────── */
function buildTree(nodes: HierarchyNode[]): TreeNode[] {
  const map = new Map<string, TreeNode>();
  nodes.forEach(n => map.set(n.userId, { ...n, children: [] }));
  const roots: TreeNode[] = [];
  map.forEach(node => {
    if (node.managerId && map.has(node.managerId)) {
      map.get(node.managerId)!.children.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

/* Find all ancestor userIds for a given userId (for search expansion) */
function findAncestors(roots: TreeNode[], targetId: string): Set<string> {
  const result = new Set<string>();
  function walk(node: TreeNode, path: string[]): boolean {
    if (node.userId === targetId) {
      path.forEach(id => result.add(id));
      return true;
    }
    for (const child of node.children) {
      if (walk(child, [...path, node.userId])) return true;
    }
    return false;
  }
  for (const root of roots) walk(root, []);
  return result;
}

/* Find the subtree rooted at a given userId */
function findSubtree(roots: TreeNode[], targetId: string): TreeNode | null {
  function walk(node: TreeNode): TreeNode | null {
    if (node.userId === targetId) return node;
    for (const child of node.children) {
      const found = walk(child);
      if (found) return found;
    }
    return null;
  }
  for (const root of roots) {
    const found = walk(root);
    if (found) return found;
  }
  return null;
}

/* Collect all matching userIds given a search query */
function searchNodes(roots: TreeNode[], q: string): Set<string> {
  const matches = new Set<string>();
  function walk(node: TreeNode) {
    const haystack = `${node.fullName} ${node.designationName ?? ''} ${node.departmentName ?? ''}`.toLowerCase();
    if (haystack.includes(q)) matches.add(node.userId);
    node.children.forEach(walk);
  }
  roots.forEach(walk);
  return matches;
}

/* ── Initials / Avatar ─────────────────────────────── */
const DEPT_COLORS: Record<string, { bg: string; fg: string }> = {
  Engineering: { bg: 'rgba(76,141,214,.18)', fg: '#4C8DD6' },
  Sales:       { bg: 'rgba(224,169,59,.18)', fg: '#E0A93B' },
  HR:          { bg: 'rgba(47,182,124,.18)', fg: '#2FB67C' },
  Marketing:   { bg: 'rgba(177,17,22,.18)',  fg: '#E4373D' },
  Finance:     { bg: 'rgba(107,114,128,.18)', fg: '#9BA1AC' },
  Operations:  { bg: 'rgba(139,92,246,.18)', fg: '#8B5CF6' },
};
const DEFAULT_COLOR = { bg: 'rgba(177,17,22,.18)', fg: '#E4373D' };

function deptColor(dept: string | null) {
  if (!dept) return DEFAULT_COLOR;
  const key = Object.keys(DEPT_COLORS).find(k => dept.toLowerCase().includes(k.toLowerCase()));
  return key ? DEPT_COLORS[key] : DEFAULT_COLOR;
}

function Initials({ name, dept, size = 38, isMe = false }: { name: string; dept: string | null; size?: number; isMe?: boolean }) {
  const c = isMe ? { bg: 'rgba(177,17,22,.28)', fg: '#E4373D' } : deptColor(dept);
  const letters = name.split(' ').map(w => w[0] ?? '').join('').slice(0, 2).toUpperCase();
  return (
    <div style={{
      width: size, height: size, borderRadius: '50%',
      background: isMe ? '#B11116' : c.bg,
      color: isMe ? '#fff' : c.fg,
      display: 'grid', placeItems: 'center',
      fontSize: size * 0.34, fontWeight: 700, flexShrink: 0,
      fontFamily: '"Space Grotesk", sans-serif',
      border: isMe ? '2px solid rgba(228,55,61,.5)' : '1.5px solid transparent',
      boxSizing: 'border-box',
    }}>
      {letters}
    </div>
  );
}

/* ── Detail panel ──────────────────────────────────── */
function DetailPanel({ node, onClose, onViewSubtree }: {
  node: TreeNode;
  onClose: () => void;
  onViewSubtree: (id: string) => void;
}) {
  return (
    <div style={{
      position: 'fixed', top: 0, right: 0, bottom: 0, width: 320,
      background: 'var(--panel)', borderLeft: '1px solid var(--line)',
      boxShadow: '-8px 0 32px rgba(0,0,0,.35)', zIndex: 300,
      display: 'flex', flexDirection: 'column',
    }}>
      <div style={{ padding: '16px 18px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          Person Details
        </span>
        <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', display: 'grid', placeItems: 'center', padding: 4, borderRadius: 5 }}>
          <X size={14} />
        </button>
      </div>
      <div style={{ flex: 1, overflowY: 'auto', padding: '20px 18px', display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 13 }}>
          <Initials name={node.fullName} dept={node.departmentName} size={52} />
          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', marginBottom: 2 }}>{node.fullName}</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 5 }}>{node.designationName ?? '—'}</div>
            <span style={{ fontSize: 10, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: node.active ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)', color: node.active ? 'var(--ok)' : 'var(--txt-dim)' }}>
              {node.active ? 'Active' : 'Inactive'}
            </span>
          </div>
        </div>

        {[['Department', node.departmentName], ['Designation', node.designationName], ['Direct Reports', node.children.length > 0 ? `${node.children.length} people` : 'None']] .map(([label, value]) => (
          <div key={label as string}>
            <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 3 }}>{label}</div>
            <div style={{ fontSize: 13, color: value ? 'var(--txt)' : 'var(--txt-dim)' }}>{value || '—'}</div>
          </div>
        ))}

        {node.children.length > 0 && (
          <button
            onClick={() => { onClose(); onViewSubtree(node.userId); }}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px', background: 'rgba(177,17,22,.1)', border: '1px solid rgba(177,17,22,.25)', borderRadius: 7, fontSize: 12.5, fontWeight: 600, color: 'var(--brand)', cursor: 'pointer' }}
          >
            <Users size={13} />
            View {node.fullName.split(' ')[0]}'s sub-tree
          </button>
        )}
      </div>
    </div>
  );
}

/* ── Single org node card ──────────────────────────── */
function NodeCard({
  node, isMe, depth, expandedIds, searchMatches, highlightIds, onToggle, onSelect,
}: {
  node: TreeNode;
  isMe: boolean;
  depth: number;
  expandedIds: Set<string>;
  searchMatches: Set<string>;
  highlightIds: Set<string>;
  onToggle: (id: string) => void;
  onSelect: (node: TreeNode) => void;
}) {
  const nodeRef = useRef<HTMLDivElement>(null);
  const isExpanded = expandedIds.has(node.userId);
  const isHighlighted = highlightIds.has(node.userId);
  const isMatch = searchMatches.has(node.userId);
  const hasChildren = node.children.length > 0;

  useEffect(() => {
    if (isMatch && nodeRef.current) {
      nodeRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [isMatch]);

  return (
    <div ref={nodeRef} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
      <div
        style={{
          background: isMe ? 'rgba(177,17,22,.12)' : isMatch ? 'rgba(76,141,214,.1)' : 'var(--panel)',
          border: isMe ? '2px solid rgba(228,55,61,.5)' : isMatch ? '2px solid rgba(76,141,214,.6)' : isHighlighted ? '1.5px solid var(--brand)' : '1.5px solid var(--line)',
          borderRadius: 10, padding: '11px 14px',
          minWidth: 140, maxWidth: 180,
          textAlign: 'center',
          boxShadow: isMe ? '0 0 16px rgba(177,17,22,.15)' : '0 1px 4px rgba(0,0,0,.15)',
          transition: 'border-color 150ms, box-shadow 150ms',
          position: 'relative',
        }}
      >
        {isMe && (
          <div style={{ position: 'absolute', top: -9, left: '50%', transform: 'translateX(-50%)', background: '#B11116', color: '#fff', fontSize: 9, fontWeight: 700, padding: '1px 7px', borderRadius: 20, whiteSpace: 'nowrap', letterSpacing: '.04em' }}>
            YOU
          </div>
        )}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, cursor: 'pointer' }} onClick={() => onSelect(node)}>
          <Initials name={node.fullName} dept={node.departmentName} size={38} isMe={isMe} />
          <div>
            <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', lineHeight: 1.3, marginBottom: 2 }}>
              {node.fullName}
            </div>
            {node.designationName && (
              <div style={{ fontSize: 10, color: 'var(--txt-mut)', lineHeight: 1.3 }}>{node.designationName}</div>
            )}
            {node.departmentName && (
              <div style={{ marginTop: 4 }}>
                <span style={{ fontSize: 9.5, fontWeight: 600, padding: '1px 6px', borderRadius: 20, background: deptColor(node.departmentName).bg, color: deptColor(node.departmentName).fg }}>
                  {node.departmentName}
                </span>
              </div>
            )}
          </div>
        </div>

        {hasChildren && (
          <button
            onClick={(e) => { e.stopPropagation(); onToggle(node.userId); }}
            style={{ marginTop: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 3, width: '100%', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 5, padding: '3px 0', fontSize: 10, color: 'var(--txt-dim)', cursor: 'pointer' }}
          >
            {isExpanded ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
            {node.children.length} report{node.children.length > 1 ? 's' : ''}
          </button>
        )}
      </div>

      {/* Children */}
      {hasChildren && isExpanded && (
        <div style={{ marginTop: 0, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
          {/* Vertical line down */}
          <div style={{ width: 2, height: 24, background: 'var(--line2)' }} />

          <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', position: 'relative' }}>
            {/* Horizontal connector */}
            {node.children.length > 1 && (
              <div style={{
                position: 'absolute', top: 0, left: 0, right: 0, height: 2, background: 'var(--line2)',
              }} />
            )}
            {node.children.map(child => (
              <div key={child.userId} style={{ paddingTop: node.children.length > 1 ? 0 : 0, display: 'flex', flexDirection: 'column', alignItems: 'center', position: 'relative' }}>
                {/* Vertical line from bar to child */}
                <div style={{ width: 2, height: 22, background: 'var(--line2)', marginBottom: 0 }} />
                <NodeCard
                  node={child}
                  isMe={false}
                  depth={depth + 1}
                  expandedIds={expandedIds}
                  searchMatches={searchMatches}
                  highlightIds={highlightIds}
                  onToggle={onToggle}
                  onSelect={onSelect}
                />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/* ── Main page ─────────────────────────────────────── */
export default function HierarchyPage() {
  const token     = useAuthStore(s => s.token) ?? '';
  const [nodes, setNodes]   = useState<HierarchyNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]   = useState('');
  const [search, setSearch] = useState('');
  const [viewMode, setViewMode] = useState<'company' | 'myteam'>('company');
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<TreeNode | null>(null);
  const [myTeamRoot, setMyTeamRoot] = useState<string | null>(null);
  const [currentUserId] = useState<string | null>(null);

  useEffect(() => {
    hierarchyApi.list(token)
      .then(data => {
        setNodes(data);
        // Default-expand first 2 levels
        const roots = buildTree(data);
        const toExpand = new Set<string>();
        function expand(node: TreeNode, depth: number) {
          if (depth < 2 && node.children.length > 0) {
            toExpand.add(node.userId);
            node.children.forEach(c => expand(c, depth + 1));
          }
        }
        roots.forEach(r => expand(r, 0));
        setExpandedIds(toExpand);
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load hierarchy'))
      .finally(() => setLoading(false));
  }, [token]);

  // Attempt to identify current user's node by email matching — hierarchy doesn't carry email,
  // so we identify by matching the auth store email against fullName (approximate). Backend can
  // be enhanced in a future slice to include userId in the JWT claim and return it with hierarchy.
  // For now we tag "You" using the user's employeeCode or just skip if ambiguous.
  // Better: the hierarchy endpoint should return the current user's userId in a top-level field.
  // We'll leave currentUserId as null for now — the "You" badge won't show until userId is in JWT.

  const allRoots = useMemo(() => buildTree(nodes), [nodes]);

  const displayRoots: TreeNode[] = useMemo(() => {
    if (viewMode === 'company') return allRoots;
    if (myTeamRoot) {
      const sub = findSubtree(allRoots, myTeamRoot);
      return sub ? [sub] : allRoots;
    }
    return allRoots;
  }, [viewMode, allRoots, myTeamRoot]);

  const searchQ = search.trim().toLowerCase();

  const searchMatches = useMemo(() => {
    if (!searchQ) return new Set<string>();
    return searchNodes(allRoots, searchQ);
  }, [allRoots, searchQ]);

  // Auto-expand ancestors of search matches
  useEffect(() => {
    if (!searchQ || searchMatches.size === 0) return;
    const toExpand = new Set(expandedIds);
    searchMatches.forEach(matchId => {
      const ancestors = findAncestors(allRoots, matchId);
      ancestors.forEach(id => toExpand.add(id));
      toExpand.add(matchId);
    });
    setExpandedIds(toExpand);
  }, [searchQ, searchMatches]);

  function toggleNode(id: string) {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  function handleViewSubtree(userId: string) {
    setMyTeamRoot(userId);
    setViewMode('myteam');
  }

  function expandAll() {
    const all = new Set<string>();
    function collect(node: TreeNode) { all.add(node.userId); node.children.forEach(collect); }
    allRoots.forEach(collect);
    setExpandedIds(all);
  }

  function collapseAll() {
    setExpandedIds(new Set());
  }

  const SELECT: React.CSSProperties = {
    background: 'var(--raised)', border: '1px solid var(--line2)',
    borderRadius: 7, padding: '7px 12px', fontSize: 12.5,
    color: 'var(--txt)', outline: 'none', cursor: 'pointer',
  };

  const TOGGLE_BTN = (active: boolean): React.CSSProperties => ({
    padding: '6px 14px', borderRadius: 6, fontSize: 12.5, fontWeight: active ? 700 : 500,
    border: active ? '1.5px solid var(--brand)' : '1px solid var(--line2)',
    background: active ? 'rgba(177,17,22,.1)' : 'var(--raised)',
    color: active ? 'var(--brand)' : 'var(--txt-mut)',
    cursor: 'pointer',
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Header */}
      <div>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
          Org Hierarchy
        </h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>
          {loading ? 'Loading…' : `${nodes.length} people · Click a node to see details · Click report count to expand/collapse`}
        </p>
      </div>

      {/* Controls */}
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
        {/* Search */}
        <div style={{ position: 'relative', flex: '1 1 220px', minWidth: 180 }}>
          <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by name, designation, department…"
            style={{ ...SELECT, paddingLeft: 30, width: '100%', boxSizing: 'border-box' }}
          />
        </div>

        {/* View toggle */}
        <div style={{ display: 'flex', gap: 4 }}>
          <button style={TOGGLE_BTN(viewMode === 'company')} onClick={() => setViewMode('company')}>
            Company-wide
          </button>
          <button style={TOGGLE_BTN(viewMode === 'myteam')} onClick={() => setViewMode('myteam')}>
            My Team
          </button>
        </div>

        <button onClick={expandAll} style={{ ...SELECT, fontSize: 11.5 }}>Expand all</button>
        <button onClick={collapseAll} style={{ ...SELECT, fontSize: 11.5 }}>Collapse all</button>
      </div>

      {/* Tree */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '32px 24px', overflowX: 'auto', minHeight: 300 }}>
        {loading ? (
          <div style={{ textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13, padding: '48px 0' }}>Loading hierarchy…</div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: '48px 0' }}>
            <div style={{ fontSize: 13, color: 'var(--risk)', marginBottom: 8 }}>{error}</div>
            <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Check that the backend is running and manager assignments exist.</div>
          </div>
        ) : displayRoots.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '48px 0' }}>
            <GitBranch size={28} style={{ color: 'var(--line2)', margin: '0 auto 10px', display: 'block' }} />
            <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>No hierarchy data yet.</div>
            <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 4 }}>Assign managers in Employee Master to build this chart.</div>
          </div>
        ) : searchQ && searchMatches.size === 0 ? (
          <div style={{ textAlign: 'center', padding: '48px 0' }}>
            <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>No people match "{search}".</div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 40, alignItems: 'center', minWidth: 'max-content' }}>
            {displayRoots.map(root => (
              <NodeCard
                key={root.userId}
                node={root}
                isMe={root.userId === currentUserId}
                depth={0}
                expandedIds={expandedIds}
                searchMatches={searchMatches}
                highlightIds={searchMatches.size > 0 ? findAncestors(allRoots, [...searchMatches][0] ?? '') : new Set()}
                onToggle={toggleNode}
                onSelect={setSelected}
              />
            ))}
          </div>
        )}
      </div>

      {/* Detail panel overlay */}
      {selected && (
        <>
          <div onClick={() => setSelected(null)} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.3)', zIndex: 299 }} />
          <DetailPanel
            node={selected}
            onClose={() => setSelected(null)}
            onViewSubtree={handleViewSubtree}
          />
        </>
      )}
    </div>
  );
}
