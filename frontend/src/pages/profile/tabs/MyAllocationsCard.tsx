import { useCallback, useEffect, useState } from 'react';
import { FolderKanban } from 'lucide-react';
import { projectAllocationsApi, type MyAllocation } from '../../../api/projectAllocations';
import {
  CLOSED_PROJECT_LABEL, MY_ALLOCATIONS_EMPTY_MESSAGE, NO_CURRENT_MESSAGE, NO_UPCOMING_MESSAGE, STATUS_LABEL,
  clientLabel, formatAllocationRange, formatPercent, groupMyAllocations, isProjectClosed, projectHoldLabel, projectLabel,
} from '../../../lib/myAllocations';
import { SectionHeader } from '../shared';

/**
 * US-B08 — "My Project Allocations": the signed-in employee's own current and upcoming allocations,
 * strictly read-only (no add/edit/remove controls). The server decides whose data this is, from the
 * login token — nothing here names an employee.
 */
export function MyAllocationsCard({ token }: { token: string }) {
  const [items, setItems] = useState<MyAllocation[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setItems(null);
    setError(null);
    let cancelled = false;
    projectAllocationsApi.mine(token)
      .then(list => { if (!cancelled) setItems(list); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load your project allocations.'); });
    return () => { cancelled = true; };
  }, [token]);

  useEffect(() => load(), [load]);

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <SectionHeader title="My Project Allocations" icon={FolderKanban} />
      <Body items={items} error={error} onRetry={load} />
    </div>
  );
}

function Body({ items, error, onRetry }: { items: MyAllocation[] | null; error: string | null; onRetry: () => void }) {
  if (error) {
    return (
      <div role="alert" style={{ fontSize: 13, color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span>Couldn’t load your project allocations. {error}</span>
        <button type="button" onClick={onRetry}
          style={{ background: 'transparent', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 10px', fontSize: 12, color: 'var(--txt)', cursor: 'pointer' }}>
          Try again
        </button>
      </div>
    );
  }
  if (items === null) {
    return <div aria-live="polite" style={{ fontSize: 13, color: 'var(--txt-mut)' }}>Loading…</div>;
  }
  if (items.length === 0) {
    return <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>{MY_ALLOCATIONS_EMPTY_MESSAGE}</div>;
  }

  const { current, upcoming, closed } = groupMyAllocations(items);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <Group title="Current" rows={current} emptyText={NO_CURRENT_MESSAGE} />
      <Group title="Upcoming" rows={upcoming} emptyText={NO_UPCOMING_MESSAGE} />
      {closed.length > 0 && <Group title="Closed projects" rows={closed} emptyText="" />}
    </div>
  );
}

function Group({ title, rows, emptyText }: { title: string; rows: MyAllocation[]; emptyText: string }) {
  return (
    <section aria-label={`${title} allocations`}>
      <div className="nf-ps-label" style={{ marginBottom: 8 }}>{title} ({rows.length})</div>
      {rows.length === 0 ? (
        <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>{emptyText}</div>
      ) : (
        <div role="list" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 12 }}>
          {rows.map(a => <AllocationItem key={a.id} a={a} />)}
        </div>
      )}
    </section>
  );
}

function AllocationItem({ a }: { a: MyAllocation }) {
  const closed = isProjectClosed(a);
  const tone = closed ? 'var(--txt-mut)' : a.status === 'CURRENT' ? 'var(--ok)' : 'var(--info)';
  const percent = Math.max(0, Math.min(100, a.capacityPercent));
  const holdLabel = projectHoldLabel(a.projectStatus);
  return (
    <div role="listitem" style={{ border: '1px solid var(--line)', borderRadius: 8, padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 8, opacity: closed ? 0.8 : 1 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
        <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)', overflowWrap: 'anywhere' }}>{projectLabel(a.projectName)}</span>
        <span style={{ display: 'flex', gap: 6, flexShrink: 0, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {holdLabel && (
            <span style={{
              fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 999, color: 'var(--warn)',
              background: 'color-mix(in srgb, var(--warn) 14%, transparent)',
            }}>{holdLabel}</span>
          )}
          <span style={{
            fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 999, color: tone,
            background: `color-mix(in srgb, ${tone} 14%, transparent)`,
          }}>{closed ? CLOSED_PROJECT_LABEL : STATUS_LABEL[a.status]}</span>
        </span>
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Client: {clientLabel(a.clientName)}</div>
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--txt-mut)', marginBottom: 4 }}>
          <span>Allocation</span>
          <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{formatPercent(a.capacityPercent)}</span>
        </div>
        <div aria-hidden="true" style={{ height: 6, borderRadius: 999, background: 'var(--line)', overflow: 'hidden' }}>
          <div style={{ width: `${percent}%`, height: '100%', background: tone }} />
        </div>
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{formatAllocationRange(a.startDate, a.endDate)}</div>
    </div>
  );
}
