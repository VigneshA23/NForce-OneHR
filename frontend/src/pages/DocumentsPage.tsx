import { useEffect, useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import { useSearchParams } from 'react-router-dom';
import { CheckCircle, AlertTriangle, Search, Eye, Paperclip, FileText, ShieldCheck, Hourglass, XCircle, FileX2, Megaphone, ScrollText, ExternalLink } from 'lucide-react';
import DashTile from '../components/DashTile';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { myPolicies, acknowledgePolicy, publishedAnnouncements, fetchPolicyAttachment, type Policy, type Announcement } from '../api/policies';
import { card, bucketRequiredDocuments } from './documents/shared';
import { useMyDocumentsData } from './documents/useMyDocumentsData';
import { MyDocumentsSection } from './documents/MyDocumentsSection';
import { TabBar } from '../components/TabBar';
import { DocPopup } from './documents/DocPopup';

// ── Policy Read-First Modal ───────────────────────────────

// Without onConfirm the modal is read-only (view the policy, no acknowledgment step).
function AcknowledgeModal({ policy, onConfirm, onClose }: {
  policy: Policy;
  onConfirm?(): Promise<void>;
  onClose(): void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [checked, setChecked] = useState(false);
  const [loading, setLoading] = useState(false);
  const [attachmentLoading, setAttachmentLoading] = useState(false);

  async function submit() {
    if (!onConfirm) return;
    setLoading(true);
    try { await onConfirm(); } finally { setLoading(false); }
  }

  async function openAttachment() {
    setAttachmentLoading(true);
    try {
      const url = await fetchPolicyAttachment(token, policy.id);
      window.open(url, '_blank');
    } catch {
      showToast('error', 'Could not open attachment');
    } finally {
      setAttachmentLoading(false);
    }
  }

  return (
    <DocPopup
      icon={ScrollText}
      size="lg"
      title={policy.title}
      sub={onConfirm ? 'Read the policy, then confirm your acknowledgment.' : 'Policy details'}
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="nf-dp-cancel" onClick={onClose} disabled={loading}>{onConfirm ? 'Cancel' : 'Close'}</button>
          {onConfirm && (
            <button type="button" className="nf-dp-go" onClick={submit} disabled={!checked || loading}>
              <CheckCircle size={15} /> {loading ? 'Saving…' : 'Confirm Acknowledgment'}
            </button>
          )}
        </>
      )}
    >
      <div className="nf-dp-chips">
        <span className="nf-dp-chip is-brand">Version {policy.version}</span>
        {policy.required && <span className="nf-dp-chip is-risk">Required</span>}
        <span className="nf-dp-chip">Audience: {policy.audience}</span>
        <span className="nf-dp-chip">Published {new Date(policy.publishedAt).toLocaleDateString()}</span>
      </div>
      <div className="nf-dp-read">{policy.description}</div>
      {policy.hasAttachment && (
        <button type="button" className="nf-dp-attach" onClick={openAttachment} disabled={attachmentLoading}>
          <span className="nf-dp-attach-ico"><Paperclip size={16} /></span>
          <span className="nf-dp-attach-txt">
            {attachmentLoading ? 'Opening…' : (policy.attachmentFileName ?? 'View Attachment')}
            <small>Opens in a new tab</small>
          </span>
          <ExternalLink size={15} color="var(--txt-mut)" />
        </button>
      )}
      {onConfirm && (
        <label className={`nf-dp-agree${checked ? ' is-on' : ''}`}>
          <input type="checkbox" checked={checked} onChange={e => setChecked(e.target.checked)} />
          <span>I have read and understood this policy and agree to comply with its terms.</span>
        </label>
      )}
    </DocPopup>
  );
}

// ── Announcement View Modal ───────────────────────────────

