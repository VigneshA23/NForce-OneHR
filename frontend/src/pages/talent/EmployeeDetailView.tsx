import { Award, BookOpen, Sparkles } from 'lucide-react';
import type { TalentEmployeeDetail } from '../../api/talentOrg';

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

const sectionHeading: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)',
  textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8,
};
const emptyNote: React.CSSProperties = { fontSize: 12.5, color: 'var(--txt-dim)', fontStyle: 'italic' };

/** Skills/Certificates/Learning read-only detail — shared by the Employee Directory drill-down and the Dashboard's stat/chart drill-down (both fetch via talentDirectoryApi.getDetail). */
export function EmployeeDetailView({ detail }: { detail: TalentEmployeeDetail }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div>
        <div style={sectionHeading}><Sparkles size={13} /> Skills & Expertise</div>
        {detail.skills.length === 0 ? (
          <div style={emptyNote}>None logged.</div>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {detail.skills.map(s => (
              <Pill key={s.id} tone={LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)'}>{s.skillName} · {s.proficiencyLevel}</Pill>
            ))}
          </div>
        )}
      </div>
      <div>
        <div style={sectionHeading}><Award size={13} /> Certificates</div>
        {detail.certificates.length === 0 ? (
          <div style={emptyNote}>None logged.</div>
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
        <div style={sectionHeading}><BookOpen size={13} /> Learning & Development</div>
        {detail.learningEntries.length === 0 ? (
          <div style={emptyNote}>None logged.</div>
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
  );
}
