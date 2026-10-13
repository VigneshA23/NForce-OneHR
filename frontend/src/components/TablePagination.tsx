// Client-side table pagination footer: "Showing X–Y of N", a rows-per-page select, and
// Prev / numbered / Next buttons. Styled to match the inline pagination on the User
// Management page. Pages are 1-based throughout.

export const PAGE_SIZE_OPTIONS = [10, 25, 50] as const;

// Clamps a requested page into [1, lastPage] — e.g. after a verify/reject removes the last
// row of the final page, the table falls back to the new last page instead of showing empty.
export function clampPage(page: number, total: number, pageSize: number): number {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  return Math.min(Math.max(1, page), totalPages);
}

// The (at most `max`) page numbers to render, centred on the current page where possible.
export function pageWindow(page: number, totalPages: number, max = 5): number[] {
  const count = Math.min(max, totalPages);
  const start = Math.max(1, Math.min(page - Math.floor(max / 2), totalPages - count + 1));
  return Array.from({ length: count }, (_, i) => start + i);
}

export function paginate<T>(rows: T[], page: number, pageSize: number): T[] {
  return rows.slice((page - 1) * pageSize, page * pageSize);
}

const btn: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '6px 10px', fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer', minWidth: 32 };

export function TablePagination({
  page, pageSize, total, onPageChange, onPageSizeChange, noun = 'results',
}: {
  page: number;          // already clamped via clampPage
  pageSize: number;
  total: number;
  onPageChange(page: number): void;
  onPageSizeChange(size: number): void;
  noun?: string;
}) {
  if (total === 0) return null;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const first = (page - 1) * pageSize + 1;
  const last = Math.min(page * pageSize, total);

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 18px', borderTop: '1px solid var(--line)', flexWrap: 'wrap', gap: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
          Showing {first}–{last} of {total} {noun}
        </span>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--txt-dim)' }}>
          Rows per page
          <select value={pageSize} onChange={e => onPageSizeChange(Number(e.target.value))}
            style={{ padding: '4px 8px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 6, color: 'var(--txt)', fontSize: 12, cursor: 'pointer' }}>
            {PAGE_SIZE_OPTIONS.map(n => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
      </div>
      {totalPages > 1 && (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <button onClick={() => onPageChange(page - 1)} disabled={page === 1} aria-label="Previous page"
            style={{ ...btn, padding: '6px 13px', color: page === 1 ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === 1 ? 'not-allowed' : 'pointer' }}>
            ← Prev
          </button>
          {pageWindow(page, totalPages).map(p => (
            <button key={p} onClick={() => onPageChange(p)} aria-current={p === page ? 'page' : undefined}
              style={{ ...btn, background: p === page ? 'var(--brand)' : 'var(--raised)', color: p === page ? '#fff' : 'var(--txt-mut)' }}>
              {p}
            </button>
          ))}
          <button onClick={() => onPageChange(page + 1)} disabled={page === totalPages} aria-label="Next page"
            style={{ ...btn, padding: '6px 13px', color: page === totalPages ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === totalPages ? 'not-allowed' : 'pointer' }}>
            Next →
          </button>
        </div>
      )}
    </div>
  );
}
