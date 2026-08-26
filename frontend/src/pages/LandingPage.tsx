import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Users, User, Clock, FileText, Package, Shield, GitBranch, AlertTriangle,
  MessageCircle, BarChart2, FileCheck, CheckSquare, Building2, LogIn,
  ChevronRight, Menu, X, Bot, MapPin, Check,
} from 'lucide-react';
import { BrandMark } from '../components/BrandMark';
import './LandingPage.css';

// ── Scroll reveal ────────────────────────────────────────────────────────────
type FadeVariant = 'up' | 'left' | 'right';

function FadeIn({
  children,
  delay = 0,
  style,
  variant = 'up',
}: {
  children: React.ReactNode;
  delay?: number;
  style?: React.CSSProperties;
  variant?: FadeVariant;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ob = new IntersectionObserver(
      ([e]) => { if (e.isIntersecting) { el.classList.add('lp-visible'); ob.unobserve(el); } },
      { threshold: 0.04, rootMargin: '0px 0px -32px 0px' }
    );
    ob.observe(el);
    return () => ob.disconnect();
  }, []);
  const cls = variant === 'left' ? 'lp-fade-left' : variant === 'right' ? 'lp-fade-right' : 'lp-fade';
  return (
    <div
      ref={ref}
      className={cls}
      style={{ transitionDelay: delay ? `${delay}ms` : undefined, ...style }}
    >
      {children}
    </div>
  );
}

// ── Nav ──────────────────────────────────────────────────────────────────────
const NAV_LINKS = [
  { label: 'Features',    href: '#features' },
  { label: 'AI Agents',   href: '#ai-agents' },
  { label: 'Platform',    href: '#platform' },
  { label: 'How it Works',href: '#how-it-works' },
  { label: 'Pricing',     href: '#pricing' },
];

function LandingNav() {
  const navigate = useNavigate();
  const [scrolled, setScrolled] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const fn = () => setScrolled(window.scrollY > 24);
    window.addEventListener('scroll', fn, { passive: true });
    return () => window.removeEventListener('scroll', fn);
  }, []);

  function scrollTo(id: string) {
    setOpen(false);
    document.querySelector(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  return (
    <nav className={`lp-nav${scrolled ? ' lp-nav-scrolled' : ''}`}>
      <div style={{ maxWidth: 1200, margin: '0 auto', padding: '0 24px', height: 60, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        {/* Logo */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }} onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}>
          <BrandMark size="sm" />
          <span style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 16, color: 'var(--txt)', letterSpacing: '-0.02em' }}>
            NForce <span style={{ color: 'var(--brand-bright)' }}>OneHR</span>
          </span>
        </div>
        {/* Desktop links */}
        <div className="lp-nav-links-desktop" style={{ display: 'flex', alignItems: 'center', gap: 28 }}>
          {NAV_LINKS.map(l => (
            <button
              key={l.label}
              onClick={() => scrollTo(l.href)}
              style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', fontSize: 14, fontFamily: 'inherit', fontWeight: 500, padding: 0, transition: 'color 0.15s' }}
              onMouseEnter={e => (e.currentTarget.style.color = 'var(--txt)')}
              onMouseLeave={e => (e.currentTarget.style.color = 'var(--txt-mut)')}
            >{l.label}</button>
          ))}
        </div>
        {/* CTAs */}
        <div className="lp-nav-links-desktop" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <button
            onClick={() => navigate('/login')}
            style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 8, color: 'var(--txt)', fontSize: 14, fontFamily: 'inherit', fontWeight: 500, padding: '7px 16px', cursor: 'pointer', transition: 'border-color 0.15s' }}
            onMouseEnter={e => ((e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--brand-bright)')}
            onMouseLeave={e => ((e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--line2)')}
          >Sign In</button>
          <button
            onClick={() => scrollTo('#early-access')}
            style={{ background: 'var(--brand)', border: 'none', borderRadius: 8, color: '#fff', fontSize: 14, fontFamily: 'inherit', fontWeight: 600, padding: '7px 16px', cursor: 'pointer', transition: 'background 0.15s' }}
            onMouseEnter={e => ((e.currentTarget as HTMLButtonElement).style.background = 'var(--brand-bright)')}
            onMouseLeave={e => ((e.currentTarget as HTMLButtonElement).style.background = 'var(--brand)')}
          >Request Early Access</button>
        </div>
        {/* Mobile hamburger */}
        <button className="lp-mobile-menu-btn" onClick={() => setOpen(!open)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt)', padding: 4 }}>
          {open ? <X size={20} /> : <Menu size={20} />}
        </button>
      </div>
      {open && (
        <div style={{ background: 'var(--panel)', borderTop: '1px solid var(--line)', padding: '16px 24px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {NAV_LINKS.map(l => (
            <button key={l.label} onClick={() => scrollTo(l.href)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', fontSize: 15, fontFamily: 'inherit', fontWeight: 500, padding: '10px 0', textAlign: 'left' }}>{l.label}</button>
          ))}
          <div style={{ marginTop: 12, display: 'flex', gap: 8, flexDirection: 'column' }}>
            <button onClick={() => navigate('/login')} style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, color: 'var(--txt)', fontSize: 14, fontFamily: 'inherit', fontWeight: 500, padding: '10px', cursor: 'pointer' }}>Sign In</button>
            <button onClick={() => { setOpen(false); scrollTo('#early-access'); }} style={{ background: 'var(--brand)', border: 'none', borderRadius: 8, color: '#fff', fontSize: 14, fontFamily: 'inherit', fontWeight: 600, padding: '10px', cursor: 'pointer' }}>Request Early Access</button>
          </div>
        </div>
      )}
    </nav>
  );
}

