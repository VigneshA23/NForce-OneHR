import type * as XLSX from 'xlsx';

// SheetJS has no autofit — columns default to a fixed narrow width, truncating headers/content
// (names, dates, reasons) until the user manually resizes. Width is derived from the longest of
// the header and each row's value for that column (in characters), padded for readability and
// capped so a long free-text Reason/Remarks value can't blow out the whole sheet.
export function autoSizeColumns(ws: XLSX.WorkSheet, rows: Record<string, unknown>[], headers: string[]) {
  const MIN_WIDTH = 8;
  const MAX_WIDTH = 50;
  const PADDING = 2;
  ws['!cols'] = headers.map(header => {
    let longest = header.length;
    for (const row of rows) {
      const value = row[header];
      if (value == null) continue;
      const len = String(value).length;
      if (len > longest) longest = len;
    }
    return { wch: Math.min(Math.max(longest + PADDING, MIN_WIDTH), MAX_WIDTH) };
  });
}
