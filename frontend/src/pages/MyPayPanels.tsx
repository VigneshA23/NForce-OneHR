import { useEffect, useState, type ReactNode } from 'react';
import { Download, IndianRupee, CalendarDays, Wallet } from 'lucide-react';
import { myPayApi, type SalaryTimeline, type PayslipPreview, type IncomeTax, type PayslipLine } from '../api/myPay';
import { financeAdminApi } from '../api/myTax';
import { ReadField } from './profile/shared';
import { panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, BtnGhost, SectionHead, StatTile } from './payroll/payrollShared';

const grid = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 16 } as const;
const right = { textAlign: 'right' } as const;
const muted = { fontSize: 13, color: 'var(--txt-mut)' } as const;

/** Runs `load` and gives back its result, an error message, or neither while loading. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<{ data?: T; error?: string }>({});
  useEffect(() => {
    let off = false;
    setState({});
    load().then(data => !off && setState({ data }), e => !off && setState({ error: e instanceof Error ? e.message : 'Failed to load' }));
    return () => { off = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

export function Status({ error, children }: { error?: string; children?: ReactNode }) {
  return <div style={muted}>{error ?? children ?? 'Loading…'}</div>;
}

// ── US-F07 My Salary ─────────────────────────────────────────────────────────

export function MySalaryPanel({ token, uid }: { token: string; uid?: string }) {
  const { data, error } = useLoad<SalaryTimeline>(() => (uid ? financeAdminApi(token).salaryTimeline(uid) : myPayApi.salaryTimeline(token)), [token, uid]);
  const [open, setOpen] = useState<string | null>(null);
  if (!data) return <Status error={error} />;
  return (
    <>
      <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
        <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Current Compensation (per year)" value={data.currentCtc != null ? fmtCurrency(data.currentCtc) : 'N/A'} />
        <StatTile tone="var(--info)" icon={<CalendarDays size={20} />} label="Pay Cycle" value={data.payCycle ?? 'N/A'} hint={data.frequency ?? undefined} />
      </div>
      <SectionHead title="Salary Timeline" />
      {data.revisions.length === 0 && <div style={{ ...panelStyle, padding: 24, ...muted }}>No salary revisions yet.</div>}
      {data.revisions.map(r => {
        const earnings = r.components.filter(c => c.type === 'EARNING');
        const isOpen = open === r.id;
        return (
          <div key={r.id} style={{ ...panelStyle, padding: '16px 20px', marginBottom: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
              <div>
                <div style={{ fontWeight: 700, color: 'var(--txt)' }}>
                  Effective {fmtDate(r.effectiveFrom)}
                  {r.current && <span style={{ marginLeft: 10, fontSize: 11, fontWeight: 800, color: 'var(--ok)' }}>CURRENT</span>}
                </div>
                <div style={muted}>{r.structureName}</div>
              </div>
              <div style={{ ...grid, gridTemplateColumns: 'repeat(2, minmax(150px, 1fr))' }}>
                <ReadField label="Regular Salary" value={fmtCurrency(r.regularSalary)} />
                <ReadField label="Total" value={fmtCurrency(r.ctc)} />
              </div>
            </div>
            <div style={{ marginTop: 10 }}>
              <a href="#" onClick={e => { e.preventDefault(); setOpen(isOpen ? null : r.id); }}>{isOpen ? 'Hide' : 'View'} Salary Breakdown</a>
            </div>
            {isOpen && (
              <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse', marginTop: 12 }}>
                <thead><tr>{['Component', 'Monthly', 'Yearly'].map((h, i) => <th key={h} style={{ ...thStyle, ...(i ? right : {}) }}>{h}</th>)}</tr></thead>
                <tbody>
                  {earnings.map(c => (
                    <tr key={c.name}><td style={tdStyle}>{c.name}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(c.monthly)}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(c.annual)}</td></tr>
                  ))}
                  <tr>
                    <td style={{ ...tdStyle, fontWeight: 700 }}>Total</td>
                    <td style={{ ...tdStyle, ...right, fontWeight: 700 }}>{fmtCurrency(earnings.reduce((s, c) => s + c.monthly, 0))}</td>
                    <td style={{ ...tdStyle, ...right, fontWeight: 700 }}>{fmtCurrency(r.regularSalary)}</td>
                  </tr>
                </tbody>
              </table>
            )}
          </div>
        );
      })}
    </>
  );
}

// ── US-F08 Payslips ──────────────────────────────────────────────────────────

const ONES = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten', 'Eleven', 'Twelve', 'Thirteen', 'Fourteen',
  'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen'];
const TENS = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety'];
function below100(n: number) { return n < 20 ? ONES[n] : `${TENS[Math.floor(n / 10)]}${n % 10 ? ' ' + ONES[n % 10] : ''}`; }
function below1000(n: number) {
  return [n >= 100 ? `${ONES[Math.floor(n / 100)]} Hundred` : '', below100(n % 100)].filter(Boolean).join(' ');
}
/** Indian grouping (crore / lakh / thousand), whole rupees plus paise. */
export function rupeesInWords(amount: number): string {
  const rupees = Math.floor(Math.round(amount * 100) / 100);
  const paise = Math.round((amount - rupees) * 100);
  const parts: string[] = [];
  let n = rupees;
  for (const [unit, name] of [[10000000, 'Crore'], [100000, 'Lakh'], [1000, 'Thousand']] as const) {
    const q = Math.floor(n / unit);
    if (q) parts.push(`${below1000(q)} ${name}`);
    n %= unit;
  }
  if (n) parts.push(below1000(n));
  const words = (parts.join(' ') || 'Zero') + ' Rupees' + (paise ? ` and ${below100(paise)} Paise` : '');
  return words + ' Only';
}

