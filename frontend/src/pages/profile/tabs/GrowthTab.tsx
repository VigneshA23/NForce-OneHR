import { useEffect, useMemo, useState } from 'react';
import {
  Plus, Pencil, Trash2, Sparkles, BookOpen, CheckCircle2, Target,
  Clock, Award, ArrowRight, ExternalLink,
} from 'lucide-react';
import { useToast } from '../../../context/ToastContext';
import { profileSkillsApi, profileLearningApi, profileCertificatesApi, type SkillEntry, type LearningEntry, type LearningStatus, type CertificateEntry } from '../../../api/profile';
import { SectionHeader } from '../shared';
import { SkillModal } from './SkillModal';
import { LearningEntryModal } from './LearningEntryModal';
import { CertificateModal } from './CertificateModal';
import { TabBar } from '../../../components/TabBar';

const ADD_BTN_STYLE: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px',
  background: 'var(--brand)', border: 'none', borderRadius: 6,
  fontSize: 12, fontWeight: 600, color: '#fff', cursor: 'pointer',
};
const ICON_BTN_STYLE: React.CSSProperties = {
  background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: 5,
  cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex',
};

const LEVEL_TONE: Record<string, string> = {
  Beginner: 'var(--txt-dim)', Intermediate: 'var(--info)', Advanced: 'var(--brand-bright)', Expert: 'var(--ok)',
};

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

function SkillChip({ name }: { name: string }) {
  return (
    <span style={{
      fontSize: 10.5, fontWeight: 600, padding: '2px 9px', borderRadius: 20,
      background: 'var(--raised2)', border: '1px solid var(--line2)', color: 'var(--txt-mut)',
    }}>
      {name}
    </span>
  );
}

// `new Date("2026-09-30")` parses as UTC midnight, so toLocaleDateString in a negative-offset
// timezone (e.g. US Central) rolls it back to the previous day. Parsing the Y/M/D components
// into a *local* Date instead avoids that shift regardless of the viewer's timezone.
function parseLocalDate(d: string): Date {
  const [y, m, day] = d.split('-').map(Number);
  return new Date(y, m - 1, day);
}

function formatDate(d: string | null): string {
  if (!d) return '—';
  return parseLocalDate(d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function certificateStatus(expiry: string | null): { label: string; tone: string } | null {
  if (!expiry) return null;
  const days = (parseLocalDate(expiry).getTime() - Date.now()) / 86_400_000;
  if (days < 0) return { label: 'Expired', tone: 'var(--risk)' };
  if (days <= 60) return { label: 'Expiring Soon', tone: 'var(--warn)' };
  return { label: 'Active', tone: 'var(--ok)' };
}

function SkillsCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const [skills, setSkills] = useState<SkillEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalTarget, setModalTarget] = useState<SkillEntry | 'new' | null>(null);

  function reload() {
    setLoading(true);
    profileSkillsApi.list(token).then(setSkills).catch(() => {}).finally(() => setLoading(false));
  }
  useEffect(reload, [token]);

  async function handleDelete(id: string) {
    try {
      await profileSkillsApi.remove(token, id);
      setSkills(prev => prev.filter(s => s.id !== id));
      showToast('success', 'Skill removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <SectionHeader title="Skills & Expertise" icon={Sparkles} />
        <button onClick={() => setModalTarget('new')} style={ADD_BTN_STYLE}>
          <Plus size={13} /> Add Skill
        </button>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : skills.length === 0 ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No skills added yet.</div>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {skills.map(s => (
            <div key={s.id} style={{
              display: 'flex', alignItems: 'center', gap: 7, padding: '6px 6px 6px 12px',
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
              <button onClick={() => setModalTarget(s)} aria-label="Edit skill" style={ICON_BTN_STYLE}><Pencil size={11} /></button>
              <button onClick={() => handleDelete(s.id)} aria-label="Delete skill" style={{ ...ICON_BTN_STYLE, color: 'var(--risk)' }}><Trash2 size={11} /></button>
            </div>
          ))}
        </div>
      )}
      {modalTarget && (
        <SkillModal
          token={token}
          existing={modalTarget === 'new' ? null : modalTarget}
          onClose={() => setModalTarget(null)}
          onSaved={saved => {
            setSkills(prev => {
              const idx = prev.findIndex(s => s.id === saved.id);
              return idx >= 0 ? prev.map((s, i) => i === idx ? saved : s) : [...prev, saved];
            });
          }}
        />
      )}
    </div>
  );
}

function CertificateCard({ cert, onEdit, onDelete }: { cert: CertificateEntry; onEdit: () => void; onDelete: () => void }) {
  const status = certificateStatus(cert.expiryDate);
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10, padding: '14px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
          <Award size={14} color="var(--info)" style={{ flexShrink: 0 }} />
          <span style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{cert.name}</span>
          {status && <Pill tone={status.tone}>{status.label}</Pill>}
        </div>
        <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3 }}>
          {cert.issuingOrganization ?? 'Issuer not specified'}
          {cert.credentialId ? ` · ID ${cert.credentialId}` : ''}
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
          {cert.issueDate ? `Issued ${formatDate(cert.issueDate)}` : ''}
          {cert.expiryDate ? `${cert.issueDate ? ' · ' : ''}Expires ${formatDate(cert.expiryDate)}` : ''}
        </div>
        {cert.credentialUrl && (
          <a href={cert.credentialUrl} target="_blank" rel="noreferrer" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginTop: 8, fontSize: 12, fontWeight: 600, color: 'var(--brand-bright)', textDecoration: 'none' }}>
            View credential <ExternalLink size={11} />
          </a>
        )}
      </div>
      <EntryActions onEdit={onEdit} onDelete={onDelete} />
    </div>
  );
}

function CertificatesCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const [certs, setCerts] = useState<CertificateEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalTarget, setModalTarget] = useState<CertificateEntry | 'new' | null>(null);

  function reload() {
    setLoading(true);
    profileCertificatesApi.list(token).then(setCerts).catch(() => {}).finally(() => setLoading(false));
  }
  useEffect(reload, [token]);

  async function handleDelete(id: string) {
    try {
      await profileCertificatesApi.remove(token, id);
      setCerts(prev => prev.filter(c => c.id !== id));
      showToast('success', 'Certificate removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <SectionHeader title="Certificates" icon={Award} />
        <button onClick={() => setModalTarget('new')} style={ADD_BTN_STYLE}>
          <Plus size={13} /> Add Certificate
        </button>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : certs.length === 0 ? (
        <EmptyState text="No certificates added yet." onAdd={() => setModalTarget('new')} addLabel="Add Certificate" />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {certs.map(c => (
            <CertificateCard key={c.id} cert={c} onEdit={() => setModalTarget(c)} onDelete={() => handleDelete(c.id)} />
          ))}
        </div>
      )}
      {modalTarget && (
        <CertificateModal
          token={token}
          existing={modalTarget === 'new' ? null : modalTarget}
          onClose={() => setModalTarget(null)}
          onSaved={saved => {
            setCerts(prev => {
              const idx = prev.findIndex(c => c.id === saved.id);
              return idx >= 0 ? prev.map((c, i) => i === idx ? saved : c) : [...prev, saved];
            });
          }}
        />
      )}
    </div>
  );
}

type LearningTabKey = 'recent' | 'inProgress' | 'history';
const LEARNING_TABS: { key: LearningTabKey; label: string }[] = [
  { key: 'recent', label: 'Recent Learning' },
  { key: 'inProgress', label: 'In Progress' },
  { key: 'history', label: 'Learning History' },
];

function EntryActions({ onEdit, onDelete }: { onEdit: () => void; onDelete: () => void }) {
  return (
    <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
      <button onClick={onEdit} aria-label="Edit entry" style={ICON_BTN_STYLE}><Pencil size={13} /></button>
      <button onClick={onDelete} aria-label="Delete entry" style={{ ...ICON_BTN_STYLE, color: 'var(--risk)' }}><Trash2 size={13} /></button>
    </div>
  );
}

