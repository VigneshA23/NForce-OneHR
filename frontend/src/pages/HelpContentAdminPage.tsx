import { useEffect, useState } from 'react';
import { Archive, ArchiveRestore, Eye, EyeOff, FilePlus2, Pencil, Plus, Trash2 } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import {
  hrHelpContentApi,
  type HelpContentDetail,
  type HelpContentSummary,
  type HelpContentType,
} from '../api/helpContent';
import { ContentFormModal, StatusChip } from '../components/helpContent/ContentFormModal';

// Same local style-const convention as HelpDeskPage/HelpDeskAdminPage — no shared component
// library in this codebase.
const filterSelect: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', color: 'var(--txt)', fontSize: 12.5, outline: 'none' };
const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const tdStyle: React.CSSProperties = { padding: '11px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const iconBtn: React.CSSProperties = { background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '5px 7px', color: 'var(--txt-mut)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center' };

const TYPE_OPTIONS: HelpContentType[] = ['FAQ', 'QUICK_HELP', 'GUIDE', 'DOCUMENT'];
const TYPE_LABEL: Record<HelpContentType, string> = { FAQ: 'FAQ', QUICK_HELP: 'Quick Help', GUIDE: 'Guide', DOCUMENT: 'Document' };

// NOTE: this page is no longer reachable via navigation — Help & Guidance content management
// moved inline onto HelpDeskPage.tsx (see ContentFormModal usage there). Kept here, still fully
// functional and reusing the same shared ContentFormModal/StatusChip, in case a full-table admin
// view is wanted again later; not deleted per the "don't delete, extract instead" instruction.

export default function HelpContentAdminPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [items, setItems] = useState<HelpContentSummary[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [typeFilter, setTypeFilter] = useState<HelpContentType | ''>('');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<HelpContentDetail | null>(null);

  function load(p = page) {
    setLoading(true);
    hrHelpContentApi.list(token, { type: typeFilter || undefined, search: search || undefined, page: p, size: 20 })
      .then(res => { setItems(res.content); setTotalPages(res.totalPages); setPage(res.number); })
      .finally(() => setLoading(false));
  }
  useEffect(() => { load(0); }, [token, typeFilter, search]);

  async function openEdit(id: string) {
    const detail = await hrHelpContentApi.getOne(id, token);
    setEditing(detail);
    setFormOpen(true);
  }

  async function togglePublished(item: HelpContentSummary) {
    try {
      await (item.published ? hrHelpContentApi.unpublish(item.id, token) : hrHelpContentApi.publish(item.id, token));
      showToast('success', item.published ? 'Unpublished' : 'Published');
      load();
    } catch (err) { showToast('error', err instanceof Error ? err.message : 'Failed to update'); }
  }

  async function toggleActive(item: HelpContentSummary) {
    try {
      await (item.active ? hrHelpContentApi.archive(item.id, token) : hrHelpContentApi.reactivate(item.id, token));
      showToast('success', item.active ? 'Archived' : 'Reactivated');
      load();
    } catch (err) { showToast('error', err instanceof Error ? err.message : 'Failed to update'); }
  }

  async function remove(item: HelpContentSummary) {
    if (!window.confirm(`Delete "${item.title}"? This cannot be undone.`)) return;
    try {
      await hrHelpContentApi.remove(item.id, token);
      showToast('success', 'Deleted');
      load();
    } catch (err) { showToast('error', err instanceof Error ? err.message : 'Failed to delete'); }
  }

  return (
    <div>
      <div style={{ marginBottom: 22, display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 14 }}>
        <div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Help & Guidance Content</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>Manage the FAQs, guides, quick help, and documents employees see on the Help & Guidance page.</p>
        </div>
        <button onClick={() => { setEditing(null); setFormOpen(true); }} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap' }}>
          <Plus size={14} /> New Content
        </button>
      </div>

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 16 }}>
        <input
          placeholder="Search title, description, or body…"
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={{ flex: '1 1 240px', minWidth: 200, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 12px', color: 'var(--txt)', fontSize: 13, outline: 'none' }}
        />
        <select value={typeFilter} onChange={e => setTypeFilter(e.target.value as HelpContentType | '')} style={filterSelect}>
          <option value="">All Types</option>
          {TYPE_OPTIONS.map(t => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
        </select>
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : items.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <FilePlus2 size={28} style={{ color: 'var(--line2)', marginBottom: 10 }} />
            <div style={{ fontSize: 15, color: 'var(--txt-mut)' }}>No content yet.</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)', marginTop: 4 }}>Use "New Content" to publish your first FAQ, guide, or document.</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Title', 'Type', 'Category', 'Status', 'Views', 'Updated', 'Actions'].map(h => <th key={h} style={thStyle}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {items.map(item => (
                  <tr key={item.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600, maxWidth: 260 }}>{item.title}</td>
                    <td style={tdStyle}>{TYPE_LABEL[item.type]}</td>
                    <td style={tdStyle}>{item.category ?? '—'}</td>
                    <td style={tdStyle}>
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        <StatusChip label={item.published ? 'Published' : 'Draft'} tone={item.published ? 'ok' : 'warn'} />
                        {!item.active && <StatusChip label="Archived" tone="dim" />}
                        {item.featured && <StatusChip label="Featured" tone="warn" />}
                      </div>
                    </td>
                    <td style={tdStyle}>{item.viewCount}</td>
                    <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{new Date(item.updatedAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}</td>
                    <td style={tdStyle}>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <button title="Edit" onClick={() => openEdit(item.id)} style={iconBtn}><Pencil size={13} /></button>
                        <button title={item.published ? 'Unpublish' : 'Publish'} onClick={() => togglePublished(item)} style={iconBtn}>
                          {item.published ? <EyeOff size={13} /> : <Eye size={13} />}
                        </button>
                        <button title={item.active ? 'Archive' : 'Reactivate'} onClick={() => toggleActive(item)} style={iconBtn}>
                          {item.active ? <Archive size={13} /> : <ArchiveRestore size={13} />}
                        </button>
                        <button title="Delete" onClick={() => remove(item)} style={{ ...iconBtn, color: 'var(--risk)' }}><Trash2 size={13} /></button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {totalPages > 1 && (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 12, padding: '12px 0', borderTop: '1px solid var(--line)' }}>
            <button onClick={() => load(page - 1)} disabled={page === 0} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)', cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? 0.5 : 1 }}>← Prev</button>
            <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Page {page + 1} of {totalPages}</span>
            <button onClick={() => load(page + 1)} disabled={page >= totalPages - 1} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, color: 'var(--txt-mut)', cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', opacity: page >= totalPages - 1 ? 0.5 : 1 }}>Next →</button>
          </div>
        )}
      </div>

      {formOpen && (
        <ContentFormModal
          editing={editing}
          token={token}
          onClose={() => setFormOpen(false)}
          onSaved={() => load(0)}
        />
      )}
    </div>
  );
}
