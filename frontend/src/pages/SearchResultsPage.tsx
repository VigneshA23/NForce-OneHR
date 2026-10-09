import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowUpRight } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { searchApi, type SearchResultItem } from '../api/search';

const PAGE_SIZE = 10;

interface ModuleState {
  label: string;
  refineUrl: string | null;
  items: SearchResultItem[];
  page: number;
  totalElements: number;
  totalPages: number;
  loading: boolean;
}

const cardStyle: React.CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, marginBottom: 18, overflow: 'hidden',
};
const sectionHeaderStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 18px', borderBottom: '1px solid var(--line)',
};
const resultRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, width: '100%',
  padding: '12px 18px', border: 'none', borderTop: '1px solid var(--line)', background: 'none', cursor: 'pointer', textAlign: 'left',
};
const pagerBtnStyle = (disabled: boolean): React.CSSProperties => ({
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px',
  fontSize: 12, color: 'var(--txt-mut)', cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.5 : 1,
});

/** GET-equivalent dedicated results page for "See all results" from the header search — same
 * Shell layout as every other page, grouped by module, each with its own independent pagination
 * (see the module list's per-key page state below — one group paginating never touches another's). */
export default function SearchResultsPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const query = (searchParams.get('q') ?? '').trim();

  const [modules, setModules] = useState<Record<string, ModuleState>>({});
  const [moduleOrder, setModuleOrder] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  function loadModulePage(moduleKey: string, page: number) {
    setModules(prev => ({ ...prev, [moduleKey]: { ...prev[moduleKey], loading: true } }));
    searchApi.modulePage(token, moduleKey, query, page, PAGE_SIZE)
      .then(res => {
        setModules(prev => ({
          ...prev,
          [moduleKey]: {
            ...prev[moduleKey],
            items: res.items, page: res.page, totalElements: res.totalElements,
            totalPages: res.totalPages, refineUrl: res.refineUrl, loading: false,
          },
        }));
      })
      .catch(() => setModules(prev => ({ ...prev, [moduleKey]: { ...prev[moduleKey], loading: false } })));
  }

  useEffect(() => {
    if (query.length < 2 || !token) { setModules({}); setModuleOrder([]); return; }
    setLoading(true); setError(false);
    searchApi.preview(token, query)
      .then(res => {
        const order = res.groups.map(g => g.module);
        const initial: Record<string, ModuleState> = {};
        res.groups.forEach(g => {
          initial[g.module] = {
            label: g.label, refineUrl: g.refineUrl, items: g.items, page: 0,
            totalElements: g.totalCount, totalPages: Math.ceil(g.totalCount / PAGE_SIZE), loading: g.totalCount > g.items.length,
          };
        });
        setModuleOrder(order);
        setModules(initial);
        // The preview only carries each group's top 5 — fetch a real first page (size 10) for
        // any group with more than that so this page's pagination/count is fully accurate.
        res.groups.forEach(g => {
          if (g.totalCount > g.items.length) loadModulePage(g.module, 0);
        });
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, token]);

  return (
    <div>
      <h1 style={{ fontSize: 22, fontWeight: 800, marginBottom: 4, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>Search Results</h1>
      <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginBottom: 22 }}>
        {query ? <>Showing results for <strong style={{ color: 'var(--txt)' }}>"{query}"</strong></> : 'Enter a search term to get started.'}
      </p>

      {query.length > 0 && query.length < 2 && (
        <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Enter at least 2 characters to search.</p>
      )}

      {loading && <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Searching…</p>}

      {!loading && error && (
        <p style={{ color: '#ef4444', fontSize: 13 }}>Something went wrong loading search results. Please try again.</p>
      )}

      {!loading && !error && query.length >= 2 && moduleOrder.length === 0 && (
        <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No results found across Employees, Documents, Helpdesk, Help & Guidance, or Announcements.</p>
      )}

      {!loading && !error && moduleOrder.map(moduleKey => {
        const m = modules[moduleKey];
        if (!m) return null;
        return (
          <div key={moduleKey} style={cardStyle}>
            <div style={sectionHeaderStyle}>
              <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
                {m.label} <span style={{ color: 'var(--txt-dim)', fontWeight: 500, fontSize: 13 }}>({m.totalElements})</span>
              </div>
              {m.refineUrl && (
                <button onClick={() => navigate(m.refineUrl!)}
                  style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 12px', fontSize: 12, fontWeight: 600, color: 'var(--txt)', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4 }}>
                  Refine in {m.label} <ArrowUpRight size={13} aria-hidden="true" />
                </button>
              )}
            </div>

            {m.loading && <p style={{ padding: '12px 18px', color: 'var(--txt-dim)', fontSize: 13 }}>Loading…</p>}

            {!m.loading && m.items.map(item => (
              <button key={item.id} onClick={() => navigate(item.detailUrl)} style={resultRowStyle}>
                <div style={{ minWidth: 0 }}>
                  <div style={{ color: 'var(--txt)', fontSize: 13.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{item.title}</div>
                  {item.subtitle && <div style={{ color: 'var(--txt-dim)', fontSize: 12, marginTop: 2 }}>{item.subtitle}</div>}
                </div>
              </button>
            ))}

            {!m.loading && m.totalPages > 1 && (
              <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 12, padding: '12px 0', borderTop: '1px solid var(--line)' }}>
                <button onClick={() => loadModulePage(moduleKey, m.page - 1)} disabled={m.page === 0} style={pagerBtnStyle(m.page === 0)}>← Prev</button>
                <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Page {m.page + 1} of {m.totalPages}</span>
                <button onClick={() => loadModulePage(moduleKey, m.page + 1)} disabled={m.page >= m.totalPages - 1} style={pagerBtnStyle(m.page >= m.totalPages - 1)}>Next →</button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
