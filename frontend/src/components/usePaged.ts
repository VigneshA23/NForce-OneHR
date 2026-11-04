import { useEffect, useState } from 'react';
import { clampPage, paginate } from './TablePagination';

// Display-only paging for a list that is already fully loaded and filtered by the caller. Pair it with
// <TablePagination {...pagerProps} /> (the app's one pagination footer: Prev, 1 2 3, Next, rows per page).
// The page resets to 1 whenever `resetKey` values change (e.g. a search or filter), and never goes past
// the last page when rows are removed. It does not change what the list contains.
export function usePaged<T>(rows: T[], resetKey: ReadonlyArray<unknown> = [], initialSize = 10) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(initialSize);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { setPage(1); }, resetKey);
  const current = clampPage(page, rows.length, pageSize);
  return {
    pageRows: paginate(rows, current, pageSize),
    pagerProps: {
      page: current,
      pageSize,
      total: rows.length,
      onPageChange: setPage,
      onPageSizeChange: (n: number) => { setPageSize(n); setPage(1); },
    },
  };
}
