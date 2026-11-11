import PageTitleBlock from '../components/PageTitleBlock';
import { Award } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { SkillsCard } from './profile/tabs/SkillsCard';
import { hasPermission } from '../lib/permissions';
import { SkillRatingsCard, TeamSkillEndorsementsCard } from './SkillRatings';

// US-A07: 1–5 self-rating against the HR-managed taxonomy, plus manager endorsement of direct reports.
export default function MySkillsPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const permissions = useAuthStore(s => s.permissions);
  // Endorsing a direct report's skills is gated server-side by skill_ratings:VIEW:TEAM — mirror that, not a role code.
  const canEndorse = hasPermission(permissions, 'skill_ratings', 'VIEW', 'TEAM');

  return (
    <div>
      <PageTitleBlock card icon={Award} style={{ marginBottom: 14 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>My Skills</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          Keep your skills up to date so HR and managers can find you for relevant work.
        </p>
      </PageTitleBlock>

      <SkillsCard token={token} />
      <SkillRatingsCard token={token} />
      {canEndorse && <TeamSkillEndorsementsCard token={token} />}
    </div>
  );
}
