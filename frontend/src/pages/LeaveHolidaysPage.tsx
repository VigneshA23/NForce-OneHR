import { useState } from 'react';
import LeavePage from './LeavePage';
import HolidaysPage from './HolidaysPage';
import { TabBar } from '../components/TabBar';

// Single top-level "Leave & Holidays" module (one NavItem, one route — see nav.config.ts and
// App.tsx) presenting its two existing sub-modules, Leave and Holidays, *sequentially*: exactly
// one renders at a time, in this same content area, switched via the tab bar below — never both
// side by side. Both sub-components are reused completely unmodified (same state, API calls,
// filtering, styling, and behavior as before); this file only adds the tab switcher on top.
type LeaveHolidaysTab = 'leave' | 'holidays';

const TABS: { key: LeaveHolidaysTab; label: string }[] = [
  { key: 'leave', label: 'Leave' },
  { key: 'holidays', label: 'Holidays' },
];

export default function LeaveHolidaysPage() {
  const [tab, setTab] = useState<LeaveHolidaysTab>('leave');

  return (
    <div>
      <TabBar tabs={TABS} active={tab} onChange={setTab} ariaLabel="Leave and holidays" style={{ marginBottom: 16 }} />
      {tab === 'leave' ? <LeavePage /> : <HolidaysPage />}
    </div>
  );
}