function Lines({ title, lines, total }: { title: string; lines: PayslipLine[]; total?: number }) {
  return (
    <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 14 }}>
      <thead><tr>{[title, 'Amount', 'YTD'].map((h, i) => <th key={h} style={{ ...thStyle, ...(i ? right : {}) }}>{h}</th>)}</tr></thead>
      <tbody>
        {lines.map(l => (
          <tr key={l.name}><td style={tdStyle}>{l.name}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(l.amount)}</td>
            <td style={{ ...tdStyle, ...right }}>{l.ytd != null ? fmtCurrency(l.ytd) : '—'}</td></tr>
        ))}
        {lines.length === 0 && <EmptyRow cols={3} msg="None" />}
        {total != null && (
          <tr><td style={{ ...tdStyle, fontWeight: 700 }}>Total</td><td style={{ ...tdStyle, ...right, fontWeight: 700 }}>{fmtCurrency(total)}</td><td style={tdStyle} /></tr>
        )}
      </tbody>
    </table>
  );
}

function PayslipView({ p }: { p: PayslipPreview }) {
  const sum = (l: PayslipLine[]) => l.reduce((s, x) => s + x.amount, 0);
  return (
    <div style={{ ...panelStyle, padding: '20px 24px' }}>
      <div style={{ fontWeight: 800, fontSize: 16, color: 'var(--txt)' }}>{p.companyName ?? 'Payslip'}</div>
      {p.companyAddress && <div style={muted}>{p.companyAddress}</div>}
      <div style={{ ...muted, margin: '4px 0 16px' }}>Payslip for {p.periodLabel} ({fmtDate(p.cycleStart)} – {fmtDate(p.cycleEnd)})</div>
      <div style={{ ...grid, marginBottom: 16 }}>
        <ReadField label="Employee" value={`${p.employeeName} (${p.employeeCode})`} />
        <ReadField label="Joining Date" value={p.joiningDate ? fmtDate(p.joiningDate) : null} />
        <ReadField label="Department" value={p.department} />
        <ReadField label="Designation" value={p.designation} />
        <ReadField label="Payment Mode" value={p.paymentMode} />
        <ReadField label="UAN" value={p.uan} />
        <ReadField label="PAN" value={p.pan} />
      </div>
      <div style={{ ...grid, marginBottom: 16 }}>
        <ReadField label="Total Working Days" value={String(p.totalDays)} />
        <ReadField label="LOP Days" value={String(p.lopDays)} />
        <ReadField label="Days Payable" value={String(p.daysPayable)} />
      </div>
      <Lines title="Earnings (A)" lines={p.earnings} total={p.totalEarnings} />
      {p.contributions.length > 0 && <Lines title="Contributions" lines={p.contributions} total={sum(p.contributions)} />}
      <Lines title="Taxes & Deductions (C)" lines={p.deductions} total={p.totalDeductions} />
      <div style={{ fontWeight: 800, color: 'var(--txt)' }}>Net Salary Payable: {fmtCurrency(p.netPay)}</div>
      <div style={muted}>{rupeesInWords(p.netPay)}</div>
    </div>
  );
}

