import React, { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft, Download, User, Users, Settings, Shield,
  Clock, Calendar, FileText, Package, GitBranch, AlertTriangle,
  Home, HelpCircle, CheckSquare, Lock,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { NAV, type Role, type NavItem } from '../lib/nav.config';
import { BrandMark } from '../components/BrandMark';

// ── URL slug → Role ───────────────────────────────────────────────────────────
const SLUG_TO_ROLE: Record<string, Role> = {
  employee:    'Employee',
  manager:     'Manager',
  'hr-admin':  'HR Admin',
  'super-admin': 'Super Admin',
};

// ── Role display config ───────────────────────────────────────────────────────
const ROLE_CONFIG: Record<Role, { icon: LucideIcon; color: string; bg: string }> = {
  Employee:    { icon: User,     color: '#2FB67C', bg: 'rgba(47,182,124,0.12)' },
  Manager:     { icon: Users,    color: '#4C8DD6', bg: 'rgba(76,141,214,0.12)' },
  'HR Admin':  { icon: Settings, color: '#E0A93B', bg: 'rgba(224,169,59,0.12)' },
  'Super Admin': { icon: Shield, color: '#E4373D', bg: 'rgba(228,55,61,0.12)' },
};

// ── Per-key feature descriptions (all real nav item keys from nav.config.ts) ──
const FEATURE_DESCRIPTIONS: Record<string, string> = {
  dashboard:    'Your personal HR dashboard — see your attendance status, upcoming leaves, pending requests, and team activity at a glance.',
  'my-team':    'View your direct teammates and reports, their current attendance status, and key HR details in one place.',
  employees:    'Manage the full employee lifecycle — add, update, and deactivate employee records across all departments.',
  directory:    'Browse and search the full employee directory — find anyone by name, role, department, or location instantly.',
  hierarchy:    'Navigate the company\'s reporting structure visually — see who reports to whom across every level, and export the chart as a PDF.',
  onboarding:   'Prepare new joiners with structured onboarding checklists, document collection, and access provisioning before day one.',
  attendance:   'Clock in and out, track hours automatically, manage shifts, process regularizations, and view your full attendance history.',
  leave:        'Apply for leave, check balances by type, browse the company holiday calendar, and track the status of every request.',
  approvals:    'A single queue for every pending decision — leave, expenses, and asset requests — so nothing gets lost in email.',
  exceptions:   'Surface attendance anomalies, irregular patterns, and policy violations across your team for quick review and resolution.',
  documents:    'Access your personal HR documents, track required submissions, and read company policies shared with your role.',
  policies:     'Publish company policies and HR announcements, and track who has read and acknowledged each one.',
  organization: 'Set up and maintain departments, designations, reporting structures, and locations for the entire organization.',
  performance:  'Track goals, log progress updates, and review growth milestones for individuals and teams.',
  assets:       'View company assets assigned to you, submit expense claims with supporting documents, and track reimbursement status.',
  requests:     'Track all your submitted requests — leave, regularizations, web clock-ins — and monitor their approval status in real time.',
  reports:      'Generate detailed reports on attendance, leave utilization, headcount, and team performance across the organization.',
  audit:        'A full, searchable log of every sensitive system action — role changes, approvals, account modifications, and security events.',
  access:       'Create and manage user accounts, assign roles, reset credentials, and control who has access to what across the platform.',
  masters:      'Configure the master data that powers the whole system: departments, designations, locations, and shift definitions.',
  workflows:    'Design and configure automated approval routing rules and process flows for your organization.',
  integrations: 'Connect NForce OneHR with external identity providers, payroll systems, and third-party tools.',
  featurelab:   'Preview and test upcoming platform features before they are released to the rest of the organization.',
  help:         'Raise a support ticket, browse help articles, and get instant answers to common HR questions.',
};

// Map nav icon names used in nav.config.ts to component refs for display
const ICON_MAP: Record<string, LucideIcon> = {
  Home, Clock, Calendar, HelpCircle, FileText, Users, GitBranch, Shield, AlertTriangle, Package,
  CheckSquare, User, Settings, Lock,
};

function NavItemRow({ item }: { item: NavItem }) {
  const Icon = item.icon as LucideIcon;
  const desc = FEATURE_DESCRIPTIONS[item.key] ?? 'Feature details coming soon.';
  const isPhase2 = item.phase === 2;

  return (
    <div style={{
      display: 'flex', alignItems: 'flex-start', gap: 16, padding: '18px 20px',
      border: `1px solid ${isPhase2 ? 'var(--line)' : 'var(--line)'}`,
      borderRadius: 10, background: isPhase2 ? 'transparent' : 'var(--panel)',
      opacity: isPhase2 ? 0.6 : 1,
    }}>
      <div style={{ width: 36, height: 36, borderRadius: 8, background: isPhase2 ? 'var(--raised)' : 'rgba(177,17,22,0.1)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <Icon size={16} color={isPhase2 ? 'var(--txt-dim)' : 'var(--brand-bright)'} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <span style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 14, color: 'var(--txt)' }}>
            {item.label}
          </span>
          {isPhase2 && (
            <span style={{ background: 'rgba(224,169,59,0.12)', color: 'var(--warn)', border: '1px solid rgba(224,169,59,0.22)', borderRadius: 999, padding: '1px 8px', fontSize: 10, fontWeight: 700 }}>
              COMING SOON
            </span>
          )}
        </div>
        <p style={{ color: 'var(--txt-mut)', fontSize: 13, lineHeight: 1.6, margin: 0 }}>
          {desc}
        </p>
      </div>
    </div>
  );
}

// ── PDF export ─────────────────────────────────────────────────────────────────
async function exportRoleGuidePdf(contentRef: React.RefObject<HTMLDivElement | null>, role: Role) {
  if (!contentRef.current) return;
  const { default: html2canvas } = await import('html2canvas');
  const { jsPDF } = await import('jspdf');

  const canvas = await html2canvas(contentRef.current, {
    backgroundColor: '#0E0F12',
    scale: 2,
    useCORS: true,
    logging: false,
  });

  const W = canvas.width;
  const H = canvas.height;
  const pdf = new jsPDF({ orientation: W > H ? 'landscape' : 'portrait', unit: 'px', format: [W, H] });
  pdf.addImage(canvas.toDataURL('image/png'), 'PNG', 0, 0, W, H);
  pdf.save(`nforce-onehr-${role.toLowerCase().replace(/\s+/g, '-')}-guide.pdf`);
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function RoleGuidePage() {
  const { role: slug } = useParams<{ role: string }>();
  const navigate = useNavigate();
  const contentRef = useRef<HTMLDivElement>(null);
  const [exporting, setExporting] = useState(false);

  const role = slug ? SLUG_TO_ROLE[slug] : undefined;

  if (!role) {
    return (
      <div style={{ minHeight: '100vh', background: 'var(--shell)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16 }}>
        <p style={{ color: 'var(--txt-mut)', fontSize: 16 }}>Role not found.</p>
        <button onClick={() => navigate('/')} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '10px 20px', cursor: 'pointer', fontFamily: 'inherit', fontSize: 14, fontWeight: 600 }}>
          Back to home
        </button>
      </div>
    );
  }

  const items = NAV[role];
  const phase1 = items.filter(i => i.phase === 1);
  const phase2 = items.filter(i => i.phase === 2);
  const cfg = ROLE_CONFIG[role];
  const RoleIcon = cfg.icon;

  const handleExport = async () => {
    setExporting(true);
    try {
      await exportRoleGuidePdf(contentRef, role);
    } finally {
      setExporting(false);
    }
  };

  return (
    <div data-theme="dark" style={{ minHeight: '100vh', background: 'var(--shell)', color: 'var(--txt)' }}>
      {/* Header */}
      <div style={{ borderBottom: '1px solid var(--line)', background: 'rgba(14,15,18,0.9)', backdropFilter: 'blur(14px)', position: 'sticky', top: 0, zIndex: 50 }}>
        <div style={{ maxWidth: 900, margin: '0 auto', padding: '0 24px', height: 60, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <button onClick={() => navigate('/')} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 6, fontSize: 14, fontFamily: 'inherit', padding: '4px 0' }}
              onMouseEnter={e => (e.currentTarget.style.color = 'var(--txt)')}
              onMouseLeave={e => (e.currentTarget.style.color = 'var(--txt-mut)')}
            >
              <ArrowLeft size={16} /> Back
            </button>
            <span style={{ color: 'var(--line2)', fontSize: 18 }}>|</span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <BrandMark size="sm" />
              <span style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 15, color: 'var(--txt)' }}>
                NForce OneHR
              </span>
            </div>
          </div>
          <button onClick={handleExport} disabled={exporting} style={{
            background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8,
            color: 'var(--txt)', fontSize: 13, fontFamily: 'inherit', fontWeight: 500,
            padding: '7px 14px', cursor: exporting ? 'not-allowed' : 'pointer',
            display: 'flex', alignItems: 'center', gap: 6, opacity: exporting ? 0.6 : 1,
          }}>
            <Download size={14} /> {exporting ? 'Exporting…' : 'Download PDF'}
          </button>
        </div>
      </div>

      {/* Page content — captured for PDF */}
      <div ref={contentRef} style={{ background: 'var(--shell)' }}>
        {/* Hero */}
        <div style={{ background: 'linear-gradient(to bottom, var(--panel), var(--shell))', borderBottom: '1px solid var(--line)', padding: '56px 24px 48px' }}>
          <div style={{ maxWidth: 900, margin: '0 auto' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginBottom: 24 }}>
              <div style={{ width: 64, height: 64, borderRadius: 16, background: cfg.bg, border: '1px solid rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <RoleIcon size={28} color={cfg.color} />
              </div>
              <div>
                <p style={{ color: 'var(--txt-dim)', fontSize: 12, fontWeight: 600, letterSpacing: '0.08em', textTransform: 'uppercase', margin: '0 0 4px' }}>
                  Role Guide
                </p>
                <h1 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(1.6rem, 4vw, 2.4rem)', letterSpacing: '-0.03em', margin: 0, color: 'var(--txt)' }}>
                  {role}
                </h1>
              </div>
            </div>
            <p style={{ color: 'var(--txt-mut)', fontSize: 15, lineHeight: 1.65, margin: 0, maxWidth: 640 }}>
              This is the complete list of features available to the <strong style={{ color: 'var(--txt)' }}>{role}</strong> role
              in NForce OneHR, sourced directly from the platform's navigation configuration.
              Phase 2 features are listed but marked as coming soon.
            </p>
          </div>
        </div>

        {/* Feature list */}
        <div style={{ maxWidth: 900, margin: '0 auto', padding: '48px 24px 80px' }}>
          {/* Phase 1 — Live features */}
          <div style={{ marginBottom: 48 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20 }}>
              <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 14, color: 'var(--txt)', margin: 0, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                Available now
              </h2>
              <span style={{ background: 'rgba(47,182,124,0.12)', color: 'var(--ok)', border: '1px solid rgba(47,182,124,0.2)', borderRadius: 999, padding: '2px 10px', fontSize: 11, fontWeight: 600 }}>
                {phase1.length} features
              </span>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {phase1.map(item => <NavItemRow key={item.key} item={item} />)}
            </div>
          </div>

          {/* Phase 2 — Coming soon */}
          {phase2.length > 0 && (
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20 }}>
                <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 14, color: 'var(--txt)', margin: 0, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                  On the roadmap
                </h2>
                <span style={{ background: 'rgba(224,169,59,0.12)', color: 'var(--warn)', border: '1px solid rgba(224,169,59,0.22)', borderRadius: 999, padding: '2px 10px', fontSize: 11, fontWeight: 600 }}>
                  {phase2.length} coming soon
                </span>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {phase2.map(item => <NavItemRow key={item.key} item={item} />)}
              </div>
            </div>
          )}

          {/* Footer note in PDF */}
          <div style={{ marginTop: 56, padding: '20px 24px', border: '1px solid var(--line)', borderRadius: 10, background: 'var(--panel)', display: 'flex', alignItems: 'center', gap: 14 }}>
            <BrandMark size="sm" />
            <div>
              <p style={{ margin: 0, fontSize: 12, color: 'var(--txt-mut)' }}>
                NForce OneHR — {role} Role Guide. Generated {new Date().toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' })}.
                Feature list sourced from the live navigation configuration.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