function CompletedEntryCard({ entry, onEdit, onDelete }: { entry: LearningEntry; onEdit: () => void; onDelete: () => void }) {
  const cert = entry.certificateName ? certificateStatus(entry.certificateExpiryDate) : null;
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10, padding: '14px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
          <CheckCircle2 size={14} color="var(--ok)" style={{ flexShrink: 0 }} />
          <span style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{entry.title}</span>
        </div>
        <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3 }}>
          {entry.learningType} · Completed {formatDate(entry.completedDate)}
          {entry.provider ? ` · ${entry.provider}` : ''}
        </div>
        {entry.description && <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 6 }}>{entry.description}</div>}
        {entry.skillsDeveloped.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 8 }}>
            {entry.skillsDeveloped.map(s => <SkillChip key={s} name={s} />)}
          </div>
        )}
        {entry.certificateName && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
            <Pill tone="var(--info)"><Award size={9} style={{ marginRight: 3, verticalAlign: -1 }} />{entry.certificateName}</Pill>
            {cert && <Pill tone={cert.tone}>{cert.label}</Pill>}
          </div>
        )}
      </div>
      <EntryActions onEdit={onEdit} onDelete={onDelete} />
    </div>
  );
}

function InProgressEntryCard({ entry, onEdit, onDelete }: { entry: LearningEntry; onEdit: () => void; onDelete: () => void }) {
  const isPlanned = entry.status === 'Planned';
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10, padding: '14px 16px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
          {isPlanned ? <Target size={14} color="var(--info)" style={{ flexShrink: 0 }} /> : <Clock size={14} color="var(--warn)" style={{ flexShrink: 0 }} />}
          <span style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{entry.title}</span>
        </div>
        <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3 }}>
          {entry.learningType} · {entry.status}
          {entry.provider ? ` · ${entry.provider}` : ''}
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
          {isPlanned ? `Starts ${formatDate(entry.startDate)}` : `Started ${formatDate(entry.startDate)}`}
        </div>
        {entry.description && <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 6 }}>{entry.description}</div>}
        {entry.skillsDeveloped.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 8 }}>
            {entry.skillsDeveloped.map(s => <SkillChip key={s} name={s} />)}
          </div>
        )}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8, flexShrink: 0 }}>
        <button onClick={onEdit} style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '5px 11px', background: 'var(--raised2)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 11.5, fontWeight: 600, color: 'var(--txt)', cursor: 'pointer' }}>
          {isPlanned ? 'View' : 'Continue'} <ArrowRight size={11} />
        </button>
        <EntryActions onEdit={onEdit} onDelete={onDelete} />
      </div>
    </div>
  );
}

function EmptyState({ text, onAdd, addLabel = 'Add Learning' }: { text: string; onAdd?: () => void; addLabel?: string }) {
  return (
    <div style={{ textAlign: 'center', padding: '28px 12px', color: 'var(--txt-dim)', fontSize: 13 }}>
      <div>{text}</div>
      {onAdd && (
        <button onClick={onAdd} style={{ ...ADD_BTN_STYLE, margin: '14px auto 0' }}>
          <Plus size={13} /> {addLabel}
        </button>
      )}
    </div>
  );
}

function LearningDevelopmentCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const [entries, setEntries] = useState<LearningEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<LearningTabKey>('recent');
  const [modalTarget, setModalTarget] = useState<LearningEntry | 'new' | null>(null);

  function reload() {
    setLoading(true);
    profileLearningApi.list(token).then(setEntries).catch(() => {}).finally(() => setLoading(false));
  }
  useEffect(reload, [token]);

  async function handleDelete(id: string) {
    try {
      await profileLearningApi.remove(token, id);
      setEntries(prev => prev.filter(e => e.id !== id));
      showToast('success', 'Entry removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  const { recent, inProgress, historyByYear } = useMemo(() => {
    const cutoff = new Date();
    cutoff.setMonth(cutoff.getMonth() - 12);

    const completed = entries.filter(e => e.status === 'Completed' && e.completedDate);
    const recent = completed
      .filter(e => parseLocalDate(e.completedDate as string) >= cutoff)
      .sort((a, b) => (b.completedDate as string).localeCompare(a.completedDate as string));
    const history = completed
      .filter(e => parseLocalDate(e.completedDate as string) < cutoff)
      .sort((a, b) => (b.completedDate as string).localeCompare(a.completedDate as string));
    const inProgress = entries
      .filter(e => e.status === 'In Progress' || e.status === 'Planned')
      .sort((a, b) => (b.startDate ?? b.createdAt).localeCompare(a.startDate ?? a.createdAt));

    const historyByYear = new Map<string, LearningEntry[]>();
    for (const e of history) {
      const year = (e.completedDate as string).slice(0, 4);
      if (!historyByYear.has(year)) historyByYear.set(year, []);
      historyByYear.get(year)!.push(e);
    }

    return { recent, inProgress, historyByYear };
  }, [entries]);

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 10 }}>
        <SectionHeader title="Learning & Development" icon={BookOpen} />
        <button onClick={() => setModalTarget('new')} style={ADD_BTN_STYLE}>
          <Plus size={13} /> Add Learning
        </button>
      </div>

      <TabBar size="sm" ariaLabel="Learning" style={{ marginBottom: 16 }} tabs={LEARNING_TABS} active={tab} onChange={setTab} />

      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : tab === 'recent' ? (
        recent.length === 0 ? (
          <EmptyState text="Your recent learning activities will appear here." onAdd={() => setModalTarget('new')} />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {recent.map(e => <CompletedEntryCard key={e.id} entry={e} onEdit={() => setModalTarget(e)} onDelete={() => handleDelete(e.id)} />)}
          </div>
        )
      ) : tab === 'inProgress' ? (
        inProgress.length === 0 ? (
          <EmptyState text="Nothing in progress right now." onAdd={() => setModalTarget('new')} />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {inProgress.map(e => <InProgressEntryCard key={e.id} entry={e} onEdit={() => setModalTarget(e)} onDelete={() => handleDelete(e.id)} />)}
          </div>
        )
      ) : historyByYear.size === 0 ? (
        <EmptyState text="No previous learning records yet." />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {Array.from(historyByYear.keys()).sort((a, b) => b.localeCompare(a)).map(year => (
            <div key={year}>
              <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', letterSpacing: '.05em', marginBottom: 8, borderBottom: '1px solid var(--line)', paddingBottom: 6 }}>
                {year}
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {historyByYear.get(year)!.map(e => (
                  <CompletedEntryCard key={e.id} entry={e} onEdit={() => setModalTarget(e)} onDelete={() => handleDelete(e.id)} />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {modalTarget && (
        <LearningEntryModal
          token={token}
          existing={modalTarget === 'new' ? null : modalTarget}
          // Add Learning is one shared button above the tabs, not scoped to whichever tab is
          // open — so a brand-new entry's selectable statuses are restricted to match the active
          // tab instead (Recent Learning/History is specifically the "already learned" list, so
          // only Completed applies there; In Progress covers both Planned and actively underway).
          // Editing an existing entry keeps all three (the modal's own default), since you still
          // need to move it along its lifecycle — e.g. marking a Planned one Completed.
          statusOptions={modalTarget === 'new'
            ? (tab === 'inProgress' ? ['Planned', 'In Progress'] : ['Completed']) as LearningStatus[]
            : undefined}
          onClose={() => setModalTarget(null)}
          onSaved={saved => {
            setEntries(prev => {
              const idx = prev.findIndex(e => e.id === saved.id);
              return idx >= 0 ? prev.map((e, i) => i === idx ? saved : e) : [saved, ...prev];
            });
          }}
        />
      )}
    </div>
  );
}

// Employee self-service only (ONEHR profile growth tracking, phase 1). Manager/lead visibility
// into Skills & Expertise / Learning & Development is an explicit later phase, not implemented here.
export function GrowthTab({ token }: { token: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <SkillsCard token={token} />
      <CertificatesCard token={token} />
      <LearningDevelopmentCard token={token} />
    </div>
  );
}
