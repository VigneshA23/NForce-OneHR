import { useEffect, useMemo, useRef, useState } from 'react';
import { Search, Download, Award, BookOpen, Sparkles } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { talentDirectoryApi, type TalentEmployeeSummary, type TalentEmployeeDetail } from '../../api/talentOrg';
import { DetailModalShell } from '../../components/TeamGrowthModal';

const LEVEL_TONE: Record<string, string> = {
  Beginner: 'var(--txt-dim)', Intermediate: 'var(--info)', Advanced: 'var(--brand-bright)', Expert: 'var(--ok)',
};

function Pill({ tone, children }: { tone: string; children: React.ReactNode }) {
  return (
    <span style={{
      fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 20,
      background: `color-mix(in srgb, ${tone} 15%, transparent)`, color: tone, whiteSpace: 'nowrap',
    }}>
      {children}
    </span>
  );
}

export function DirectoryTab() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [rows, setRows] = useState<TalentEmployeeSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [departmentFilter, setDepartmentFilter] = useState('');
  const [detail, setDetail] = useState<TalentEmployeeDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    talentDirectoryApi.listAll(token)
      .then(setRows)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load the directory'))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const departments = useMemo(
    () => Array.from(new Set(rows.map(r => r.departmentName).filter((d): d is string => !!d))).sort(),
    [rows]
  );

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return rows.filter(r => {
      if (departmentFilter && r.departmentName !== departmentFilter) return false;
      if (!q) return true;
      return r.employeeName.toLowerCase().includes(q) || r.employeeCode.toLowerCase().includes(q);
    });
  }, [rows, search, departmentFilter]);

  const detailRequestId = useRef(0);

  async function openDetail(employeeUserId: string) {
    const requestId = ++detailRequestId.current;
    setDetail(null);
    setDetailLoading(true);
    try {
      const d = await talentDirectoryApi.getDetail(token, employeeUserId);
      if (detailRequestId.current === requestId) setDetail(d);
    } catch (e) {
      if (detailRequestId.current === requestId) showToast('error', e instanceof Error ? e.message : 'Could not load this employee');
    } finally {
      if (detailRequestId.current === requestId) setDetailLoading(false);
    }
  }

  // Closing must win even if the detail fetch is still in flight — otherwise the response
  // landing after close flips detailLoading/detail back on and the modal reappears, stuck.
  function closeDetail() {
    detailRequestId.current++;
    setDetail(null);
    setDetailLoading(false);
  }

  if (loading) return <div style={{ color: 'var(--txt-mut)', fontSize: 13, padding: '20px 0' }}>Loading…</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <div style={{ maxWidth: 280, display: 'flex', alignItems: 'center', gap: 8, background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 10px', color: 'var(--txt-dim)' }}>
            <Search size={13} />
            <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search name or code…"
              style={{ flex: 1, background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} />
          </div>
          <select value={departmentFilter} onChange={e => setDepartmentFilter(e.target.value)}
            style={{ background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)' }}>
            <option value="">All Departments</option>
            {departments.map(d => <option key={d} value={d}>{d}</option>)}
          </select>
        </div>
        <a href={talentDirectoryApi.exportUrl()} target="_blank" rel="noreferrer"
          onClick={() => { /* browser follows the link with its own session cookie/token concerns handled server-side via the auth header — see note below */ }}
          style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, fontSize: 12.5, fontWeight: 600, color: 'var(--txt)', textDecoration: 'none' }}>
          <Download size={13} /> Export CSV
        </a>
      </div>

      <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>{filtered.length} of {rows.length} employees</div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {filtered.length === 0 ? (
          <div style={{ padding: 20, color: 'var(--txt-dim)', fontSize: 13 }}>No employees match.</div>
        ) : (
          filtered.map((r, i) => (
            <button key={r.employeeUserId} onClick={() => openDetail(r.employeeUserId)} style={{
              display: 'flex', alignItems: 'center', gap: 14, width: '100%', textAlign: 'left',
              padding: '12px 18px', background: 'none', border: 'none', cursor: 'pointer', font: 'inherit',
              borderTop: i === 0 ? 'none' : '1px solid var(--line)',
            }}>
              <div style={{ flex: '0 0 200px', minWidth: 0 }}>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.employeeName}</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeCode} · {r.departmentName ?? '—'}</div>
              </div>
              <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5, minWidth: 0 }}>
                {r.skills.slice(0, 4).map(s => (
                  <Pill key={s.id} tone={LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)'}>{s.skillName}</Pill>
                ))}
                {r.skills.length > 4 && <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>+{r.skills.length - 4} more</span>}
                {r.skills.length === 0 && <span style={{ fontSize: 11.5, color: 'var(--txt-dim)', fontStyle: 'italic' }}>No skills logged</span>}
              </div>
              <div style={{ flex: '0 0 100px', fontSize: 11.5, color: 'var(--txt-mut)', textAlign: 'right' }}>
                {r.certificates.length > 0 && <div><Award size={11} style={{ verticalAlign: -1 }} /> {r.certificates.length}</div>}
              </div>
            </button>
          ))
        )}
      </div>

      {(detail || detailLoading) && (
        <DetailModalShell title={detail?.employeeName ?? 'Loading…'} onClose={closeDetail}>
          {detailLoading || !detail ? (
            <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>
                  <Sparkles size={13} /> Skills & Expertise
                </div>
                {detail.skills.length === 0 ? (
                  <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', fontStyle: 'italic' }}>None logged.</div>
                ) : (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {detail.skills.map(s => (
                      <Pill key={s.id} tone={LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)'}>{s.skillName} · {s.proficiencyLevel}</Pill>
                    ))}
                  </div>
                )}
              </div>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>
                  <Award size={13} /> Certificates
                </div>
                {detail.certificates.length === 0 ? (
                  <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', fontStyle: 'italic' }}>None logged.</div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    {detail.certificates.map(c => (
                      <div key={c.id} style={{ fontSize: 12.5, color: 'var(--txt)' }}>
                        <strong>{c.name}</strong>
                        {c.categoryName && <span style={{ color: 'var(--txt-dim)' }}> · {c.categoryName}</span>}
                        {c.expiryDate && <span style={{ color: 'var(--txt-dim)' }}> · expires {c.expiryDate}</span>}
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>
                  <BookOpen size={13} /> Learning & Development
                </div>
                {detail.learningEntries.length === 0 ? (
                  <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', fontStyle: 'italic' }}>None logged.</div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {detail.learningEntries.map(l => (
                      <div key={l.id} style={{ fontSize: 12.5, color: 'var(--txt)' }}>
                        <strong>{l.title}</strong> <span style={{ color: 'var(--txt-dim)' }}>· {l.status}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </DetailModalShell>
      )}
    </div>
  );
}