function AnnouncementModal({ announcement, onClose }: { announcement: Announcement; onClose(): void }) {
  return (
    <DocPopup
      icon={Megaphone}
      size="lg"
      title={announcement.title}
      sub="Announcement"
      onClose={onClose}
      closeOnBackdrop
      footer={<button type="button" className="nf-dp-cancel" onClick={onClose}>Close</button>}
    >
      <div className="nf-dp-chips">
        {announcement.publishedAt && <span className="nf-dp-chip">Published {new Date(announcement.publishedAt).toLocaleDateString()}</span>}
        <span className="nf-dp-chip">Audience: {announcement.audience}</span>
      </div>
      <div className="nf-dp-read">{announcement.body}</div>
    </DocPopup>
  );
}

// ── Loading skeleton ──────────────────────────────────────
// Same shimmer treatment as NotificationsPage's SkeletonRow — mirrors the page's real shape
// (title, KPI tiles, tab bar, a few card rows) instead of a bare "Loading…" line.

function PageSkeleton() {
  return (
    <div>
      <div className="nf-skeleton" style={{ height: 22, width: 240, borderRadius: 5, marginBottom: 8 }} />
      <div className="nf-skeleton" style={{ height: 13, width: 340, borderRadius: 4, marginBottom: 22 }} />
      <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px,1fr))', gap: 12, marginBottom: 24 }}>
        {[0, 1, 2, 3, 4].map(i => (
          <div key={i} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '16px 20px' }}>
            <div className="nf-skeleton" style={{ height: 26, width: 40, borderRadius: 5, marginBottom: 8 }} />
            <div className="nf-skeleton" style={{ height: 11, width: 70, borderRadius: 4 }} />
          </div>
        ))}
      </div>
      <div className="nf-skeleton" style={{ height: 38, width: 300, borderRadius: 8, marginBottom: 20 }} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {[0, 1, 2].map(i => (
          <div key={i} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: 20 }}>
            <div className="nf-skeleton" style={{ height: 14, width: '40%', borderRadius: 4, marginBottom: 10 }} />
            <div className="nf-skeleton" style={{ height: 11, width: '85%', borderRadius: 4, marginBottom: 6 }} />
            <div className="nf-skeleton" style={{ height: 11, width: '60%', borderRadius: 4 }} />
          </div>
        ))}
      </div>
      <style>{`
        .nf-skeleton {
          background: linear-gradient(90deg, var(--raised2) 25%, var(--line) 50%, var(--raised2) 75%);
          background-size: 200% 100%;
          animation: nf-doc-shimmer 1.5s infinite;
        }
        @keyframes nf-doc-shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
      `}</style>
    </div>
  );
}

// ── Main Page ─────────────────────────────────────────────

/** The five summary tiles: same tile as the Home dashboard (components/DashTile). Display only. */
export function DocumentStatTiles({ required, verified, pending, rejected, missing }: { required: number; verified: number; pending: number; rejected: number; missing: number }) {
  return (
    <div className="nf-kpi-2x2-mobile" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px,1fr))', gap: 14, marginBottom: 24 }}>
      <DashTile icon={<FileText size={20} />} accent="var(--info)" label="Required" value={String(required)} />
      <DashTile icon={<ShieldCheck size={20} />} accent="var(--ok)" label="Verified" value={String(verified)} />
      <DashTile icon={<Hourglass size={20} />} accent="var(--warn)" label="Pending Review" value={String(pending)} />
      <DashTile icon={<XCircle size={20} />} accent="var(--risk)" label="Rejected" value={String(rejected)} />
      <DashTile icon={<FileX2 size={20} />} accent="var(--om-purple)" label="Not Submitted" value={String(missing)} />
    </div>
  );
}