// ── Hero ─────────────────────────────────────────────────────────────────────
function LandingHero() {
  const navigate = useNavigate();
  function scrollTo(id: string) {
    document.querySelector(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  return (
    <section style={{ position: 'relative', padding: '144px 24px 96px', overflow: 'hidden' }}>
      {/* Ambient mesh */}
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(ellipse 80% 55% at 50% 0%, rgba(177,17,22,0.13) 0%, transparent 68%)' }} />
        <div className="lp-blob-1" style={{ position: 'absolute', width: 680, height: 680, borderRadius: '50%', background: 'radial-gradient(circle, rgba(177,17,22,0.11) 0%, transparent 70%)', left: '58%', top: '-18%' }} />
        <div className="lp-blob-2" style={{ position: 'absolute', width: 480, height: 480, borderRadius: '50%', background: 'radial-gradient(circle, rgba(228,55,61,0.06) 0%, transparent 70%)', right: '68%', top: '28%' }} />
        <div className="lp-blob-3" style={{ position: 'absolute', width: 560, height: 560, borderRadius: '50%', background: 'radial-gradient(circle, rgba(122,12,16,0.09) 0%, transparent 70%)', left: '42%', bottom: '-8%' }} />
        <div style={{ position: 'absolute', inset: 0, backgroundImage: 'linear-gradient(rgba(255,255,255,0.013) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.013) 1px, transparent 1px)', backgroundSize: '52px 52px' }} />
      </div>

      <div style={{ maxWidth: 880, margin: '0 auto', position: 'relative', textAlign: 'center' }}>
        {/* Badge */}
        <FadeIn>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: 36 }}>
            <span style={{ background: 'rgba(228,55,61,0.1)', border: '1px solid rgba(228,55,61,0.22)', borderRadius: 999, padding: '3px 14px 3px 6px', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <span style={{ background: 'var(--brand-bright)', borderRadius: 999, padding: '2px 9px', fontSize: 10, fontWeight: 700, color: '#fff', letterSpacing: '0.06em' }}>NOW LIVE</span>
              <span style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Built for NForce. Opening up to the world.</span>
            </span>
          </div>
        </FadeIn>

        {/* Headline */}
        <FadeIn delay={70}>
          <h1 style={{
            fontFamily: "'Space Grotesk', sans-serif",
            fontWeight: 700,
            fontSize: 'clamp(3rem, 7.8vw, 6rem)',
            lineHeight: 1.04,
            letterSpacing: '-0.045em',
            margin: '0 0 28px',
            background: 'linear-gradient(172deg, #ffffff 0%, rgba(255,255,255,0.68) 100%)',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
            backgroundClip: 'text',
          }}>
            Put AI agents to work<br />
            for your{' '}
            <span style={{ WebkitTextFillColor: 'var(--brand-bright)', color: 'var(--brand-bright)' }}>
              HR team.
            </span>
          </h1>
        </FadeIn>

        {/* Sub */}
        <FadeIn delay={150}>
          <p style={{ color: 'var(--txt-mut)', fontSize: 'clamp(1rem, 2.2vw, 1.18rem)', lineHeight: 1.68, margin: '0 auto 44px', maxWidth: 520 }}>
            Attendance, leave, approvals, documents, and your entire org chart
            — in one platform your team will actually want to use.
            AI agents are joining the team soon.
          </p>
        </FadeIn>

        {/* CTAs */}
        <FadeIn delay={230}>
          <div style={{ display: 'flex', gap: 12, justifyContent: 'center', flexWrap: 'wrap', marginBottom: 60 }}>
            <button
              onClick={() => navigate('/login')}
              className="lp-btn-primary"
              style={{ background: 'var(--brand)', border: 'none', borderRadius: 10, color: '#fff', fontSize: 15, fontFamily: 'inherit', fontWeight: 600, padding: '14px 30px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}
            >
              <LogIn size={16} /> Sign In
            </button>
            <button
              onClick={() => scrollTo('#platform')}
              style={{ background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 10, color: 'var(--txt)', fontSize: 15, fontFamily: 'inherit', fontWeight: 500, padding: '14px 30px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8, transition: 'background 0.15s, border-color 0.15s' }}
              onMouseEnter={e => { const b = e.currentTarget as HTMLButtonElement; b.style.background = 'rgba(255,255,255,0.09)'; b.style.borderColor = 'rgba(255,255,255,0.18)'; }}
              onMouseLeave={e => { const b = e.currentTarget as HTMLButtonElement; b.style.background = 'rgba(255,255,255,0.05)'; b.style.borderColor = 'rgba(255,255,255,0.1)'; }}
            >
              See the platform <ChevronRight size={16} />
            </button>
          </div>
        </FadeIn>

        {/* Trust */}
        <FadeIn delay={310}>
          <p style={{ color: 'var(--txt-dim)', fontSize: 12, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--ok)', display: 'inline-block', boxShadow: '0 0 6px rgba(47,182,124,0.5)' }} />
            Currently powering HR for NForce's own team
          </p>
        </FadeIn>
      </div>

      {/* Product frame with glow */}
      <FadeIn delay={420} style={{ marginTop: 72 }}>
        <div style={{ maxWidth: 1020, margin: '0 auto', position: 'relative' }}>
          <div style={{
            position: 'absolute', left: '50%', top: '-14%',
            transform: 'translateX(-50%)',
            width: '82%', height: '58%',
            background: 'radial-gradient(ellipse at 50% 65%, rgba(228,55,61,0.26) 0%, rgba(177,17,22,0.11) 42%, transparent 68%)',
            filter: 'blur(60px)',
            animation: 'lp-glow-breathe 6s ease-in-out infinite',
            zIndex: 0,
          }} />
          <div style={{ position: 'relative', zIndex: 1, borderRadius: 14, border: '1px solid rgba(255,255,255,0.07)', boxShadow: '0 52px 120px rgba(0,0,0,0.72)', overflow: 'hidden' }}>
            {/* Browser chrome */}
            <div style={{ background: 'rgba(16,18,22,0.99)', borderBottom: '1px solid rgba(255,255,255,0.05)', padding: '11px 16px', display: 'flex', alignItems: 'center', gap: 12 }}>
              <div style={{ display: 'flex', gap: 6 }}>
                {['#FF5F57','#FFBD2E','#28C840'].map(c => <div key={c} style={{ width: 11, height: 11, borderRadius: '50%', background: c }} />)}
              </div>
              <div style={{ flex: 1, background: 'rgba(255,255,255,0.04)', borderRadius: 6, padding: '4px 12px', fontSize: 11, color: 'var(--txt-dim)', textAlign: 'center', fontFamily: "'JetBrains Mono', monospace" }}>
                app.nforceone.com/dashboard
              </div>
            </div>
            {/* App shell */}
            <div style={{ display: 'grid', gridTemplateColumns: '188px 1fr', background: 'var(--shell)', minHeight: 370 }}>
              {/* Sidebar */}
              <div style={{ background: 'var(--panel)', borderRight: '1px solid var(--line)', padding: '14px 0' }}>
                <div style={{ padding: '0 12px 14px', borderBottom: '1px solid var(--line)', marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{ width: 28, height: 28, borderRadius: 7, background: 'var(--brand)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                    <span style={{ color: '#fff', fontSize: 10, fontWeight: 700, fontFamily: "'Space Grotesk', sans-serif" }}>NF</span>
                  </div>
                  <div>
                    <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt)', fontFamily: "'Space Grotesk', sans-serif", lineHeight: 1.2 }}>NForce OneHR</div>
                    <div style={{ fontSize: 9, color: 'var(--txt-dim)' }}>Super Admin</div>
                  </div>
                </div>
                {[
                  { label: 'Dashboard', active: true },
                  { label: 'My Team', active: false },
                  { label: 'Attendance', active: false },
                  { label: 'Leave & Holidays', active: false },
                  { label: 'Approvals', active: false },
                  { label: 'Employees', active: false },
                  { label: 'Documents', active: false },
                ].map(item => (
                  <div
                    key={item.label}
                    style={{
                      padding: '7px 10px', margin: '0 8px 2px', borderRadius: 6, fontSize: 12,
                      fontWeight: item.active ? 600 : 400,
                      color: item.active ? 'var(--txt)' : 'var(--txt-mut)',
                      background: item.active ? 'var(--raised)' : 'transparent',
                      cursor: 'default', display: 'flex', alignItems: 'center', gap: 7,
                    }}
                  >
                    <div style={{ width: 5, height: 5, borderRadius: '50%', background: item.active ? 'var(--brand-bright)' : 'transparent', flexShrink: 0 }} />
                    {item.label}
                  </div>
                ))}
              </div>
              {/* Main */}
              <div style={{ padding: '22px 24px', background: 'var(--shell)' }}>
                <div style={{ marginBottom: 18, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 17, margin: '0 0 2px', color: 'var(--txt)' }}>Dashboard</h2>
                    <p style={{ fontSize: 11, color: 'var(--txt-dim)', margin: 0 }}>Monday, 25 Aug 2026</p>
                  </div>
                  <div style={{ width: 30, height: 17, background: 'var(--raised)', borderRadius: 999, position: 'relative' }}>
                    <div style={{ position: 'absolute', right: 2, top: 2, width: 13, height: 13, borderRadius: '50%', background: 'var(--brand-bright)' }} />
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 9, marginBottom: 18 }}>
                  {[
                    { val: '142', label: 'Present Today', color: 'var(--ok)' },
                    { val: '8',   label: 'On Leave',      color: 'var(--info)' },
                    { val: '3',   label: 'Pending',       color: 'var(--warn)' },
                    { val: '7.5h',label: 'Avg Hours',     color: 'var(--brand-bright)' },
                  ].map(s => (
                    <div key={s.label} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 9, padding: '11px 13px' }}>
                      <div style={{ fontSize: 19, fontWeight: 700, fontFamily: "'Space Grotesk', sans-serif", color: s.color, marginBottom: 2 }}>{s.val}</div>
                      <div style={{ fontSize: 9.5, color: 'var(--txt-dim)', lineHeight: 1.3 }}>{s.label}</div>
                    </div>
                  ))}
                </div>
                <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 9, padding: '13px 15px' }}>
                  <div style={{ fontSize: 10.5, fontWeight: 600, color: 'var(--txt)', marginBottom: 9 }}>Attendance Overview — This Week</div>
                  <div style={{ display: 'flex', alignItems: 'flex-end', gap: 7, height: 60 }}>
                    {[72, 88, 64, 94, 81, 42, 0].map((h, i) => (
                      <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
                        {h > 0 && (
                          <div style={{ width: '100%', background: i === 3 ? 'var(--brand-bright)' : 'var(--raised2)', borderRadius: '3px 3px 0 0', height: `${h * 0.65}%` }} />
                        )}
                        <div style={{ fontSize: 8, color: 'var(--txt-dim)' }}>{['M','T','W','T','F','S','S'][i]}</div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </FadeIn>
    </section>
  );
}

// ── Stats strip ───────────────────────────────────────────────────────────────
const STATS = [
  { val: '15+',  label: 'HR modules built',         note: 'Phase 1 complete' },
  { val: '4',    label: 'Role-based experiences',    note: 'Employee → Super Admin' },
  { val: '100%', label: 'Real-time data',            note: 'No daily exports' },
  { val: '0',    label: 'Spreadsheets needed',       note: 'All consolidated here' },
];

function LandingStats() {
  return (
    <div style={{ background: 'var(--panel)', borderTop: '1px solid var(--line)', borderBottom: '1px solid var(--line)' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 0 }}>
        {STATS.map((s, i) => (
          <FadeIn key={s.val} delay={i * 55}>
            <div style={{ padding: '36px 28px', textAlign: 'center', borderRight: i < STATS.length - 1 ? '1px solid var(--line)' : 'none' }}>
              <div style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(2.2rem, 4vw, 3rem)', letterSpacing: '-0.05em', color: 'var(--txt)', marginBottom: 6, lineHeight: 1 }}>{s.val}</div>
              <div style={{ fontSize: 14, color: 'var(--txt-mut)', marginBottom: 4 }}>{s.label}</div>
              <div style={{ fontSize: 11, color: 'var(--brand-bright)', fontWeight: 600 }}>{s.note}</div>
            </div>
          </FadeIn>
        ))}
      </div>
    </div>
  );
}

// ── Product showcase ──────────────────────────────────────────────────────────
const SHOWCASE_ITEMS = [
  {
    src: '/assets/screenshots/app-dashboard.png',
    label: 'Real-time Dashboard',
    desc: 'Attendance stats, pending approvals, and team pulse — live, on one screen.',
    pos: 'top center',
  },
  {
    src: '/assets/screenshots/app-ai-agents.png',
    label: 'AI Agents Section',
    desc: 'Named AI agents designed for specific HR workflows — routing, detecting, predicting.',
    pos: 'top center',
  },
];

function LandingProductShowcase() {
  return (
    <section id="platform" style={{ padding: '100px 24px 112px', background: 'var(--shell)' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <FadeIn>
          <div style={{ textAlign: 'center', marginBottom: 64 }}>
            <p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>SEE IT IN ACTION</p>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 2.9rem)', letterSpacing: '-0.035em', margin: '0 0 16px', color: 'var(--txt)', lineHeight: 1.1 }}>
              Built for the way your team actually works.
            </h2>
            <p style={{ color: 'var(--txt-mut)', fontSize: 16, maxWidth: 480, margin: '0 auto', lineHeight: 1.6 }}>
              Every screen designed for speed — no training manual required.
            </p>
          </div>
        </FadeIn>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))', gap: 28 }}>
          {SHOWCASE_ITEMS.map((s, i) => (
            <FadeIn key={s.label} delay={i * 90} variant={i % 2 === 0 ? 'left' : 'right'}>
              <div className="lp-showcase-frame">
                <div style={{ background: 'rgba(14,16,20,0.99)', borderBottom: '1px solid rgba(255,255,255,0.05)', padding: '9px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div style={{ display: 'flex', gap: 5 }}>
                    {['#FF5F57','#FFBD2E','#28C840'].map(c => <div key={c} style={{ width: 9, height: 9, borderRadius: '50%', background: c }} />)}
                  </div>
                  <div style={{ flex: 1, background: 'rgba(255,255,255,0.04)', borderRadius: 4, padding: '3px 10px', fontSize: 10, color: 'var(--txt-dim)', fontFamily: "'JetBrains Mono', monospace" }}>
                    app.nforceone.com
                  </div>
                </div>
                <div style={{ overflow: 'hidden', height: 300 }}>
                  <img
                    src={s.src}
                    alt={s.label}
                    style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: s.pos, display: 'block' }}
                  />
                </div>
                <div style={{ padding: '18px 20px', background: 'var(--panel)', borderTop: '1px solid var(--line)' }}>
                  <div style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 14, color: 'var(--txt)', marginBottom: 5 }}>{s.label}</div>
                  <div style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.55 }}>{s.desc}</div>
                </div>
              </div>
            </FadeIn>
          ))}
        </div>
      </div>
    </section>
  );
}

// ── Features ──────────────────────────────────────────────────────────────────
const FEATURES = [
  { icon: Users,       title: 'People Directory & Org Hierarchy', desc: 'See your entire company structure at a glance, navigate reporting lines instantly, and find anyone in seconds.' },
  { icon: Clock,       title: 'Attendance & Leave',               desc: 'Check in from anywhere, track hours automatically, and manage leave balances without a single spreadsheet.' },
  { icon: CheckSquare, title: 'Approvals & Workflows',            desc: "Every decision — leave, expenses, asset requests — lives in one Approval Center, so nothing gets lost in email." },
  { icon: FileText,    title: 'Documents & Compliance',           desc: 'Track required documents and policy acknowledgments automatically, with reminders before anything expires.' },
  { icon: Package,     title: 'Assets & Expenses',                desc: "From laptop assignments to expense claims, track what's owned, who has it, and what's pending approval." },
  { icon: Shield,      title: 'Audit & Security',                 desc: "A full, searchable history of every sensitive action — role changes, approvals, account changes — for real accountability." },
];

function LandingFeatures() {
  return (
    <section id="features" style={{ padding: '112px 24px 100px', background: 'var(--panel)' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <FadeIn>
          <div style={{ marginBottom: 68, maxWidth: 580 }}>
            <p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>WHAT'S INSIDE</p>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(2rem, 4vw, 3.1rem)', letterSpacing: '-0.038em', margin: '0 0 18px', color: 'var(--txt)', lineHeight: 1.08 }}>
              Everything your team already needs — built in, not bolted on.
            </h2>
            <p style={{ color: 'var(--txt-mut)', fontSize: 16, lineHeight: 1.65, margin: 0 }}>
              No more spreadsheets, email threads, or a dozen disconnected tools.
            </p>
          </div>
        </FadeIn>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(310px, 1fr))', gap: 1, background: 'var(--line)' }}>
          {FEATURES.map((f, i) => {
            const Icon = f.icon;
            return (
              <FadeIn key={f.title} delay={Math.floor(i / 2) * 70 + (i % 2) * 35}>
                <div className="lp-feature-card" style={{ background: 'var(--panel)', padding: '36px 30px', height: '100%', boxSizing: 'border-box' }}>
                  <div style={{ width: 46, height: 46, borderRadius: 11, background: 'rgba(177,17,22,0.11)', border: '1px solid rgba(177,17,22,0.18)', display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 20 }}>
                    <Icon size={20} color="var(--brand-bright)" />
                  </div>
                  <h3 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 15.5, color: 'var(--txt)', margin: '0 0 10px', lineHeight: 1.3 }}>{f.title}</h3>
                  <p style={{ color: 'var(--txt-mut)', fontSize: 13.5, lineHeight: 1.68, margin: 0 }}>{f.desc}</p>
                </div>
              </FadeIn>
            );
          })}
        </div>
      </div>
    </section>
  );
}

