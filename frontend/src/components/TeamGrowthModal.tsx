import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, Sparkles, Award, BookOpen, CheckCircle2, Clock, Target, ExternalLink } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useToast } from '../context/ToastContext';
import { teamGrowthApi, type TeamGrowthData } from '../api/teamGrowth';
import { TabBar } from './TabBar';

/** Overlay + card chrome, browse-only (no forced Cancel/Save footer like EditModal) — this is a
 * read-only view over an employee's Skills/Certificates/Learning, not a form. Exported for reuse
 * by the single-item detail popups on the Team Performance roster (MyTeamPage). */
export function DetailModalShell({ title, subtitle, onClose, children }: {
  title: string; subtitle?: React.ReactNode; onClose: () => void; children: React.ReactNode;
}) {
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);

  return createPortal(
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600, padding: 'clamp(16px, 4vw, 40px)' }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 12,
          width: 'clamp(320px, 70vw, 760px)', maxWidth: '95vw', maxHeight: '90vh',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
          boxShadow: '0 24px 64px rgba(0,0,0,.55)',
        }}
      >
        <div style={{ padding: '18px 22px', borderBottom: '1px solid var(--line)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>{title}</span>
            <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, display: 'flex' }}>
              <X size={16} />
            </button>
          </div>
          {subtitle && <div style={{ marginTop: 4 }}>{subtitle}</div>}
        </div>
        <div style={{ padding: 22, overflowY: 'auto' }}>{children}</div>
      </div>
    </div>,
    document.body
  );
}

function parseLocalDate(d: string): Date {
  const [y, m, day] = d.split('-').map(Number);
  return new Date(y, m - 1, day);
}
function fmtDate(d: string | null): string {
  if (!d) return '—';
  return parseLocalDate(d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function Pill({ tone, children }: { tone: string; children: React.ReactNode }) {
  return (
    <span style={{
      fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 20,
      background: `color-mix(in srgb, ${tone} 15%, transparent)`, color: tone,
      whiteSpace: 'nowrap',
    }}>
      {children}
    </span>
  );
}

function certificateStatus(expiry: string | null): { label: string; tone: string } | null {
  if (!expiry) return null;
  const days = (parseLocalDate(expiry).getTime() - Date.now()) / 86_400_000;
  if (days < 0) return { label: 'Expired', tone: 'var(--risk)' };
  if (days <= 60) return { label: 'Expiring Soon', tone: 'var(--warn)' };
  return { label: 'Active', tone: 'var(--ok)' };
}

const LEVEL_TONE: Record<string, string> = {
  Beginner: 'var(--txt-dim)', Intermediate: 'var(--info)', Advanced: 'var(--brand-bright)', Expert: 'var(--ok)',
};

type GrowthSection = 'skills' | 'certificates' | 'learning';

function SectionTabs({ tab, setTab, counts }: {
  tab: GrowthSection; setTab: (t: GrowthSection) => void; counts: Record<GrowthSection, number>;
}) {
  const tabs: { key: GrowthSection; label: string; icon: LucideIcon }[] = [
    { key: 'skills', label: 'Skills', icon: Sparkles },
    { key: 'certificates', label: 'Certificates', icon: Award },
    { key: 'learning', label: 'Learning', icon: BookOpen },
  ];
  return (
    <TabBar
      size="sm"
      ariaLabel="Growth sections"
      style={{ marginBottom: 18 }}
      active={tab}
      onChange={setTab}
      tabs={tabs.map(({ key, label, icon: Icon }) => ({ key, label, icon: <Icon size={13} />, count: counts[key] }))}
    />
  );
}

export function TeamGrowthModal({ token, employeeUserId, onClose }: {
  token: string; employeeUserId: string; onClose: () => void;
}) {
  const { showToast } = useToast();
  const [data, setData] = useState<TeamGrowthData | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<GrowthSection>('skills');

  useEffect(() => {
    setLoading(true);
    teamGrowthApi.get(token, employeeUserId).then(setData).catch(e => {
      showToast('error', e instanceof Error ? e.message : 'Could not load this employee\'s profile');
    }).finally(() => setLoading(false));
  }, [token, employeeUserId]);

  if (loading || !data) {
    return (
      <DetailModalShell title="Skills & Growth" onClose={onClose}>
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      </DetailModalShell>
    );
  }

  return (
    <DetailModalShell title={`${data.employeeName} — Skills & Growth`} onClose={onClose}>
      <SectionTabs
        tab={tab}
        setTab={setTab}
        counts={{ skills: data.skills.length, certificates: data.certificates.length, learning: data.learningEntries.length }}
      />

      {tab === 'skills' && (
        data.skills.length === 0 ? (
          <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Nothing added yet.</div>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {data.skills.map(s => (
              <div key={s.id} style={{
                display: 'flex', alignItems: 'center', gap: 7, padding: '6px 12px',
                background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 20,
              }}>
                <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{s.skillName}</span>
                <span style={{
                  fontSize: 9.5, fontWeight: 700, padding: '2px 7px', borderRadius: 10,
                  background: `color-mix(in srgb, ${LEVEL_TONE[s.proficiencyLevel]} 16%, transparent)`,
                  color: LEVEL_TONE[s.proficiencyLevel],
                }}>
                  {s.proficiencyLevel}
                </span>
              </div>
            ))}
          </div>
        )
      )}

      {tab === 'certificates' && (
        data.certificates.length === 0 ? (
          <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Nothing added yet.</div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {data.certificates.map(c => {
              const status = certificateStatus(c.expiryDate);
              return (
                <div key={c.id} style={{ padding: '12px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
                    <Award size={13} color="var(--info)" />
                    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{c.name}</span>
                    {status && <Pill tone={status.tone}>{status.label}</Pill>}
                  </div>
                  <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 3 }}>
                    {c.issuingOrganization ?? 'Issuer not specified'}
                    {c.credentialId ? ` · ID ${c.credentialId}` : ''}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3 }}>
                    {c.issueDate ? `Issued ${fmtDate(c.issueDate)}` : ''}
                    {c.expiryDate ? `${c.issueDate ? ' · ' : ''}Expires ${fmtDate(c.expiryDate)}` : ''}
                  </div>
                  {c.credentialUrl && (
                    <a href={c.credentialUrl} target="_blank" rel="noreferrer" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginTop: 6, fontSize: 11.5, fontWeight: 600, color: 'var(--brand-bright)', textDecoration: 'none' }}>
                      View credential <ExternalLink size={10} />
                    </a>
                  )}
                </div>
              );
            })}
          </div>
        )
      )}

      {tab === 'learning' && (
        data.learningEntries.length === 0 ? (
          <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Nothing added yet.</div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {data.learningEntries.map(e => (
              <div key={e.id} style={{ padding: '12px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
                  {e.status === 'Completed'
                    ? <CheckCircle2 size={13} color="var(--ok)" />
                    : e.status === 'Planned' ? <Target size={13} color="var(--info)" /> : <Clock size={13} color="var(--warn)" />}
                  <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{e.title}</span>
                  <Pill tone="var(--txt-dim)">{e.status}</Pill>
                </div>
                <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 3 }}>
                  {e.learningType}{e.provider ? ` · ${e.provider}` : ''}
                  {e.completedDate ? ` · Completed ${fmtDate(e.completedDate)}` : e.startDate ? ` · Started ${fmtDate(e.startDate)}` : ''}
                </div>
                {e.description && <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 6 }}>{e.description}</div>}
              </div>
            ))}
          </div>
        )
      )}
    </DetailModalShell>
  );
}
