import { Award, BookOpen, Sparkles } from 'lucide-react';
import type { TalentEmployeeDetail } from '../../api/talentOrg';
import './talentPolish.css';

export const LEVEL_TONE: Record<string, string> = {
  Beginner: 'var(--txt-dim)', Intermediate: 'var(--info)', Advanced: 'var(--brand-bright)', Expert: 'var(--ok)',
};

export function Pill({ tone, children }: { tone: string; children: React.ReactNode }) {
  return (
    <span style={{
      fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 20,
      background: `color-mix(in srgb, ${tone} 15%, transparent)`, color: tone, whiteSpace: 'nowrap',
    }}>
      {children}
    </span>
  );
}

function Section({ icon: Icon, title, count, children }: { icon: React.ComponentType<{ size?: number }>; title: string; count: number; children: React.ReactNode }) {
  return (
    <section className="nf-ed-sec">
      <div className="nf-ed-sec-hd">
        <span className="nf-ed-sec-ico"><Icon size={15} /></span>
        <h4>{title}</h4>
        <span className="nf-ed-count">{count}</span>
      </div>
      <div className="nf-ed-sec-body">{count === 0 ? <div className="nf-ed-none">None logged.</div> : children}</div>
    </section>
  );
}

/** Expiry label is derived from the certificate's own expiryDate only (display, no rule change). */
function expiryTag(expiryDate: string): { label: string; tone: string } {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const exp = new Date(expiryDate); exp.setHours(0, 0, 0, 0);
  const days = Math.round((exp.getTime() - today.getTime()) / 86400000);
  if (days < 0) return { label: 'Expired', tone: 'var(--risk)' };
  if (days <= 90) return { label: days === 0 ? 'Expires today' : `Expires in ${days}d`, tone: 'var(--warn)' };
  return { label: `Valid till ${expiryDate}`, tone: 'var(--ok)' };
}

const LEARNING_TONE: Record<string, string> = { Completed: 'var(--ok)', 'In Progress': 'var(--info)', Planned: 'var(--txt-mut)' };

/** Skills/Certificates/Learning read-only detail — shared by the Employee Directory drill-down and the Dashboard's stat/chart drill-down (both fetch via talentDirectoryApi.getDetail). */
export function EmployeeDetailView({ detail }: { detail: TalentEmployeeDetail }) {
  return (
    <div className="nf-ed">
      <div className="nf-ed-stats">
        <div className="nf-ed-stat" style={{ ['--c' as string]: 'var(--ok)' }}><b>{detail.skills.length}</b><span>Skills</span></div>
        <div className="nf-ed-stat" style={{ ['--c' as string]: 'var(--om-purple)' }}><b>{detail.certificates.length}</b><span>Certificates</span></div>
        <div className="nf-ed-stat" style={{ ['--c' as string]: 'var(--info)' }}><b>{detail.learningEntries.length}</b><span>Learning</span></div>
      </div>

      <Section icon={Sparkles} title="Skills & Expertise" count={detail.skills.length}>
        <div className="nf-ed-skills">
          {detail.skills.map(s => {
            const tone = LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)';
            return (
              <span key={s.id} className="nf-ed-skill">
                <i style={{ background: `color-mix(in srgb, ${tone} 15%, transparent)`, color: tone }}>{s.proficiencyLevel}</i>{s.skillName}
              </span>
            );
          })}
        </div>
      </Section>

      <Section icon={Award} title="Certificates" count={detail.certificates.length}>
        <div className="nf-ed-items">
          {detail.certificates.map(cert => {
            const tag = cert.expiryDate ? expiryTag(cert.expiryDate) : null;
            return (
              <div key={cert.id} className="nf-ed-item" style={{ ['--c' as string]: 'var(--om-purple)' }}>
                <span className="nf-ed-item-ico"><Award size={16} /></span>
                <div className="nf-ed-item-main">
                  <div className="nf-ed-item-name">{cert.name}</div>
                  {(cert.categoryName || cert.issuingOrganization) && (
                    <div className="nf-ed-item-sub">{[cert.categoryName, cert.issuingOrganization].filter(Boolean).join(' · ')}</div>
                  )}
                </div>
                {tag && <span className="nf-ed-tag" style={{ ['--c' as string]: tag.tone }}>{tag.label}</span>}
              </div>
            );
          })}
        </div>
      </Section>

      <Section icon={BookOpen} title="Learning & Development" count={detail.learningEntries.length}>
        <div className="nf-ed-items">
          {detail.learningEntries.map(l => (
            <div key={l.id} className="nf-ed-item" style={{ ['--c' as string]: 'var(--info)' }}>
              <span className="nf-ed-item-ico"><BookOpen size={16} /></span>
              <div className="nf-ed-item-main">
                <div className="nf-ed-item-name">{l.title}</div>
                {l.learningType && <div className="nf-ed-item-sub">{l.learningType}</div>}
              </div>
              <span className="nf-ed-tag" style={{ ['--c' as string]: LEARNING_TONE[l.status] ?? 'var(--txt-mut)' }}>{l.status === 'Planned' ? 'Not Started' : l.status}</span>
            </div>
          ))}
        </div>
      </Section>
    </div>
  );
}