// ── AI Agents — editorial layout ──────────────────────────────────────────────
const SIDE_AGENTS = [
  {
    verb: 'Answers',
    name: 'HR Assistant Agent',
    icon: MessageCircle,
    desc: "Employees ask anything — leave balances, policy rules, benefits — in plain language. Responds instantly, accurately, without a helpdesk ticket.",
    eta: 'Q4 2026',
  },
  {
    verb: 'Monitors',
    name: 'Compliance Guardian',
    icon: FileCheck,
    desc: 'Tracks every document expiry and policy acknowledgment gap company-wide. Alerts HR before anything lapses — not after an audit finds it.',
    eta: '2027',
  },
];

const BOTTOM_AGENTS = [
  {
    verb: 'Detects',
    name: 'Anomaly Sentinel',
    icon: AlertTriangle,
    desc: "Automatically surfaces irregular attendance patterns — missed punches, unusual hours, chronic lateness — before they become real problems.",
    eta: 'Q4 2026',
  },
  {
    verb: 'Predicts',
    name: 'Workforce Forecaster',
    icon: BarChart2,
    desc: "Projects headcount gaps, attrition risk, and leave coverage shortfalls before you need the report. Know what's coming, not just what happened.",
    eta: '2027',
  },
  {
    verb: 'Maps',
    name: 'Org Development Agent',
    icon: Building2,
    desc: 'Analyzes reporting structure, span-of-control depth, and headcount distribution to surface org design recommendations proactively.',
    eta: '2027',
  },
];