export function MyPayslipsPanel({ token, uid, periods, selected, downloading, onSelect, onDownload }: {
  uid?: string;
  token: string; periods: string[]; selected: string | null; downloading: string | null;
  onSelect: (period: string) => void; onDownload: (period: string) => void;
}) {
  const years = [...new Set(periods.map(p => p.slice(0, 4)))];
  const [year, setYear] = useState(selected?.slice(0, 4) ?? years[0] ?? '');
  const months = periods.filter(p => p.startsWith(year));
  const active = selected && months.includes(selected) ? selected : months[0] ?? null;
  const { data, error } = useLoad<PayslipPreview | null>(() => (active ? (uid ? financeAdminApi(token).payslipPreview(uid, active) : myPayApi.payslipPreview(active, token)) : Promise.resolve(null)), [active, token, uid]);

  if (periods.length === 0) return <div style={{ ...panelStyle, padding: 24, ...muted }}>No payslips available yet.</div>;
  return (
    <>
      <SectionHead title="Payslips" action={
        <select value={year} onChange={e => setYear(e.target.value)} aria-label="Year" style={{ padding: '8px 10px', borderRadius: 6 }}>
          {years.map(y => <option key={y} value={y}>{y}</option>)}
        </select>} />
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(150px, 220px) 1fr', gap: 18, alignItems: 'start' }}>
        <div style={panelStyle}>
          {months.map(p => (
            <button key={p} onClick={() => onSelect(p)} style={{
              display: 'block', width: '100%', textAlign: 'left', padding: '12px 16px', border: 0, borderBottom: '1px solid var(--line)',
              background: p === active ? 'var(--brand-soft, rgba(99,102,241,.1))' : 'transparent', color: 'var(--txt)', cursor: 'pointer',
            }}>
              {new Date(Number(p.slice(0, 4)), Number(p.slice(5)) - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' })}
            </button>
          ))}
        </div>
        <div>
          {active && !uid && (
            <div style={{ marginBottom: 12 }}>
              <BtnGhost onClick={() => onDownload(active)} disabled={downloading === active}>
                <Download size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />
                {downloading === active ? 'Downloading…' : 'Download Payslip'}
              </BtnGhost>
            </div>
          )}
          {data ? <PayslipView p={data} /> : <Status error={error} />}
        </div>
      </div>
    </>
  );
}

// ── US-F09 Income Tax ────────────────────────────────────────────────────────

export function MyIncomeTaxPanel({ token, uid }: { token: string; uid?: string }) {
  const [fy, setFy] = useState<number | undefined>();
  const { data, error } = useLoad<IncomeTax>(() => (uid ? financeAdminApi(token).incomeTax(uid, fy) : myPayApi.incomeTax(token, fy)), [token, fy, uid]);
  if (!data) return <Status error={error} />;
  const t = data.tax;
  const cur = data.fyStartYear;
  const now = new Date();
  const realFy = now.getMonth() >= 3 ? now.getFullYear() : now.getFullYear() - 1; // financial year starts in April
  return (
    <>
      <SectionHead title="Income Tax" action={
        <select value={cur} onChange={e => setFy(Number(e.target.value))} aria-label="Financial year" style={{ padding: '8px 10px', borderRadius: 6 }}>
          {[realFy, realFy - 1].map(y => <option key={y} value={y}>FY {y}-{String(y + 1).slice(2)}</option>)}
        </select>} />
      <div style={{ ...panelStyle, padding: '12px 18px', marginBottom: 16, ...muted }}>
        <strong>Important:</strong> tax uses your declarations (accepted amounts once verified; nothing for unproven items after the proof window closes) and approved previous-employer income. Future months are projected
        at your current pay and exclude bonuses and revisions.
      </div>
      {!data.configured && <div style={{ ...panelStyle, padding: 18, marginBottom: 16, ...muted }}>Tax slabs for {data.fyLabel} are not set up yet.</div>}
      <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
        <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Net Taxable Income" value={fmtCurrency(data.netTaxableIncome)} />
        <StatTile tone="var(--info)" icon={<Wallet size={20} />} label="Gross Income Tax" value={fmtCurrency(t.grossTax)} />
        <StatTile tone="var(--info)" icon={<Wallet size={20} />} label="Surcharge & Cess" value={fmtCurrency(t.surcharge + t.cess)} />
        <StatTile tone="var(--warn, #b45309)" icon={<IndianRupee size={20} />} label="Net Tax Payable" value={fmtCurrency(t.netPayable)} />
        <StatTile tone="var(--ok)" icon={<IndianRupee size={20} />} label="Tax Paid Till Now" value={fmtCurrency(data.taxPaidTillNow)} />
        <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Remaining Tax" value={fmtCurrency(data.remainingTax)} />
      </div>

      <SectionHead title="A – Gross Earnings" />
      <div style={{ ...panelStyle, marginBottom: 20 }}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Month', 'Type', 'Gross'].map((h, i) => <th key={h} style={{ ...thStyle, ...(i === 2 ? right : {}) }}>{h}</th>)}</tr></thead>
          <tbody>
            {data.months.map(m => (
              <tr key={m.period}><td style={tdStyle}>{m.label}</td><td style={tdStyle}>{m.actual ? 'Actual' : 'Projected'}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(m.gross)}</td></tr>
            ))}
            <tr><td style={tdStyle}>Previous employer income</td><td style={tdStyle} /><td style={{ ...tdStyle, ...right }}>{fmtCurrency(data.previousEmployerIncome)}</td></tr>
            <tr><td style={{ ...tdStyle, fontWeight: 700 }}>Total (A)</td><td style={tdStyle} /><td style={{ ...tdStyle, ...right, fontWeight: 700 }}>{fmtCurrency(data.grossEarnings + data.previousEmployerIncome)}</td></tr>
          </tbody>
        </table>
      </div>

      <SectionHead title="B – Taxable Income" />
      <div style={{ ...panelStyle, padding: '16px 20px', marginBottom: 20 }}>
        <div style={grid}>
          <ReadField label="Gross Earnings (A)" value={fmtCurrency(data.grossEarnings + data.previousEmployerIncome)} />
          <ReadField label="Add: Other Income Declared" value={fmtCurrency(data.otherIncome)} />
          <ReadField label="Less: Standard Deduction" value={fmtCurrency(data.standardDeduction)} />
          <ReadField label="Less: Declared Deductions" value={fmtCurrency(data.deductions)} />
          <ReadField label="Tax Deducted by Previous Employer" value={fmtCurrency(data.previousEmployerTds)} />
          <ReadField label="Net Taxable Income" value={fmtCurrency(data.netTaxableIncome)} />
        </div>
      </div>

      <SectionHead title={`P – Tax on Income (${data.regime === 'OLD' ? 'Old' : 'New'} Regime)`} />
      <div style={{ ...panelStyle, marginBottom: 20 }}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Slab', 'Rate', 'Taxable', 'Tax'].map((h, i) => <th key={h} style={{ ...thStyle, ...(i > 1 ? right : {}) }}>{h}</th>)}</tr></thead>
          <tbody>
            {t.slabTax.map(s => (
              <tr key={s.from}><td style={tdStyle}>{fmtCurrency(s.from)} – {s.to == null ? 'above' : fmtCurrency(s.to)}</td><td style={tdStyle}>{Math.round(s.rate * 100)}%</td>
                <td style={{ ...tdStyle, ...right }}>{fmtCurrency(s.taxable)}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(s.tax)}</td></tr>
            ))}
            <tr><td style={tdStyle}>Less: Rebate u/s 87A</td><td style={tdStyle} /><td style={tdStyle} /><td style={{ ...tdStyle, ...right }}>{fmtCurrency(t.rebate)}</td></tr>
            <tr><td style={{ ...tdStyle, fontWeight: 700 }}>Tax after rebate</td><td style={tdStyle} /><td style={tdStyle} /><td style={{ ...tdStyle, ...right, fontWeight: 700 }}>{fmtCurrency(t.grossTax)}</td></tr>
          </tbody>
        </table>
      </div>

      <SectionHead title="Q – Surcharge, Cess and T – Net Tax Payable" />
      <div style={{ ...panelStyle, padding: '16px 20px', marginBottom: 20 }}>
        <div style={grid}>
          <ReadField label="Surcharge" value={fmtCurrency(t.surcharge)} />
          <ReadField label="Health & Education Cess (4%)" value={fmtCurrency(t.cess)} />
          <ReadField label="Net Tax Payable (T, rounded)" value={fmtCurrency(t.netPayable)} />
        </div>
      </div>

      <SectionHead title="U / V – TDS Month by Month" />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Month', 'TDS', 'Cumulative TDS'].map((h, i) => <th key={h} style={{ ...thStyle, ...(i ? right : {}) }}>{h}</th>)}</tr></thead>
          <tbody>
            {data.months.map(m => (
              <tr key={m.period}><td style={tdStyle}>{m.label}{m.actual ? '' : ' (projected)'}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(m.tds)}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(m.cumulativeTds)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