export default function DocumentsPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [searchParams] = useSearchParams();
  const [tab, setTab] = useState<'docs' | 'policies' | 'announcements'>(() => {
    const t = searchParams.get('tab');
    return t === 'policies' || t === 'announcements' ? t : 'docs';
  });
  const [search, setSearch] = useState(() => searchParams.get('search') ?? '');
  const { required, myDocs, loading: docsLoading, setRequired, setMyDocs } = useMyDocumentsData(token);
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [announcements, setAnnouncements] = useState<Announcement[]>([]);
  const [ackTarget, setAckTarget] = useState<Policy | null>(null);
  const [viewTarget, setViewTarget] = useState<Policy | null>(null);
  const [viewAnnouncement, setViewAnnouncement] = useState<Announcement | null>(null);
  const [policiesLoading, setPoliciesLoading] = useState(true);
  const loading = docsLoading || policiesLoading;

  useEffect(() => {
    setPoliciesLoading(true);
    Promise.all([
      myPolicies(token),
      publishedAnnouncements(token),
    ]).then(([pol, ann]) => {
      setPolicies(pol);
      setAnnouncements(ann);
    }).catch(e => showToast('error', e instanceof Error ? e.message : 'Load failed'))
      .finally(() => setPoliciesLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  async function confirmAck(policyId: number) {
    await acknowledgePolicy(token, policyId);
    setPolicies(prev => prev.map(p => p.id === policyId ? { ...p, acknowledged: true, acknowledgedAt: new Date().toISOString() } : p));
    showToast('success', 'Policy acknowledged');
    setAckTarget(null);
  }

  // KPI data
  const { verified, pending, rejected, missing } = bucketRequiredDocuments(required);
  const pendingPolicies = policies.filter(p => p.required && p.acknowledged === false);

  const q = search.trim().toLowerCase();
  const filteredAnnouncements = q
    ? announcements.filter(a => a.title.toLowerCase().includes(q) || a.body.toLowerCase().includes(q))
    : announcements;

  if (loading) return <PageSkeleton />;

  return (
    <div>
      <PageTitleBlock card icon={FileText}>
<h1 style={{ fontSize: 22, fontWeight: 800, marginBottom: 4, color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>My Documents & Policies</h1>
      <p style={{ color: 'var(--txt-dim)', fontSize: 13, marginBottom: 22 }}>Manage your required documents and acknowledge company policies.</p>
</PageTitleBlock>

      {/* KPI tiles */}
      <DocumentStatTiles required={required.length} verified={verified.length} pending={pending.length} rejected={rejected.length} missing={missing.length} />

      {pendingPolicies.length > 0 && (
        <div className="nf-doc-banner">
          <span className="nf-doc-banner-ico"><AlertTriangle size={16} /></span>
          <span><strong>{pendingPolicies.length} required polic{pendingPolicies.length > 1 ? 'ies' : 'y'}</strong> need acknowledgment.</span>
        </div>
      )}

      <TabBar
        className="nf-pilltabs"
        ariaLabel="Documents and policies"
        style={{ marginBottom: 20 }}
        active={tab}
        onChange={setTab}
        tabs={[
          { key: 'docs', label: 'My Documents' },
          // Badge = required policies still awaiting this employee's acknowledgment.
          { key: 'policies', label: 'Policies', count: pendingPolicies.length },
          { key: 'announcements', label: 'Announcements' },
        ]}
      />

      {/* ── My Documents Tab ── */}
      {tab === 'docs' && (
        <MyDocumentsSection
          required={required} myDocs={myDocs}
          setRequired={setRequired} setMyDocs={setMyDocs}
          search={search} onSearchChange={setSearch}
        />
      )}

      {/* ── Policies Tab ── */}
      {tab === 'policies' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {policies.length === 0 && <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No active policies.</p>}
          {policies.map(p => (
            <div key={p.id} style={{ ...card, padding: 20 }} className="nf-opp-card">
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 14 }}>
                <span className="nf-opp-ico nf-opp-ico--lg"><ScrollText size={20} /></span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 10, marginBottom: 6 }}>
                    <span style={{ fontWeight: 800, fontSize: 16, letterSpacing: '-.01em', color: 'var(--txt)' }}>{p.title}</span>
                    <span className="nf-rep-code">v{p.version}</span>
                    {p.required && <span className="nf-req-tag" style={{ marginLeft: 0 }}>Required</span>}
                  </div>
                  <p style={{ fontSize: 12, color: 'var(--txt-dim)', margin: '0 0 10px', lineHeight: 1.6 }}>{p.description}</p>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                    Published {new Date(p.publishedAt).toLocaleDateString()} · Audience: {p.audience}
                    {p.acknowledgedAt && <span style={{ marginLeft: 12, color: '#22c55e' }}>✓ Acknowledged current version {new Date(p.acknowledgedAt).toLocaleDateString()}</span>}
                    {!p.acknowledgedAt && p.previousVersionId != null && (
                      <span style={{ marginLeft: 12, color: '#eab308' }}>Updated since your last acknowledgment - please review v{p.version} again</span>
                    )}
                  </div>
                </div>
                <div style={{ flexShrink: 0 }}>
                  {/* Acknowledgment wasn't enabled when this policy was published — it's view-only
                      (read-only modal, no acknowledgment step) and it never counts as pending (see
                      p.required gating on pendingPolicies above and the backend's countPendingRequired* queries). */}
                  {p.required ? (
                    p.acknowledged === false ? (
                      <button type="button" className="nf-act-btn nf-act-btn--primary nf-act-btn--lg" onClick={() => setAckTarget(p)}>
                        <CheckCircle size={14} /> Review & Acknowledge
                      </button>
                    ) : (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                        <span className="nf-rep-pill"><CheckCircle size={13} /> Acknowledged</span>
                        <button onClick={() => setViewTarget(p)}
                          className="nf-act-btn nf-act-btn--lg"><Eye size={14} /> View
                        </button>
                      </div>
                    )
                  ) : (
                    <button onClick={() => setViewTarget(p)}
                      className="nf-act-btn nf-act-btn--lg"><Eye size={14} /> View
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── Announcements Tab ── */}
      {tab === 'announcements' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div className="nf-filterbar nf-search-full-mobile" style={{ marginBottom: 2 }}>
            <label className="nf-filter-search nf-search-full-mobile-input">
              <Search size={15} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search announcements…" />
            </label>
          </div>
          {filteredAnnouncements.length === 0 && (
            <p style={{ color: 'var(--txt-dim)', fontSize: 13 }}>{q ? 'No announcements match your search.' : 'No announcements yet.'}</p>
          )}
          {filteredAnnouncements.map(a => (
            <div key={a.id} style={{ ...card, padding: 20, display: 'flex', alignItems: 'flex-start', gap: 16, flexWrap: 'wrap' }} className="nf-opp-card">
              <span className="nf-opp-ico nf-opp-ico--lg"><Megaphone size={20} /></span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 800, fontSize: 16, letterSpacing: '-.01em', color: 'var(--txt)', marginBottom: 6 }}>{a.title}</div>
                {/* Preview only — the full announcement opens via View. */}
                <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: '0 0 10px', lineHeight: 1.6, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', overflowWrap: 'anywhere' }}>{a.body}</p>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                  {a.publishedAt && `Published ${new Date(a.publishedAt).toLocaleDateString()}`} · Audience: {a.audience}
                </div>
              </div>
              <div style={{ flexShrink: 0 }}>
                <button onClick={() => setViewAnnouncement(a)}
                  className="nf-act-btn nf-act-btn--lg"><Eye size={14} /> View
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {ackTarget && (
        <AcknowledgeModal
          policy={ackTarget}
          onConfirm={() => confirmAck(ackTarget.id)}
          onClose={() => setAckTarget(null)}
        />
      )}

      {viewTarget && <AcknowledgeModal policy={viewTarget} onClose={() => setViewTarget(null)} />}

      {viewAnnouncement && <AnnouncementModal announcement={viewAnnouncement} onClose={() => setViewAnnouncement(null)} />}
    </div>
  );
}