function LandingAI() {
  return (
    <section id="ai-agents" style={{ position: 'relative', padding: '120px 0 128px', overflow: 'hidden', background: 'var(--shell)' }}>
      {/* Red ambient + grid */}
      <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(ellipse 75% 65% at 50% 45%, rgba(177,17,22,0.08) 0%, transparent 72%)', pointerEvents: 'none' }} />
      <div style={{ position: 'absolute', inset: 0, backgroundImage: 'linear-gradient(rgba(228,55,61,0.025) 1px, transparent 1px), linear-gradient(90deg, rgba(228,55,61,0.025) 1px, transparent 1px)', backgroundSize: '72px 72px', pointerEvents: 'none' }} />

      <div style={{ maxWidth: 1100, margin: '0 auto', padding: '0 24px', position: 'relative' }}>
        {/* Header */}
        <FadeIn>
          <div style={{ marginBottom: 60 }}>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: 22, background: 'rgba(228,55,61,0.07)', border: '1px solid rgba(228,55,61,0.18)', borderRadius: 999, padding: '5px 14px 5px 10px' }}>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand-bright)', boxShadow: '0 0 8px rgba(228,55,61,0.7)', display: 'inline-block', animation: 'lp-cta-pulse 2.5s ease-in-out infinite' }} />
              <span style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.09em', textTransform: 'uppercase' }}>Roadmap — Actively Being Built</span>
            </div>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(2.3rem, 5.5vw, 3.8rem)', letterSpacing: '-0.043em', margin: '0 0 20px', color: 'var(--txt)', lineHeight: 1.03 }}>
              Meet the AI agents<br />coming to NForce OneHR.
            </h2>
            <p style={{ color: 'var(--txt-mut)', fontSize: 17, lineHeight: 1.68, maxWidth: 560, margin: 0 }}>
              Not chatbots. Autonomous agents that work alongside your HR team — routing, detecting, predicting, and acting on the decisions your team makes every day.
            </p>
          </div>
        </FadeIn>

        {/* Featured agent + 2 side agents */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 330px', gap: 16, marginBottom: 16, alignItems: 'stretch' }}>
          {/* Featured — large editorial */}
          <FadeIn variant="left">
            <div className="lp-featured-agent" style={{ height: '100%' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
                <div style={{ width: 52, height: 52, borderRadius: 13, background: 'rgba(228,55,61,0.14)', border: '1px solid rgba(228,55,61,0.26)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                  <GitBranch size={24} color="var(--brand-bright)" />
                </div>
                <div>
                  <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand-bright)', letterSpacing: '0.1em', textTransform: 'uppercase', marginBottom: 2 }}>ROUTES</div>
                  <div style={{ fontSize: 12, color: 'var(--txt-dim)', fontWeight: 500 }}>Featured Agent</div>
                </div>
              </div>
              <h3 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(1.55rem, 3vw, 2.1rem)', color: 'var(--txt)', margin: '0 0 18px', lineHeight: 1.18, letterSpacing: '-0.028em' }}>
                The Approvals Agent
              </h3>
              <p style={{ color: 'var(--txt-mut)', fontSize: 15.5, lineHeight: 1.72, margin: '0 0 32px' }}>
                Reviews every incoming request and routes only what genuinely needs a human decision.
                Routine low-risk approvals move automatically — with full context already surfaced
                for the ones that reach you.
              </p>
              <div style={{ marginTop: 'auto' }}>
                <span style={{ background: 'rgba(224,169,59,0.1)', color: 'var(--warn)', border: '1px solid rgba(224,169,59,0.2)', borderRadius: 999, padding: '5px 14px', fontSize: 11, fontWeight: 700, letterSpacing: '0.04em' }}>
                  COMING SOON — Q4 2026
                </span>
              </div>
              {/* Decorative bg icon */}
              <div style={{ position: 'absolute', bottom: -8, right: 0, opacity: 0.03, pointerEvents: 'none' }}>
                <GitBranch size={160} />
              </div>
            </div>
          </FadeIn>

          {/* 2 stacked side agents */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {SIDE_AGENTS.map((agent, i) => {
              const Icon = agent.icon;
              return (
                <FadeIn key={agent.name} delay={100 + i * 80} variant="right">
                  <div className="lp-agent-card" style={{ padding: '24px', background: 'rgba(22,24,29,0.85)', border: '1px solid rgba(255,255,255,0.07)', borderRadius: 14, flex: 1 }}>
                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
                      <div style={{ width: 38, height: 38, borderRadius: 9, background: 'rgba(228,55,61,0.1)', border: '1px solid rgba(228,55,61,0.18)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                        <Icon size={16} color="var(--brand-bright)" />
                      </div>
                      <div style={{ flex: 1 }}>
                        <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand-bright)', letterSpacing: '0.1em', textTransform: 'uppercase', marginBottom: 4 }}>{agent.verb}</div>
                        <div style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 13.5, color: 'var(--txt)', marginBottom: 8 }}>{agent.name}</div>
                        <p style={{ color: 'var(--txt-mut)', fontSize: 12.5, lineHeight: 1.62, margin: '0 0 12px' }}>{agent.desc}</p>
                        <span style={{ background: 'rgba(224,169,59,0.07)', color: 'var(--warn)', border: '1px solid rgba(224,169,59,0.14)', borderRadius: 999, padding: '2px 9px', fontSize: 10, fontWeight: 600 }}>Coming {agent.eta}</span>
                      </div>
                    </div>
                  </div>
                </FadeIn>
              );
            })}
          </div>
        </div>

        {/* Bottom row — 3 supporting agents */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(290px, 1fr))', gap: 16 }}>
          {BOTTOM_AGENTS.map((agent, i) => {
            const Icon = agent.icon;
            return (
              <FadeIn key={agent.name} delay={200 + i * 70}>
                <div className="lp-agent-card" style={{ padding: '26px 28px', background: 'rgba(22,24,29,0.85)', border: '1px solid rgba(255,255,255,0.07)', borderRadius: 14 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
                    <div style={{ width: 40, height: 40, borderRadius: 10, background: 'rgba(228,55,61,0.1)', border: '1px solid rgba(228,55,61,0.18)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                      <Icon size={18} color="var(--brand-bright)" />
                    </div>
                    <div>
                      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand-bright)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>{agent.verb}</div>
                      <div style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 14, color: 'var(--txt)' }}>{agent.name}</div>
                    </div>
                  </div>
                  <p style={{ color: 'var(--txt-mut)', fontSize: 13.5, lineHeight: 1.65, margin: '0 0 14px' }}>{agent.desc}</p>
                  <span style={{ background: 'rgba(224,169,59,0.07)', color: 'var(--warn)', border: '1px solid rgba(224,169,59,0.14)', borderRadius: 999, padding: '2px 9px', fontSize: 10, fontWeight: 600 }}>Coming {agent.eta}</span>
                </div>
              </FadeIn>
            );
          })}
        </div>

        {/* Why agents footnote */}
        <FadeIn delay={380} style={{ marginTop: 52 }}>
          <div style={{ padding: '22px 28px', background: 'rgba(22,24,29,0.7)', border: '1px solid rgba(255,255,255,0.06)', borderRadius: 12, display: 'flex', alignItems: 'flex-start', gap: 14 }}>
            <Bot size={20} color="var(--brand-bright)" style={{ flexShrink: 0, marginTop: 2 }} />
            <p style={{ color: 'var(--txt-dim)', fontSize: 13.5, lineHeight: 1.65, margin: 0 }}>
              <strong style={{ color: 'var(--txt-mut)', fontWeight: 600 }}>Why agents, not features? </strong>
              Traditional HR software makes your team do the work. These agents take on the routine decisions
              — routing, detecting, predicting — so your HR team focuses on what only humans should do.
              Workday calls their orchestrator "Sana." Ours are purpose-built for mid-market HR teams.
            </p>
          </div>
        </FadeIn>
      </div>
    </section>
  );
}

// ── Brand moment — NForce office photo ────────────────────────────────────────
function LandingBrandMoment() {
  return (
    <section className="lp-brand-section" style={{ minHeight: 400, display: 'flex', alignItems: 'center' }}>
      <img
        src="/assets/brand/nforce-office.webp"
        alt="NForce One office, Hyderabad"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'center', zIndex: 0 }}
      />
      <div className="lp-brand-overlay" />
      <div style={{ maxWidth: 1100, margin: '0 auto', padding: '80px 24px', position: 'relative', zIndex: 2, width: '100%' }}>
        <FadeIn variant="left">
          <div style={{ maxWidth: 560 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 18, color: 'var(--brand-bright)', fontSize: 12, fontWeight: 600 }}>
              <MapPin size={13} />
              Hyderabad · Dallas
            </div>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(2rem, 4vw, 3.1rem)', letterSpacing: '-0.036em', margin: '0 0 20px', color: 'var(--txt)', lineHeight: 1.1 }}>
              Built by NForce One.<br />
              <span style={{ color: 'var(--brand-bright)' }}>Scale at Speed.</span>
            </h2>
            <p style={{ color: 'var(--txt-mut)', fontSize: 15.5, lineHeight: 1.7, margin: '0 0 30px' }}>
              NForce OneHR started as an internal tool — built by our own team to solve real HR headaches.
              Now we're opening it up. Be among the first companies to use it.
            </p>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <img src="/assets/brand/nforce-logo.png" alt="NForce One" style={{ width: 42, height: 42, borderRadius: '50%', objectFit: 'cover', border: '1px solid rgba(255,255,255,0.1)' }} />
              <div>
                <div style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>NForce One</div>
                <div style={{ fontSize: 12, color: 'var(--brand-bright)', fontWeight: 600 }}>Let's Do IT!</div>
              </div>
            </div>
          </div>
        </FadeIn>
      </div>
    </section>
  );
}

// ── Roles ─────────────────────────────────────────────────────────────────────
const ROLES = [
  { slug: 'employee',   label: 'Employee',   icon: User,     color: '#2FB67C', bg: 'rgba(47,182,124,0.1)',  desc: 'Manage your own attendance, leave, documents, and requests.' },
  { slug: 'manager',    label: 'Manager',    icon: Users,    color: '#4C8DD6', bg: 'rgba(76,141,214,0.1)',  desc: 'Everything your team needs, plus approvals and team-wide visibility.' },
  { slug: 'hr-admin',   label: 'HR Admin',   icon: Building2,color: '#E0A93B', bg: 'rgba(224,169,59,0.1)', desc: 'Run HR operations — employee records, documents, policies, and compliance.' },
  { slug: 'super-admin',label: 'Super Admin',icon: Shield,   color: '#E4373D', bg: 'rgba(228,55,61,0.1)',  desc: 'Full system access — users, organization structure, security, and audit.' },
];

function LandingRoles() {
  const navigate = useNavigate();
  return (
    <section id="role-guides" style={{ padding: '112px 24px 100px', background: 'var(--panel)' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <FadeIn>
          <div style={{ textAlign: 'center', marginBottom: 60 }}>
            <p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>ROLE GUIDES</p>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 3rem)', letterSpacing: '-0.036em', margin: '0 0 14px', color: 'var(--txt)', lineHeight: 1.1 }}>
              See exactly what your role can do.
            </h2>
            <p style={{ color: 'var(--txt-mut)', fontSize: 15, margin: 0 }}>Every role in NForce OneHR has its own set of tools. Pick yours.</p>
          </div>
        </FadeIn>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))', gap: 16 }}>
          {ROLES.map((r, i) => {
            const Icon = r.icon;
            return (
              <FadeIn key={r.slug} delay={i * 60}>
                <div
                  className="lp-role-card"
                  onClick={() => navigate(`/role-guide/${r.slug}`)}
                  style={{ padding: '28px 24px 24px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14 }}
                >
                  <div style={{ width: 48, height: 48, borderRadius: 12, background: r.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 16 }}>
                    <Icon size={22} color={r.color} />
                  </div>
                  <h3 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 16, color: 'var(--txt)', margin: '0 0 10px' }}>{r.label}</h3>
                  <p style={{ color: 'var(--txt-mut)', fontSize: 13.5, lineHeight: 1.58, margin: '0 0 20px' }}>{r.desc}</p>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: r.color, fontSize: 13, fontWeight: 600 }}>
                    See role guide <ChevronRight size={14} />
                  </div>
                </div>
              </FadeIn>
            );
          })}
        </div>
      </div>
    </section>
  );
}

