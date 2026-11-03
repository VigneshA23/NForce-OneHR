import PageTitleBlock from '../components/PageTitleBlock';
import { Award } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { SkillsCard } from './profile/tabs/SkillsCard';
import { SkillRatingsCard, TeamSkillEndorsementsCard } from './SkillRatings';

// US-A07: 1–5 self-rating against the HR-managed taxonomy, plus manager endorsement of direct reports.
export default function MySkillsPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const role = useAuthStore(s => s.user?.role);
  const canEndorse = role === 'MANAGER' || role === 'HR_ADMIN' || role === 'SUPER_ADMIN';

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
