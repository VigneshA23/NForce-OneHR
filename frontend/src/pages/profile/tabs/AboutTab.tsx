import { useEffect, useState } from 'react';
import { Calendar, RefreshCw, UserRound } from 'lucide-react';
import { profileApi, type ProfileData, type ProfileTimelineEvent } from '../../../api/profile';
import { SectionHeader, ReadField, computeDisplayName } from '../shared';
import { PreferredNameBioModal } from './PreferredNameBioModal';

function SummaryCard({ profile, token, onSaved }: { profile: ProfileData; token: string; onSaved: (p: ProfileData) => void }) {
  const [editing, setEditing] = useState(false);
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <SectionHeader title="Personal Summary & Bio" />
        <button onClick={() => setEditing(true)}
          style={{ padding: '5px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', cursor: 'pointer' }}>
          Edit
        </button>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
        <ReadField label="Display Name" value={computeDisplayName(profile)} />
        <div style={{ gridColumn: '1/-1' }}>
          <ReadField label="About Me / Bio" value={profile.bio} />
        </div>
      </div>
      {editing && (
        <PreferredNameBioModal profile={profile} token={token} onClose={() => setEditing(false)}
          onSaved={p => { onSaved(p); setEditing(false); }} />
      )}
    </div>
  );
}

function EmploymentContextCard({ profile }: { profile: ProfileData }) {
  const tenure = (() => {
    if (!profile.joiningDate) return null;
    const start = new Date(profile.joiningDate);
    const now = new Date();
    let months = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth());
    if (now.getDate() < start.getDate()) months -= 1;
    const years = Math.floor(months / 12);
    const remMonths = months % 12;
    const parts = [];
    if (years > 0) parts.push(`${years} yr${years !== 1 ? 's' : ''}`);
    parts.push(`${remMonths} mo${remMonths !== 1 ? 's' : ''}`);
    return parts.join(' ');
  })();

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <SectionHeader title="Employment & Organization Context" />
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
        <ReadField label="Job Title" value={profile.designationName} />
        <ReadField label="Department" value={profile.departmentName} />
        <ReadField label="Work Email" value={profile.email} />
        <ReadField label="Work Phone" value={profile.phone} />
        <ReadField label="Reporting Manager" value={profile.managerName} />
        <ReadField label="Location" value={profile.locationName} />
        <div style={{ gridColumn: '1/-1' }}>
          <ReadField label="Date of Joining / Tenure" value={profile.joiningDate ? `${profile.joiningDate}${tenure ? ` · (${tenure})` : ''}` : null} />
        </div>
      </div>
    </div>
  );
}

function TimelineSubTab({ entries, loading }: { entries: ProfileTimelineEvent[]; loading: boolean }) {
  if (loading) {
    return <div style={{ padding: 24, color: 'var(--txt-mut)', fontSize: 13 }}>Loading timeline…</div>;
  }
  if (entries.length === 0) {
    return <div style={{ padding: 24, color: 'var(--txt-dim)', fontSize: 13 }}>No lifecycle events yet.</div>;
  }
  // Oldest first in the API response (see backend ProfileService#getTimeline); display newest
  // at top to match the prototype's "bottom is joining date" layout. Sorts by the full
  // timestamp, not just the date, so same-day changes still order correctly.
  const sorted = [...entries].sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <SectionHeader title="Employee Life Cycle & Career History" badge="System History" />
      <div style={{ position: 'relative', paddingLeft: 8 }}>
        <div style={{ position: 'absolute', left: 23, top: 10, bottom: 10, width: 2, background: 'var(--line2)' }} />
        {sorted.map((e, i) => (
          <div key={i} style={{ position: 'relative', display: 'flex', alignItems: 'flex-start', gap: 16, marginBottom: i === sorted.length - 1 ? 0 : 22 }}>
            <div style={{
              position: 'relative', zIndex: 1, width: 36, height: 36, borderRadius: '50%',
              background: e.type === 'JOINED_COMPANY' ? '#2FB67C' : '#4C8DD6',
              display: 'grid', placeItems: 'center', color: '#fff', flexShrink: 0,
              boxShadow: '0 0 0 4px var(--panel)',
            }}>
              {e.type === 'JOINED_COMPANY' ? <UserRound size={16} /> : <RefreshCw size={16} />}
            </div>
            <div style={{ paddingTop: 4 }}>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)', marginBottom: 2 }}>{e.description}</div>
              <div style={{ fontSize: 12, color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 5 }}>
                <Calendar size={11} /> {new Date(e.date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function AboutTab({ profile, token, onSaved }: { profile: ProfileData; token: string; onSaved: (p: ProfileData) => void }) {
  const [subTab, setSubTab] = useState<'summary' | 'timeline'>('summary');
  const [timeline, setTimeline] = useState<ProfileTimelineEvent[]>([]);
  const [timelineLoading, setTimelineLoading] = useState(true);

  useEffect(() => {
    profileApi.getTimeline(token)
      .then(setTimeline)
      .catch(() => {})
      .finally(() => setTimelineLoading(false));
  }, [token]);

  const subTabStyle = (t: typeof subTab): React.CSSProperties => ({
    background: t === subTab ? 'rgba(177,17,22,.12)' : 'var(--raised)',
    border: `1px solid ${t === subTab ? 'var(--brand)' : 'var(--line2)'}`,
    color: t === subTab ? 'var(--brand-bright)' : 'var(--txt-mut)',
    fontSize: 12, fontWeight: 600, padding: '5px 14px', borderRadius: 20, cursor: 'pointer',
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', gap: 8 }}>
        <button style={subTabStyle('summary')} onClick={() => setSubTab('summary')}>Summary</button>
        <button style={subTabStyle('timeline')} onClick={() => setSubTab('timeline')}>Timeline</button>
      </div>

      {subTab === 'summary' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <SummaryCard profile={profile} token={token} onSaved={onSaved} />
          <EmploymentContextCard profile={profile} />
        </div>
      ) : (
        <TimelineSubTab entries={timeline} loading={timelineLoading} />
      )}
    </div>
  );
}