// ── How it works ──────────────────────────────────────────────────────────────
const STEPS = [
  { n: 1, icon: Building2,   title: 'Your admin sets up your organization',         desc: 'Departments, designations, locations, and reporting lines — configured once, applied everywhere.' },
  { n: 2, icon: LogIn,       title: 'Everyone signs in and gets to work',           desc: "Each employee sees exactly what's relevant to their role — nothing more, nothing less." },
  { n: 3, icon: CheckSquare, title: 'Approvals and requests just flow',             desc: 'Leave, expenses, and asset requests move through one clear Approval Center — no more chasing people down.' },
];

function LandingHowItWorks() {
  return (
    <section id="how-it-works" style={{ padding: '112px 24px 100px', background: 'var(--shell)' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <FadeIn>
          <div style={{ textAlign: 'center', marginBottom: 72 }}>
            <p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>HOW IT WORKS</p>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 3rem)', letterSpacing: '-0.036em', margin: 0, color: 'var(--txt)', lineHeight: 1.1 }}>
              Up and running in minutes, not months.
            </h2>
          </div>
        </FadeIn>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 32, position: 'relative' }}>
          {STEPS.map((s, i) => {
            const Icon = s.icon;
            return (
              <FadeIn key={s.n} delay={i * 90}>
                <div style={{ textAlign: 'center', position: 'relative' }}>
                  {i < STEPS.length - 1 && <div className="lp-step-connector lp-hide-mobile" />}
                  <div style={{ position: 'relative', display: 'inline-block', marginBottom: 20 }}>
                    <div style={{ width: 60, height: 60, borderRadius: '50%', background: 'var(--panel)', border: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto' }}>
                      <Icon size={24} color="var(--brand-bright)" />
                    </div>
                    <div style={{ position: 'absolute', top: -6, right: -6, width: 20, height: 20, borderRadius: '50%', background: 'var(--brand)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 700, color: '#fff', fontFamily: "'Space Grotesk', sans-serif" }}>
                      {s.n}
                    </div>
                  </div>
                  <h3 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 16, color: 'var(--txt)', margin: '0 0 10px', lineHeight: 1.3 }}>{s.title}</h3>
                  <p style={{ color: 'var(--txt-mut)', fontSize: 14, lineHeight: 1.65, margin: 0 }}>{s.desc}</p>
                </div>
              </FadeIn>
            );
          })}
        </div>
      </div>
    </section>
  );
}

// ── Pricing ───────────────────────────────────────────────────────────────────
const TIERS = [
  {
    name: 'Starter',
    target: 'For small teams getting started',
    highlight: false,
    features: ['Core HR modules', 'People directory & org chart', 'Attendance & leave', 'Up to 25 employees'],
  },
  {
    name: 'Growth',
    target: 'For growing companies with multiple departments',
    highlight: true,
    features: ['Everything in Starter', 'Multi-department workflows', 'Approval center', 'Documents & compliance', 'Unlimited employees'],
  },
  {
    name: 'Enterprise',
    target: 'For large organizations with complex structures',
    highlight: false,
    features: ['Everything in Growth', 'Custom integrations', 'Advanced audit & security', 'Dedicated support', 'SLA guarantee'],
  },
];

function LandingPricing() {
  function scrollTo(id: string) { document.querySelector(id)?.scrollIntoView({ behavior: 'smooth' }); }
  return (
    <section id="pricing" style={{ padding: '112px 24px 100px', background: 'var(--panel)' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <FadeIn>
          <div style={{ textAlign: 'center', marginBottom: 64 }}>
            <p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>PRICING</p>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 3rem)', letterSpacing: '-0.036em', margin: '0 0 14px', color: 'var(--txt)', lineHeight: 1.1 }}>
              Simple pricing, coming soon.
            </h2>
            <p style={{ color: 'var(--txt-mut)', fontSize: 15, margin: 0 }}>
              NForce OneHR is in early access. Reach out and we'll find the right fit for your team.
            </p>
          </div>
        </FadeIn>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 20 }}>
          {TIERS.map((t, i) => (
            <FadeIn key={t.name} delay={i * 70}>
              <div
                className="lp-pricing-card"
                style={{
                  padding: '32px 28px',
                  background: t.highlight ? 'var(--raised)' : 'var(--panel)',
                  border: `1px solid ${t.highlight ? 'rgba(228,55,61,0.35)' : 'var(--line)'}`,
                  borderRadius: 14,
                  position: 'relative',
                }}
              >
                {t.highlight && (
                  <div style={{ position: 'absolute', top: -1, left: '50%', transform: 'translateX(-50%)', background: 'var(--brand)', color: '#fff', fontSize: 10, fontWeight: 700, padding: '3px 14px', borderRadius: '0 0 8px 8px', letterSpacing: '0.06em' }}>
                    MOST POPULAR
                  </div>
                )}
                <h3 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 20, color: 'var(--txt)', margin: '0 0 6px' }}>{t.name}</h3>
                <p style={{ color: 'var(--txt-mut)', fontSize: 13, margin: '0 0 20px', lineHeight: 1.5 }}>{t.target}</p>
                <div style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 26, color: 'var(--txt)', marginBottom: 24 }}>Contact us</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 28 }}>
                  {t.features.map(f => (
                    <div key={f} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <Check size={14} color="var(--ok)" strokeWidth={2.5} style={{ flexShrink: 0 }} />
                      <span style={{ fontSize: 13.5, color: 'var(--txt-mut)' }}>{f}</span>
                    </div>
                  ))}
                </div>
                <button
                  onClick={() => scrollTo('#early-access')}
                  style={{
                    width: '100%', padding: '12px', borderRadius: 9, fontSize: 14, fontFamily: 'inherit', fontWeight: 600, cursor: 'pointer',
                    background: t.highlight ? 'var(--brand)' : 'var(--raised)',
                    border: t.highlight ? 'none' : '1px solid var(--line2)',
                    color: t.highlight ? '#fff' : 'var(--txt)',
                    transition: 'background 0.15s',
                  }}
                  onMouseEnter={e => { if (t.highlight) (e.currentTarget as HTMLButtonElement).style.background = 'var(--brand-bright)'; }}
                  onMouseLeave={e => { if (t.highlight) (e.currentTarget as HTMLButtonElement).style.background = 'var(--brand)'; }}
                >Get early access</button>
              </div>
            </FadeIn>
          ))}
        </div>
      </div>
    </section>
  );
}

