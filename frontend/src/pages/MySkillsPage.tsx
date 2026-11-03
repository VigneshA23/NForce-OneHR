import PageTitleBlock from '../components/PageTitleBlock';
import { Award } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { SkillsCard } from './profile/tabs/SkillsCard';

// Org-wide taxonomy + 1-5 self/manager-rating used to live here too (US-A07) — removed from this
// page per request. That system still exists and is still used elsewhere (Bench Management's own
// Settings tab manages the taxonomy; Bench List and Internal Opportunities use it for skill
// matching) — see skillsApi/SkillController. Only this page's own UI for it was removed.
export default function MySkillsPage() {
  const token = useAuthStore(s => s.token) ?? '';

  return (
    <div>
      <PageTitleBlock card icon={Award} style={{ marginBottom: 14 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>My Skills</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          Keep your skills up to date so HR and managers can find you for relevant work.
        </p>
      </PageTitleBlock>

      <SkillsCard token={token} />
    </div>
  );
}