// ── Early access ──────────────────────────────────────────────────────────────
function LandingEarlyAccess() {
  return (
    <section id="early-access" style={{ padding: '112px 24px 100px', background: 'var(--shell)', textAlign: 'center' }}>
      <FadeIn>
        <div style={{ maxWidth: 580, margin: '0 auto' }}>
          <p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>EARLY ACCESS</p>
          <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(2rem, 4.5vw, 3.2rem)', letterSpacing: '-0.04em', margin: '0 0 20px', color: 'var(--txt)', lineHeight: 1.08 }}>
            Built by an HR team, for HR teams.
          </h2>
          <p style={{ color: 'var(--txt-mut)', fontSize: 15.5, lineHeight: 1.7, margin: '0 0 36px' }}>
            NForce OneHR started as an internal tool to solve our own team's real HR headaches —
            now we're opening it up. Be among the first companies to use it.
          </p>
          <a
            href="mailto:hr@nforceone.com?subject=NForce%20OneHR%20Early%20Access"
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 8,
              background: 'var(--brand)', color: '#fff', borderRadius: 10, fontFamily: 'inherit',
              fontWeight: 600, fontSize: 15, padding: '14px 32px', textDecoration: 'none',
              transition: 'background 0.15s',
            }}
            onMouseEnter={e => ((e.currentTarget as HTMLAnchorElement).style.background = 'var(--brand-bright)')}
            onMouseLeave={e => ((e.currentTarget as HTMLAnchorElement).style.background = 'var(--brand)')}
          >
            Request Early Access
          </a>
        </div>
      </FadeIn>
    </section>
  );
}

// ── CTA Banner ────────────────────────────────────────────────────────────────
function LandingCTABanner() {
  const navigate = useNavigate();
  function scrollTo(id: string) { document.querySelector(id)?.scrollIntoView({ behavior: 'smooth' }); }
  return (
    <section style={{ padding: '0 24px 0', background: 'var(--panel)' }}>
      <FadeIn>
        <div style={{
          maxWidth: 1100, margin: '0 auto',
          background: 'linear-gradient(135deg, var(--brand) 0%, rgba(122,12,16,0.95) 100%)',
          borderRadius: 20, padding: '64px 48px', textAlign: 'center', position: 'relative', overflow: 'hidden',
        }}>
          <div style={{ position: 'absolute', inset: 0, backgroundImage: 'linear-gradient(rgba(255,255,255,0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.05) 1px, transparent 1px)', backgroundSize: '40px 40px', borderRadius: 20 }} />
          <div style={{ position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%, -50%)', width: 600, height: 300, background: 'radial-gradient(ellipse, rgba(255,255,255,0.1) 0%, transparent 70%)', borderRadius: '50%' }} />
          <div style={{ position: 'relative' }}>
            <h2 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 'clamp(1.8rem, 4vw, 2.8rem)', letterSpacing: '-0.035em', margin: '0 0 16px', color: '#fff', lineHeight: 1.1 }}>
              Ready to see NForce OneHR in action?
            </h2>
            <p style={{ color: 'rgba(255,255,255,0.75)', fontSize: 16, margin: '0 0 36px', lineHeight: 1.6 }}>
              Your team is already doing this work. Let's make it easier.
            </p>
            <div style={{ display: 'flex', gap: 12, justifyContent: 'center', flexWrap: 'wrap' }}>
              <button onClick={() => navigate('/login')} style={{ background: 'rgba(255,255,255,0.15)', backdropFilter: 'blur(8px)', border: '1px solid rgba(255,255,255,0.25)', borderRadius: 10, color: '#fff', fontSize: 15, fontFamily: 'inherit', fontWeight: 600, padding: '13px 28px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8, transition: 'background 0.15s' }}
                onMouseEnter={e => ((e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,0.22)')}
                onMouseLeave={e => ((e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,0.15)')}
              >
                <LogIn size={16} /> Sign In
              </button>
              <button onClick={() => scrollTo('#early-access')} style={{ background: '#fff', border: 'none', borderRadius: 10, color: 'var(--brand)', fontSize: 15, fontFamily: 'inherit', fontWeight: 700, padding: '13px 28px', cursor: 'pointer', transition: 'opacity 0.15s' }}
                onMouseEnter={e => ((e.currentTarget as HTMLButtonElement).style.opacity = '0.9')}
                onMouseLeave={e => ((e.currentTarget as HTMLButtonElement).style.opacity = '1')}
              >
                Request Early Access
              </button>
            </div>
          </div>
        </div>
      </FadeIn>
    </section>
  );
}

// ── Footer ────────────────────────────────────────────────────────────────────
const FOOTER_LINKS = {
  Product:   ['Features', 'AI Agents', 'How it Works', 'Pricing'],
  Company:   ['About', 'Careers', 'Contact'],
  Resources: ['Documentation', 'Help & Guidance'],
  Legal:     ['Privacy Policy', 'Terms of Service'],
};

function LandingFooter() {
  function scrollTo(id: string) { document.querySelector(id)?.scrollIntoView({ behavior: 'smooth' }); }
  const sectionMap: Record<string, string> = {
    'Features': '#features', 'AI Agents': '#ai-agents',
    'How it Works': '#how-it-works', 'Pricing': '#pricing',
  };
  return (
    <footer style={{ background: 'var(--panel)', borderTop: '1px solid var(--line)', padding: '72px 24px 40px', marginTop: 0 }}>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '260px repeat(4, 1fr)', gap: 48, marginBottom: 56 }}>
          {/* Brand column */}
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
              <BrandMark size="sm" />
              <span style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>
                NForce <span style={{ color: 'var(--brand-bright)' }}>OneHR</span>
              </span>
            </div>
            <p style={{ color: 'var(--txt-dim)', fontSize: 13, lineHeight: 1.65, margin: '0 0 12px' }}>
              The all-in-one HR platform, built from the inside out.
            </p>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--txt-dim)', fontSize: 12, marginBottom: 18 }}>
              <MapPin size={11} /> Hyderabad · Dallas
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {['Li', 'Tw'].map(s => (
                <div key={s} style={{ width: 30, height: 30, borderRadius: 7, background: 'var(--raised)', border: '1px solid var(--line2)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, color: 'var(--txt-mut)', fontWeight: 700, cursor: 'pointer' }}>{s}</div>
              ))}
            </div>
          </div>
          {/* Link columns */}
          {Object.entries(FOOTER_LINKS).map(([col, links]) => (
            <div key={col}>
              <h4 style={{ fontFamily: "'Space Grotesk', sans-serif", fontWeight: 600, fontSize: 12, color: 'var(--txt)', margin: '0 0 16px', textTransform: 'uppercase', letterSpacing: '0.06em' }}>{col}</h4>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {links.map(l => (
                  <button
                    key={l}
                    onClick={() => sectionMap[l] ? scrollTo(sectionMap[l]) : undefined}
                    style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', fontSize: 13.5, fontFamily: 'inherit', padding: 0, textAlign: 'left', transition: 'color 0.15s' }}
                    onMouseEnter={e => (e.currentTarget.style.color = 'var(--txt)')}
                    onMouseLeave={e => (e.currentTarget.style.color = 'var(--txt-mut)')}
                  >{l}</button>
                ))}
              </div>
            </div>
          ))}
        </div>
        <div style={{ borderTop: '1px solid var(--line)', paddingTop: 24, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <p style={{ color: 'var(--txt-dim)', fontSize: 12, margin: 0 }}>© 2026 NForce One. All rights reserved.</p>
          <p style={{ color: 'var(--txt-dim)', fontSize: 12, margin: 0 }}>Built with care in India 🇮🇳</p>
        </div>
      </div>
    </footer>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────
export default function LandingPage() {
  return (
    <div data-theme="dark" style={{ background: 'var(--shell)', color: 'var(--txt)', minHeight: '100vh', overflowX: 'hidden' }}>
      <LandingNav />
      <div style={{ paddingTop: 60 }}>
        <LandingHero />
        <LandingStats />
        <LandingProductShowcase />
        <div className="lp-divider" style={{ margin: '0' }} />
        <LandingFeatures />
        <div className="lp-divider" style={{ margin: '0' }} />
        <LandingAI />
        <div className="lp-divider" style={{ margin: '0' }} />
        <LandingBrandMoment />
        <div className="lp-divider" style={{ margin: '0' }} />
        <LandingRoles />
        <div className="lp-divider" style={{ margin: '0' }} />
        <LandingHowItWorks />
        <div className="lp-divider" style={{ margin: '0' }} />
        <LandingPricing />
        <LandingEarlyAccess />
        <LandingCTABanner />
        <LandingFooter />
      </div>
    </div>
  );
}
