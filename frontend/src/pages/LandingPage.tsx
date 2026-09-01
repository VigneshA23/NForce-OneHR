import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, useScroll, useTransform } from 'framer-motion';
import {
  Users, User, Clock, FileText, Package, Shield, GitBranch, AlertTriangle,
  MessageCircle, FileCheck, CheckSquare, Building2, LogIn,
  ChevronRight, ChevronDown, Bot, MapPin, Check,
} from 'lucide-react';
import * as NavigationMenu from '@radix-ui/react-navigation-menu';
import * as Popover from '@radix-ui/react-popover';
import { BrandMark } from '../components/BrandMark';
import './LandingPage.css';

// ── Section-level reveal (whole section rises as one unit — Keka pattern) ────
function SectionReveal({ children, delay = 0 }: { children: React.ReactNode; delay?: number }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 56 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '0px 0px -80px 0px' }}
      transition={{ duration: 0.72, ease: [0.22, 1, 0.36, 1], delay }}
    >
      {children}
    </motion.div>
  );
}

// ── Scroll reveal ────────────────────────────────────────────────────────────
type FadeVariant = 'up' | 'left' | 'right' | 'scale';

function FadeIn({
  children, delay = 0, style, variant = 'up',
}: {
  children: React.ReactNode; delay?: number; style?: React.CSSProperties; variant?: FadeVariant;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ob = new IntersectionObserver(
      ([e]) => { if (e.isIntersecting) { el.classList.add('lp-visible'); ob.unobserve(el); } },
      { threshold: 0.08, rootMargin: '0px 0px -48px 0px' }
    );
    ob.observe(el);
    return () => ob.disconnect();
  }, []);
  const cls = variant === 'left' ? 'lp-fade-left' : variant === 'right' ? 'lp-fade-right' : variant === 'scale' ? 'lp-fade-scale' : 'lp-fade';
  return (
    <div ref={ref} className={cls} style={{ transitionDelay: delay ? `${delay}ms` : undefined, ...style }}>
      {children}
    </div>
  );
}

// ── Animated hamburger icon ───────────────────────────────────────────────────
function HamburgerIcon({ open }: { open: boolean }) {
  return (
    <div className="lp-hamburger" data-open={open ? 'true' : 'false'}>
      <span /><span /><span />
    </div>
  );
}

// ── Features data (used in nav mega-menu + features section) ─────────────────
const NAV_FEATURES = [
  { icon: Users,       title: 'People & Org',       desc: 'Find anyone, see the full org tree.' },
  { icon: Clock,       title: 'Attendance & Leave',  desc: 'Clock in anywhere, balances auto-update.' },
  { icon: CheckSquare, title: 'Approvals',            desc: 'All requests in one queue.' },
  { icon: FileText,    title: 'Documents',            desc: 'Track expirations and compliance.' },
  { icon: Package,     title: 'Assets & Expenses',   desc: "Who has what, what's pending." },
  { icon: Shield,      title: 'Audit & Security',    desc: 'Every action logged and attributed.' },
];

// ── Nav ──────────────────────────────────────────────────────────────────────
const OTHER_NAV = [
  { label: 'Role Guides',  href: '#role-guides' },
  { label: 'Platform',     href: '#platform' },
  { label: 'How it Works', href: '#how-it-works' },
  { label: 'Pricing',      href: '#pricing' },
];

function LandingNav() {
  const navigate = useNavigate();
  const [scrolled, setScrolled] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    const fn = () => setScrolled(window.scrollY > 24);
    window.addEventListener('scroll', fn, { passive: true });
    return () => window.removeEventListener('scroll', fn);
  }, []);

  function scrollTo(id: string) {
    setMobileOpen(false);
    document.querySelector(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  return (
    <nav className={`lp-nav${scrolled ? ' lp-nav-scrolled' : ''}`}>
      <div style={{ maxWidth: 1200, margin: '0 auto', padding: '0 28px', height: 76, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>

        {/* Logo */}
        <div className="lp-nav-logo" style={{ display: 'flex', alignItems: 'center', gap: 12, cursor: 'pointer', flexShrink: 0 }} onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}>
          <BrandMark size="md" />
          <span className="lp-nav-logo-text" style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 19, color: 'var(--txt)', letterSpacing: '-0.025em' }}>
            NForce <span style={{ color: 'var(--brand-bright)' }}>OneHR</span>
          </span>
        </div>

        {/* Desktop navigation using Radix NavigationMenu */}
        <NavigationMenu.Root className="lp-nav-root lp-nav-desktop" style={{ position: 'relative', flex: 1, display: 'flex', justifyContent: 'center' }}>
          <NavigationMenu.List className="lp-nav-list" style={{ display: 'flex', alignItems: 'center', gap: 4, listStyle: 'none', margin: 0, padding: 0 }}>

            {/* Features — with mega-menu */}
            <NavigationMenu.Item>
              <NavigationMenu.Trigger className="lp-nav-trigger">
                Features <ChevronDown size={12} className="lp-nav-chevron" />
              </NavigationMenu.Trigger>
              <NavigationMenu.Content className="lp-nav-content">
                <div style={{ padding: '20px 20px 16px', width: 520 }}>
                  <p style={{ fontSize: 11, fontWeight: 700, color: 'var(--brand-bright)', letterSpacing: '0.1em', textTransform: 'uppercase', margin: '0 0 14px 2px' }}>Product Features</p>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2 }}>
                    {NAV_FEATURES.map(f => {
                      const Icon = f.icon;
                      return (
                        <button
                          key={f.title}
                          onClick={() => scrollTo('#features')}
                          className="lp-nav-feature-item"
                          style={{ background: 'none', border: 'none', cursor: 'pointer', padding: '10px 12px', borderRadius: 8, textAlign: 'left', display: 'flex', alignItems: 'flex-start', gap: 12 }}
                        >
                          <div style={{ width: 32, height: 32, borderRadius: 8, background: 'rgba(177,17,22,0.1)', border: '1px solid rgba(177,17,22,0.16)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, marginTop: 1 }}>
                            <Icon size={14} color="var(--brand-bright)" />
                          </div>
                          <div>
                            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', fontFamily: "Inter, sans-serif", marginBottom: 2, lineHeight: 1.3 }}>{f.title}</div>
                            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', lineHeight: 1.4 }}>{f.desc}</div>
                          </div>
                        </button>
                      );
                    })}
                  </div>
                </div>
              </NavigationMenu.Content>
            </NavigationMenu.Item>

            {/* Remaining nav links */}
            {OTHER_NAV.map(l => (
              <NavigationMenu.Item key={l.label}>
                <NavigationMenu.Link asChild>
                  <button
                    onClick={() => scrollTo(l.href)}
                    className="lp-nav-link-btn"
                    style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', fontSize: 13.5, fontFamily: 'inherit', fontWeight: 500, padding: '6px 10px', borderRadius: 6, transition: 'color 0.15s, background 0.15s' }}
                    onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.color = 'var(--txt)'; (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,0.04)'; }}
                    onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.color = 'var(--txt-mut)'; (e.currentTarget as HTMLButtonElement).style.background = 'none'; }}
                  >{l.label}</button>
                </NavigationMenu.Link>
              </NavigationMenu.Item>
            ))}

          </NavigationMenu.List>

          {/* Viewport — where dropdown content renders */}
          <div className="lp-nav-viewport-wrap">
            <NavigationMenu.Viewport className="lp-nav-viewport" />
          </div>
        </NavigationMenu.Root>

        {/* Desktop CTA buttons */}
        <div className="lp-nav-desktop" style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
          <a
            href="mailto:demo@nforceone.com?subject=NForce%20OneHR%20Demo%20Request"
            className="lp-btn-shimmer"
            style={{ background: 'var(--brand-bright)', border: 'none', borderRadius: 8, color: '#fff', fontSize: 13.5, fontFamily: 'inherit', fontWeight: 700, padding: '7px 18px', cursor: 'pointer', textDecoration: 'none', display: 'flex', alignItems: 'center', gap: 5, transition: 'opacity 0.15s', boxShadow: '0 0 24px rgba(228,55,61,0.35), inset 0 1px 0 rgba(255,255,255,0.15)' }}
            onMouseEnter={e => ((e.currentTarget as HTMLAnchorElement).style.opacity = '0.88')}
            onMouseLeave={e => ((e.currentTarget as HTMLAnchorElement).style.opacity = '1')}
          >
            Book Demo
          </a>
          <button
            onClick={() => navigate('/login')}
            style={{ background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.11)', borderRadius: 8, color: 'var(--txt)', fontSize: 13.5, fontFamily: 'inherit', fontWeight: 500, padding: '7px 18px', cursor: 'pointer', transition: 'background 0.15s, border-color 0.15s, box-shadow 0.15s', display: 'flex', alignItems: 'center', gap: 6, backdropFilter: 'blur(8px)', WebkitBackdropFilter: 'blur(8px)', boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.07)' }}
            onMouseEnter={e => { const b = e.currentTarget as HTMLButtonElement; b.style.background = 'rgba(255,255,255,0.1)'; b.style.borderColor = 'rgba(255,255,255,0.18)'; b.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.1), 0 0 0 1px rgba(255,255,255,0.06)'; }}
            onMouseLeave={e => { const b = e.currentTarget as HTMLButtonElement; b.style.background = 'rgba(255,255,255,0.06)'; b.style.borderColor = 'rgba(255,255,255,0.11)'; b.style.boxShadow = 'inset 0 1px 0 rgba(255,255,255,0.07)'; }}
          >
            <LogIn size={13} /> Sign In
          </button>
        </div>

        {/* Mobile hamburger + Radix Popover */}
        <Popover.Root open={mobileOpen} onOpenChange={setMobileOpen}>
          <Popover.Trigger asChild>
            <button
              className="lp-mobile-menu-btn"
              style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 6 }}
              aria-label="Menu"
            >
              <HamburgerIcon open={mobileOpen} />
            </button>
          </Popover.Trigger>
          <Popover.Portal>
            <Popover.Content
              className="lp-mobile-nav"
              align="end"
              sideOffset={10}
              style={{ zIndex: 200 }}
            >
              {/* Features section */}
              <div style={{ padding: '4px 0 12px', borderBottom: '1px solid var(--line)', marginBottom: 8 }}>
                <p style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand-bright)', letterSpacing: '0.1em', textTransform: 'uppercase', margin: '0 0 8px 4px' }}>Features</p>
                {NAV_FEATURES.map(f => {
                  const Icon = f.icon;
                  return (
                    <button key={f.title} onClick={() => scrollTo('#features')} style={{ background: 'none', border: 'none', cursor: 'pointer', width: '100%', textAlign: 'left', padding: '7px 4px', color: 'var(--txt-mut)', fontSize: 13, fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 10, borderRadius: 6 }}>
                      <Icon size={14} color="var(--txt-dim)" /> {f.title}
                    </button>
                  );
                })}
              </div>
              {/* Other nav links */}
              {OTHER_NAV.map(l => (
                <button key={l.label} onClick={() => scrollTo(l.href)} style={{ background: 'none', border: 'none', cursor: 'pointer', width: '100%', textAlign: 'left', padding: '9px 4px', color: 'var(--txt-mut)', fontSize: 14, fontFamily: 'inherit', fontWeight: 500, borderRadius: 6 }}>{l.label}</button>
              ))}
              {/* Book Demo / Sign In live above the hero badge on mobile now (see LandingHero) — not duplicated here. */}
            </Popover.Content>
          </Popover.Portal>
        </Popover.Root>

      </div>
    </nav>
  );
}

// ── Hero ─────────────────────────────────────────────────────────────────────
function LandingHero() {
  const navigate = useNavigate();
  function scrollTo(id: string) { document.querySelector(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
  return (
    <section className="lp-hero-section" style={{ position: 'relative', padding: '120px 24px 100px', overflow: 'hidden' }}>
      {/* Ambient mesh */}
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(ellipse 80% 55% at 50% 0%, rgba(177,17,22,0.13) 0%, transparent 68%)' }} />
        <div className="lp-blob-1 lp-aurora" style={{ position: 'absolute', width: 720, height: 720, borderRadius: '50%', background: 'radial-gradient(circle, rgba(177,17,22,0.13) 0%, transparent 70%)', left: '55%', top: '-20%' }} />
        <div className="lp-blob-2" style={{ position: 'absolute', width: 500, height: 500, borderRadius: '50%', background: 'radial-gradient(circle, rgba(228,55,61,0.07) 0%, transparent 70%)', right: '66%', top: '24%' }} />
        <div className="lp-blob-3" style={{ position: 'absolute', width: 600, height: 600, borderRadius: '50%', background: 'radial-gradient(circle, rgba(122,12,16,0.1) 0%, transparent 70%)', left: '40%', bottom: '-10%' }} />
        <div style={{ position: 'absolute', inset: 0, backgroundImage: 'linear-gradient(rgba(255,255,255,0.013) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.013) 1px, transparent 1px)', backgroundSize: '52px 52px' }} />
      </div>

      <div className="lp-hero-grid" style={{ maxWidth: 1200, margin: '0 auto', position: 'relative' }}>
        {/* Left — text */}
        <div>
          <FadeIn>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: 32 }}>
              <span className="lp-hero-badge" style={{ background: 'rgba(228,55,61,0.1)', border: '1px solid rgba(228,55,61,0.22)', borderRadius: 999, padding: '3px 14px 3px 6px', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <span style={{ background: 'var(--brand-bright)', borderRadius: 999, padding: '2px 9px', fontSize: 10, fontWeight: 700, color: '#fff', letterSpacing: '0.06em', flexShrink: 0 }}>NEW</span>
                <span style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Full-stack HR platform — built for teams of every size.</span>
              </span>
            </div>
          </FadeIn>

          {/* Mobile only (nav's own Book Demo / Sign In cover desktop) — sits between
              the badge and the headline, stacked 2x1 (one full-width button per row). */}
          <FadeIn delay={40}>
            <div className="lp-hero-mobile-cta">
              <a
                href="mailto:demo@nforceone.com?subject=NForce%20OneHR%20Demo%20Request"
                className="lp-btn-shimmer"
                style={{ background: 'var(--brand-bright)', border: 'none', borderRadius: 8, color: '#fff', fontSize: 14, fontFamily: 'inherit', fontWeight: 700, padding: '10px 16px', cursor: 'pointer', textDecoration: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, boxShadow: '0 0 16px rgba(228,55,61,0.3)' }}
              >
                Book Demo
              </a>
              <button
                onClick={() => navigate('/login')}
                style={{ background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', borderRadius: 8, color: 'var(--txt)', fontSize: 14, fontFamily: 'inherit', fontWeight: 500, padding: '10px 16px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}
              >
                <LogIn size={14} /> Sign In
              </button>
            </div>
          </FadeIn>

          <FadeIn delay={70}>
            <h1 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(2.6rem, 5.5vw, 4.8rem)', lineHeight: 1.06, letterSpacing: '-0.043em', margin: '0 0 24px', background: 'linear-gradient(172deg, #ffffff 0%, rgba(255,255,255,0.68) 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', backgroundClip: 'text' }}>
              Put AI agents<br className="lp-hero-break" />to work for your{' '}
              <span style={{ WebkitTextFillColor: 'var(--brand-bright)', color: 'var(--brand-bright)' }}>HR team.</span>
            </h1>
          </FadeIn>

          <FadeIn delay={150}>
            <p style={{ color: 'var(--txt-mut)', fontSize: 'clamp(0.95rem, 1.8vw, 1.12rem)', lineHeight: 1.68, margin: '0 0 40px', maxWidth: 460 }}>
              Attendance, leave, approvals, documents, and your full org chart — all in one place.
              AI agents are joining the team soon.
            </p>
          </FadeIn>

          <FadeIn delay={230}>
            <div className="lp-hero-cta-row" style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
              <button
                onClick={() => scrollTo('#ai-agents')}
                className="lp-btn-primary lp-btn-shimmer lp-hero-cta-btn"
                style={{ background: 'var(--brand)', border: 'none', borderRadius: 10, color: '#fff', fontSize: 15, fontFamily: 'inherit', fontWeight: 600, padding: '14px 30px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, boxShadow: '0 0 28px rgba(228,55,61,0.4), inset 0 1px 0 rgba(255,255,255,0.12)' }}
              >
                <Bot size={16} /> Explore Agents
              </button>
              <button
                onClick={() => scrollTo('#platform')}
                className="lp-hero-cta-btn"
                style={{ background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 10, color: 'var(--txt)', fontSize: 15, fontFamily: 'inherit', fontWeight: 500, padding: '14px 30px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, transition: 'background 0.15s, border-color 0.15s', backdropFilter: 'blur(8px)', WebkitBackdropFilter: 'blur(8px)', boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.06)' }}
                onMouseEnter={e => { const b = e.currentTarget as HTMLButtonElement; b.style.background = 'rgba(255,255,255,0.09)'; b.style.borderColor = 'rgba(255,255,255,0.18)'; }}
                onMouseLeave={e => { const b = e.currentTarget as HTMLButtonElement; b.style.background = 'rgba(255,255,255,0.05)'; b.style.borderColor = 'rgba(255,255,255,0.1)'; }}
              >
                See the platform <ChevronRight size={16} />
              </button>
            </div>
          </FadeIn>
        </div>

        {/* Right — image cluster */}
        <div className="lp-hero-img-cluster">
          <FadeIn delay={100} variant="right">
            <div style={{ position: 'relative', height: 460 }}>
              {/* Primary image */}
              <div style={{ position: 'absolute', top: 0, right: 0, width: '90%', borderRadius: 18, overflow: 'hidden', boxShadow: '0 32px 80px rgba(0,0,0,0.65)', transform: 'rotate(1.8deg)', border: '1px solid rgba(255,255,255,0.07)' }}>
                <img src="/assets/photos/hr-team.jpg" alt="HR team collaboration" style={{ width: '100%', height: 300, objectFit: 'cover', display: 'block' }} />
              </div>
              {/* Secondary image */}
              <div style={{ position: 'absolute', bottom: 0, left: 0, width: '58%', borderRadius: 14, overflow: 'hidden', boxShadow: '0 20px 56px rgba(0,0,0,0.55)', transform: 'rotate(-2.2deg)', border: '2px solid rgba(255,255,255,0.06)' }}>
                <img src="/assets/photos/hr-professionals.jpg" alt="HR professionals" style={{ width: '100%', height: 190, objectFit: 'cover', display: 'block' }} />
              </div>
              {/* Floating live stat chip */}
              <div className="lp-glass-chip" style={{ position: 'absolute', top: '42%', left: '-2%', zIndex: 10 }}>
                <div style={{ fontSize: 10, color: 'var(--brand-bright)', fontWeight: 700, letterSpacing: '0.06em', marginBottom: 4, display: 'flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand-bright)', boxShadow: '0 0 6px rgba(228,55,61,0.7)', display: 'inline-block', animation: 'lp-cta-pulse 2.5s ease-in-out infinite' }} />
                  ENTERPRISE READY
                </div>
                <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--txt)', fontFamily: "Inter, sans-serif", lineHeight: 1.1 }}>4 Roles. One Platform.</div>
                <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2 }}>Every team member, covered</div>
              </div>
            </div>
          </FadeIn>
        </div>
      </div>
    </section>
  );
}

// ── Product showcase — tabbed switcher ────────────────────────────────────────
const SHOWCASE_TABS = [
  {
    label: 'Super Admin',
    view: 'Dashboard',
    src: '/assets/screenshots/fresh/real-dashboard-live.png',
    desc: "Super Admin home: live attendance banner, module tiles (User Management, Org Masters, Audit & Security, Approval Center), real-time org stats.",
  },
  {
    label: 'Org Hierarchy',
    view: 'Organization',
    src: '/assets/screenshots/fresh/fresh-superadmin-hierarchy.png',
    desc: '21 direct reports under the Chief Executive. Every reporting line, every role — navigable in seconds.',
  },
  {
    label: 'HR Admin',
    view: 'HR Dashboard',
    src: '/assets/screenshots/fresh/fresh-hradmin-dashboard.png',
    desc: '95 employees, org-wide active count, and a live employee list — all on the first screen after login.',
  },
];

function LandingProductShowcase() {
  const [active, setActive] = useState(0);
  const sectionRef = useRef<HTMLDivElement>(null);

  const { scrollYProgress } = useScroll({
    target: sectionRef,
    offset: ['start 0.9', 'start 0.2'],
  });

  const rotateX = useTransform(scrollYProgress, [0, 1], [20, 0]);
  const scale = useTransform(scrollYProgress, [0, 1], [0.84, 1]);
  const translateY = useTransform(scrollYProgress, [0, 1], [52, 0]);
  const opacity = useTransform(scrollYProgress, [0, 0.6], [0.55, 1]);

  return (
    <section ref={sectionRef} id="platform" style={{ padding: '96px 24px', background: 'var(--shell)' }}>
      <SectionReveal>
      <div style={{ maxWidth: 1060, margin: '0 auto' }}>
        <div style={{ textAlign: 'center', marginBottom: 48 }}>
          <FadeIn><p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>SEE IT IN ACTION</p></FadeIn>
          <FadeIn delay={80} variant="scale"><h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 2.9rem)', letterSpacing: '-0.035em', margin: '0 0 14px', color: 'var(--txt)', lineHeight: 1.1 }}>
            This is what your team actually sees.
          </h2></FadeIn>
          <FadeIn delay={160}><p style={{ color: 'var(--txt-mut)', fontSize: 15.5, maxWidth: 480, margin: '0 auto', lineHeight: 1.6 }}>
            Three roles. One platform. Every screen built around what that person actually needs.
          </p></FadeIn>
        </div>

        {/* Tab bar */}
        <FadeIn delay={80}>
          <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginBottom: 28, flexWrap: 'wrap' }}>
            {SHOWCASE_TABS.map((t, i) => (
              <button
                key={i}
                onClick={() => setActive(i)}
                className={`lp-tab${active === i ? ' lp-tab-active' : ''}`}
              >
                <span style={{ fontSize: 9.5, display: 'block', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', marginBottom: 2, color: active === i ? 'var(--brand-bright)' : 'var(--txt-dim)', transition: 'color 0.2s' }}>{t.label}</span>
                {t.view}
              </button>
            ))}
          </div>
        </FadeIn>

        {/* Browser frame — Container Scroll Animation (Aceternity-style 3D reveal) */}
        <div style={{ perspective: '1200px', perspectiveOrigin: '50% -20%' }}>
          <motion.div className="lp-showcase-scroll-anim" style={{ rotateX, scale, translateY, opacity, transformOrigin: 'top center' }}>
            <div style={{ position: 'relative' }}>
              {/* Glow behind */}
              <div style={{ position: 'absolute', left: '50%', top: '-8%', transform: 'translateX(-50%)', width: '75%', height: '40%', background: 'radial-gradient(ellipse at 50% 65%, rgba(228,55,61,0.2) 0%, rgba(177,17,22,0.08) 42%, transparent 68%)', filter: 'blur(50px)', zIndex: 0 }} />
              <div style={{ position: 'relative', zIndex: 1, borderRadius: 14, border: '1px solid rgba(255,255,255,0.08)', boxShadow: '0 40px 100px rgba(0,0,0,0.7)', overflow: 'hidden' }}>
                {/* Browser chrome */}
                <div style={{ background: 'rgba(14,16,20,0.99)', borderBottom: '1px solid rgba(255,255,255,0.06)', padding: '10px 16px', display: 'flex', alignItems: 'center', gap: 12 }}>
                  <div style={{ display: 'flex', gap: 6 }}>
                    {['#FF5F57','#FFBD2E','#28C840'].map(c => <div key={c} style={{ width: 10, height: 10, borderRadius: '50%', background: c }} />)}
                  </div>
                  <div style={{ flex: 1, background: 'rgba(255,255,255,0.04)', borderRadius: 5, padding: '3.5px 12px', fontSize: 10.5, color: 'var(--txt-dim)', textAlign: 'center', fontFamily: "Inter, sans-serif" }}>app.nforceone.com</div>
                </div>
                {/* Crossfade screenshots — aspect-ratio (not a fixed height) keeps this a
                    landscape frame at every viewport width instead of drifting toward a
                    tall/portrait box as the container narrows on smaller screens. */}
                <div className="lp-showcase-frame-height" style={{ position: 'relative', aspectRatio: '16 / 9', background: 'var(--shell)' }}>
                  {SHOWCASE_TABS.map((t, i) => (
                    <img
                      key={i}
                      src={t.src}
                      alt={`${t.label} — ${t.view}`}
                      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top center', opacity: active === i ? 1 : 0, transition: 'opacity 0.45s ease' }}
                    />
                  ))}
                </div>
                {/* Caption */}
                <div className="lp-showcase-caption" style={{ background: 'var(--panel)', borderTop: '1px solid var(--line)', padding: '14px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
                  <div>
                    <span style={{ fontFamily: "Inter, sans-serif", fontWeight: 600, fontSize: 13.5, color: 'var(--txt)', display: 'block', marginBottom: 3 }}>{SHOWCASE_TABS[active].label} — {SHOWCASE_TABS[active].view}</span>
                    <span style={{ fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.5 }}>{SHOWCASE_TABS[active].desc}</span>
                  </div>
                  <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                    {SHOWCASE_TABS.map((_, i) => (
                      <button key={i} onClick={() => setActive(i)} style={{ width: active === i ? 20 : 6, height: 6, borderRadius: 999, background: active === i ? 'var(--brand-bright)' : 'rgba(255,255,255,0.15)', border: 'none', cursor: 'pointer', transition: 'all 0.3s ease', padding: 0 }} />
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </motion.div>
        </div>
      </div>
      </SectionReveal>
    </section>
  );
}

// ── Features ──────────────────────────────────────────────────────────────────
const FEATURES = [
  { icon: Users,       title: 'People Directory & Org Hierarchy', desc: "Search any employee, see their manager, team, and reporting chain. The org chart updates automatically when anyone moves roles — no manual diagram to maintain." },
  { icon: Clock,       title: 'Attendance & Leave',               desc: 'Employees clock in from the browser. Hours log automatically. Leave balances update the moment a request is approved. Nobody calls HR to ask how many days they have left.' },
  { icon: CheckSquare, title: 'Approvals & Workflows',            desc: "Leave, expenses, and asset requests all land in one queue. The person responsible sees what needs a decision, with the relevant context already there." },
  { icon: FileText,    title: 'Documents & Compliance',           desc: 'Track which employees have signed which policies, and when each document expires. The system flags what\'s overdue before you have to chase it.' },
  { icon: Package,     title: 'Assets & Expenses',                desc: "Log who has which laptop or device, track expense claims from submission to reimbursement, and keep the full history without touching a shared sheet." },
  { icon: Shield,      title: 'Audit & Security',                 desc: "Every role change, password reset, and approval action is logged with the exact time and person responsible. If something goes wrong, you know exactly where to look." },
];


function LandingFeatures() {
  return (
    <section id="features" style={{ padding: '96px 24px', background: 'var(--panel)' }}>
      <SectionReveal>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 64, alignItems: 'center', marginBottom: 64 }} className="lp-features-header">
          <div>
            <FadeIn><p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>WHAT'S INSIDE</p></FadeIn>
            <FadeIn delay={80} variant="scale"><h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(2rem, 4vw, 3.1rem)', letterSpacing: '-0.038em', margin: '0 0 18px', color: 'var(--txt)', lineHeight: 1.08 }}>
              Everything HR runs on — all in one place.
            </h2></FadeIn>
            <FadeIn delay={160}><p style={{ color: 'var(--txt-mut)', fontSize: 16, lineHeight: 1.65, margin: 0 }}>
              Pick any HR process your team currently manages across multiple tools or email threads. It's probably already here.
            </p></FadeIn>
          </div>
          <FadeIn delay={120} variant="right">
            <div style={{ borderRadius: 16, overflow: 'hidden', boxShadow: '0 24px 64px rgba(0,0,0,0.5)', border: '1px solid rgba(255,255,255,0.07)' }}>
              <img src="/assets/photos/hr-meeting.jpg" alt="HR team" style={{ width: '100%', height: 260, objectFit: 'cover', display: 'block' }} />
            </div>
          </FadeIn>
        </div>
        <div className="lp-features-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(310px, 1fr))', gap: 1, background: 'var(--line)' }}>
          {FEATURES.map((f, i) => {
            const Icon = f.icon;
            return (
              <FadeIn key={f.title} delay={Math.floor(i / 2) * 70 + (i % 2) * 35}>
                <div className="lp-feature-card" style={{ background: 'var(--panel)', padding: '36px 30px', height: '100%', boxSizing: 'border-box' }}>
                  <div className="lp-icon-glow" style={{ width: 46, height: 46, borderRadius: 11, background: 'rgba(177,17,22,0.11)', border: '1px solid rgba(177,17,22,0.2)', display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 20 }}>
                    <Icon size={20} color="var(--brand-bright)" />
                  </div>
                  <h3 style={{ fontFamily: "Inter, sans-serif", fontWeight: 600, fontSize: 15.5, color: 'var(--txt)', margin: '0 0 10px', lineHeight: 1.3 }}>{f.title}</h3>
                  <p style={{ color: 'var(--txt-mut)', fontSize: 13.5, lineHeight: 1.68, margin: 0 }}>{f.desc}</p>
                </div>
              </FadeIn>
            );
          })}
        </div>
      </div>
      </SectionReveal>
    </section>
  );
}

// ── AI Agents — editorial numbered layout ─────────────────────────────────────
const AGENT_LIST = [
  {
    verb: 'ANSWERS',
    name: 'HR Assistant Agent',
    icon: MessageCircle,
    img: '/assets/photos/hr-agent-person.jpg',
    desc: "Your HR inbox has 20 questions that come back every month: 'How many leaves do I have?' 'What's the WFH policy for Fridays?' 'Do I need a receipt for this amount?' This agent handles those — immediately, correctly — so your team spends time on things that actually need HR judgment.",
  },
  {
    verb: 'MONITORS',
    name: 'Compliance Guardian',
    icon: FileCheck,
    img: '/assets/photos/hr-compliance.jpg',
    desc: "Document expiry dates don't announce themselves. This agent tracks every form, acknowledgment, and certification across your whole team, and flags what's about to lapse in time for someone to actually do something about it — not after an audit already found it.",
  },
  {
    verb: 'TRACKS',
    name: 'Attendance Monitor',
    icon: AlertTriangle,
    img: '/assets/photos/hr-ai-team.jpg',
    desc: "A manager shouldn't need to run a report to notice someone has been clocking in 45 minutes late every Monday for six weeks. This agent tracks those patterns — inconsistent clock-ins, early exits, hours that don't line up with approved WFH — and flags them early.",
  },
  {
    verb: 'MAPS',
    name: 'Org Development Agent',
    icon: Building2,
    img: '/assets/photos/hr-professionals.jpg',
    desc: "When one manager has 14 direct reports and another has 3, it usually surfaces as a performance issue before anyone realizes the structure underneath is the problem. This agent maps span-of-control across the org and flags where the reporting structure is working against people.",
  },
];

function LandingAI() {
  return (
    <section id="ai-agents" style={{ position: 'relative', padding: '96px 0', overflow: 'hidden', background: 'var(--shell)' }}>
      <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(ellipse 75% 65% at 50% 45%, rgba(177,17,22,0.08) 0%, transparent 72%)', pointerEvents: 'none' }} />
      <div style={{ position: 'absolute', inset: 0, backgroundImage: 'linear-gradient(rgba(228,55,61,0.025) 1px, transparent 1px), linear-gradient(90deg, rgba(228,55,61,0.025) 1px, transparent 1px)', backgroundSize: '72px 72px', pointerEvents: 'none' }} />

      <SectionReveal>
      <div style={{ maxWidth: 1100, margin: '0 auto', padding: '0 24px', position: 'relative' }}>
        {/* Header */}
        <FadeIn>
          <div style={{ marginBottom: 56 }}>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: 20, background: 'rgba(228,55,61,0.07)', border: '1px solid rgba(228,55,61,0.18)', borderRadius: 999, padding: '5px 14px 5px 10px' }}>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand-bright)', boxShadow: '0 0 8px rgba(228,55,61,0.7)', display: 'inline-block', animation: 'lp-cta-pulse 2.5s ease-in-out infinite' }} />
              <span style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.09em', textTransform: 'uppercase' }}>Roadmap — Actively Being Built</span>
            </div>
            <h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(2.3rem, 5.5vw, 3.8rem)', letterSpacing: '-0.043em', margin: '0 0 18px', color: 'var(--txt)', lineHeight: 1.03 }}>
              Meet the agents<br />joining your HR team.
            </h2>
            <p style={{ color: 'var(--txt-mut)', fontSize: 17, lineHeight: 1.68, maxWidth: 540, margin: 0 }}>
              Not chatbots. Agents that watch for specific things, take defined actions, and only escalate when something actually needs a human.
            </p>
          </div>
        </FadeIn>

        {/* Featured — The Approvals Agent */}
        <FadeIn>
          <div className="lp-featured-agent" style={{ marginBottom: 16, overflow: 'hidden' }}>
            <div className="lp-featured-agent-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 0, alignItems: 'stretch' }}>
              {/* Text side */}
              <div style={{ padding: '0 32px 0 0' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 22 }}>
                  <div style={{ width: 48, height: 48, borderRadius: 12, background: 'rgba(228,55,61,0.14)', border: '1px solid rgba(228,55,61,0.26)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                    <GitBranch size={22} color="var(--brand-bright)" />
                  </div>
                  <div>
                    <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand-bright)', letterSpacing: '0.1em', textTransform: 'uppercase', marginBottom: 2 }}>ROUTES</div>
                    <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', fontWeight: 500 }}>Featured Agent</div>
                  </div>
                  <span style={{ marginLeft: 'auto', background: 'rgba(224,169,59,0.1)', color: 'var(--warn)', border: '1px solid rgba(224,169,59,0.2)', borderRadius: 999, padding: '5px 14px', fontSize: 11, fontWeight: 700, letterSpacing: '0.04em', whiteSpace: 'nowrap', flexShrink: 0 }}>COMING SOON</span>
                </div>
                <h3 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(1.45rem, 2.8vw, 2rem)', color: 'var(--txt)', margin: '0 0 16px', lineHeight: 1.18, letterSpacing: '-0.028em' }}>The Approvals Agent</h3>
                <p style={{ color: 'var(--txt-mut)', fontSize: 15, lineHeight: 1.72, margin: 0 }}>
                  Right now, every request — leave, expense, WFH — lands in someone's inbox and waits until they decide whether it needs attention. This agent reads the request, checks the policy, the employee's history, and team leave coverage, then clears low-risk ones automatically. The ones that do reach you already have the full context — so what used to take 30 minutes takes 30 seconds.
                </p>
              </div>
              {/* Image side */}
              <div style={{ margin: '-28px -28px -28px 0', borderLeft: '1px solid rgba(255,255,255,0.06)', overflow: 'hidden' }}>
                <img src="/assets/photos/hr-analytics.jpg" alt="HR analytics" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block', minHeight: 240 }} />
              </div>
            </div>
          </div>
        </FadeIn>

        {/* Numbered editorial list */}
        <div>
          {AGENT_LIST.map((agent, i) => {
            const Icon = agent.icon;
            return (
              <FadeIn key={agent.name} delay={i * 70}>
                <div className="lp-agent-row">
                  {/* Large faint number */}
                  <div className="lp-agent-number">
                    {String(i + 1).padStart(2, '0')}
                  </div>
                  {/* Content */}
                  <div style={{ flex: 1, display: 'grid', gridTemplateColumns: '1fr auto', gap: 24, alignItems: 'start' }}>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                        <div style={{ width: 32, height: 32, borderRadius: 8, background: 'rgba(228,55,61,0.1)', border: '1px solid rgba(228,55,61,0.16)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                          <Icon size={14} color="var(--brand-bright)" />
                        </div>
                        <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--brand-bright)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>{agent.verb}</span>
                      </div>
                      <h3 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 18, color: 'var(--txt)', margin: '0 0 10px', lineHeight: 1.2 }}>{agent.name}</h3>
                      <p style={{ color: 'var(--txt-mut)', fontSize: 14, lineHeight: 1.68, margin: 0 }}>{agent.desc}</p>
                    </div>
                    {/* Agent photo thumbnail */}
                    <div style={{ width: 120, height: 88, borderRadius: 12, overflow: 'hidden', border: '1px solid rgba(255,255,255,0.07)', flexShrink: 0 }} className="lp-agent-thumb">
                      <img src={agent.img} alt={agent.name} style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
                    </div>
                  </div>
                </div>
              </FadeIn>
            );
          })}
        </div>

        {/* Why agents */}
        <FadeIn delay={300} style={{ marginTop: 48 }}>
          <div style={{ padding: '20px 26px', background: 'rgba(18,20,25,0.75)', border: '1px solid rgba(255,255,255,0.08)', borderRadius: 14, display: 'flex', alignItems: 'flex-start', gap: 14, backdropFilter: 'blur(16px)', WebkitBackdropFilter: 'blur(16px)', boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.06), 0 8px 32px rgba(0,0,0,0.3)' }}>
            <Bot size={18} color="var(--brand-bright)" style={{ flexShrink: 0, marginTop: 2 }} />
            <p style={{ color: 'var(--txt-dim)', fontSize: 13.5, lineHeight: 1.65, margin: 0 }}>
              <strong style={{ color: 'var(--txt-mut)', fontWeight: 600 }}>Why agents, not features?&nbsp;</strong>
              Traditional HR software helps your team do the work faster. These agents handle specific tasks themselves — so your HR people spend time on the decisions only people should make.
            </p>
          </div>
        </FadeIn>
      </div>
      </SectionReveal>
    </section>
  );
}

// ── Brand moment ──────────────────────────────────────────────────────────────
function LandingBrandMoment() {
  return (
    <section className="lp-brand-section" style={{ minHeight: 400, display: 'flex', alignItems: 'center' }}>
      <img
        src="/assets/brand/nforce-office.webp"
        alt="NForce One office"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'center', zIndex: 0 }}
      />
      <div className="lp-brand-overlay" />
      <div style={{ maxWidth: 1100, margin: '0 auto', padding: '80px 24px', position: 'relative', zIndex: 2, width: '100%' }}>
        <FadeIn variant="left">
          <div style={{ maxWidth: 560 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 18, color: 'var(--brand-bright)', fontSize: 12, fontWeight: 600 }}>
              <MapPin size={13} /> Hyderabad · Dallas
            </div>
            <h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(2rem, 4vw, 3.1rem)', letterSpacing: '-0.036em', margin: '0 0 28px', color: 'var(--txt)', lineHeight: 1.1 }}>
              Built by NForce One.<br />
              <span style={{ color: 'var(--brand-bright)' }}>Used by NForce One.</span>
            </h2>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <img
                src="/assets/brand/nforce-logo.png"
                alt="NForce One"
                style={{ width: 40, height: 40, borderRadius: 8, objectFit: 'cover', border: '1px solid rgba(255,255,255,0.1)', flexShrink: 0 }}
              />
              <div>
                <div style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 14, color: 'var(--txt)' }}>NForce One</div>
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
  { slug: 'employee',    label: 'Employee',    icon: User,      color: '#2FB67C', bg: 'rgba(47,182,124,0.1)',  desc: 'Clock in, check your leave balance, submit requests, and track your own documents — without asking HR every time.' },
  { slug: 'manager',     label: 'Manager',     icon: Users,     color: '#4C8DD6', bg: 'rgba(76,141,214,0.1)',  desc: "Your team's attendance, pending leave requests, and outstanding approvals — visible from your dashboard without digging through reports." },
  { slug: 'hr-admin',    label: 'HR Admin',    icon: Building2, color: '#E0A93B', bg: 'rgba(224,169,59,0.1)', desc: 'Manage employee records, run compliance checks, process documents, and enforce policies — with the full context you need to make good calls.' },
  { slug: 'super-admin', label: 'Super Admin', icon: Shield,    color: '#E4373D', bg: 'rgba(228,55,61,0.1)',  desc: 'Full access to every user, every role assignment, every audit trail, and every system setting — with nothing hidden.' },
];

function LandingRoles() {
  const navigate = useNavigate();
  return (
    <section id="role-guides" style={{ padding: '96px 24px', background: 'var(--panel)' }}>
      <SectionReveal>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <div style={{ textAlign: 'center', marginBottom: 56 }}>
          <FadeIn><p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>ROLE GUIDES</p></FadeIn>
          <FadeIn delay={80} variant="scale"><h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 3rem)', letterSpacing: '-0.036em', margin: '0 0 14px', color: 'var(--txt)', lineHeight: 1.1 }}>
            Pick your role and see exactly what you can do.
          </h2></FadeIn>
          <FadeIn delay={160}><p style={{ color: 'var(--txt-mut)', fontSize: 15, margin: 0, maxWidth: 500, marginLeft: 'auto', marginRight: 'auto' }}>
            Every role has its own tailored view. What an employee sees is completely different from what HR Admin sees — intentionally.
          </p></FadeIn>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))', gap: 16 }}>
          {ROLES.map((r, i) => {
            const Icon = r.icon;
            return (
              <FadeIn key={r.slug} delay={i * 60}>
                <div className="lp-role-card" onClick={() => navigate(`/role-guide/${r.slug}`)} style={{ padding: '28px 24px 24px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14 }}>
                  <div style={{ width: 48, height: 48, borderRadius: 12, background: r.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 16 }}>
                    <Icon size={22} color={r.color} />
                  </div>
                  <h3 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 16, color: 'var(--txt)', margin: '0 0 10px' }}>{r.label}</h3>
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
      </SectionReveal>
    </section>
  );
}

// ── How it works ──────────────────────────────────────────────────────────────
const STEPS = [
  { n: 1, icon: Building2,   title: 'Admin sets up the organization',       desc: 'Departments, designations, locations, and reporting lines — configured once. Every person who logs in inherits that structure automatically.' },
  { n: 2, icon: LogIn,       title: 'Everyone signs in to their own view',  desc: "Each person sees their role's screen on first login — their attendance, their pending tasks, their team. Nothing to configure per user." },
  { n: 3, icon: CheckSquare, title: 'Requests and approvals just flow',     desc: 'A leave request submitted by an employee shows up in their manager\'s queue instantly. Both sides can see the status without anyone sending a follow-up.' },
];

function LandingHowItWorks() {
  return (
    <section id="how-it-works" style={{ padding: '96px 24px', background: 'var(--shell)' }}>
      <SectionReveal>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <div style={{ textAlign: 'center', marginBottom: 72 }}>
          <FadeIn><p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>HOW IT WORKS</p></FadeIn>
          <FadeIn delay={80} variant="scale"><h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 3rem)', letterSpacing: '-0.036em', margin: 0, color: 'var(--txt)', lineHeight: 1.1 }}>
            Running in a day. No consultants needed.
          </h2></FadeIn>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 32, position: 'relative' }}>
          {STEPS.map((s, i) => {
            const Icon = s.icon;
            return (
              <FadeIn key={s.n} delay={i * 90}>
                <div style={{ textAlign: 'center', position: 'relative' }}>
                  {i < STEPS.length - 1 && <div className="lp-step-connector lp-hide-mobile" />}
                  <div style={{ position: 'relative', display: 'inline-block', marginBottom: 20 }}>
                    <div className="lp-step-circle" style={{ width: 60, height: 60, borderRadius: '50%', background: 'rgba(22,24,29,0.8)', border: '1px solid rgba(255,255,255,0.08)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto' }}>
                      <Icon size={24} color="var(--brand-bright)" />
                    </div>
                    <div style={{ position: 'absolute', top: -6, right: -6, width: 20, height: 20, borderRadius: '50%', background: 'var(--brand)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 700, color: '#fff', fontFamily: "Inter, sans-serif" }}>{s.n}</div>
                  </div>
                  <h3 style={{ fontFamily: "Inter, sans-serif", fontWeight: 600, fontSize: 16, color: 'var(--txt)', margin: '0 0 10px', lineHeight: 1.3 }}>{s.title}</h3>
                  <p style={{ color: 'var(--txt-mut)', fontSize: 14, lineHeight: 1.65, margin: 0 }}>{s.desc}</p>
                </div>
              </FadeIn>
            );
          })}
        </div>

        {/* Photo strip — real office imagery */}
        <FadeIn delay={200} style={{ marginTop: 64 }}>
          <div className="lp-photo-strip" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, borderRadius: 18, overflow: 'hidden', boxShadow: '0 24px 72px rgba(0,0,0,0.45)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <img src="/assets/photos/hr-discussion.jpg" alt="Team in discussion" style={{ width: '100%', height: 240, objectFit: 'cover', display: 'block' }} />
            <img src="/assets/photos/hr-office.jpg" alt="Modern office" style={{ width: '100%', height: 240, objectFit: 'cover', display: 'block' }} />
          </div>
        </FadeIn>
      </div>
      </SectionReveal>
    </section>
  );
}

// ── Pricing ───────────────────────────────────────────────────────────────────
const TIERS = [
  { name: 'Starter',    target: 'Small teams that need the basics working right',    highlight: false, features: ['Core HR modules', 'People directory & org chart', 'Attendance & leave', 'Up to 25 employees'] },
  { name: 'Growth',     target: 'Growing companies with multiple departments',        highlight: true,  features: ['Everything in Starter', 'Multi-department workflows', 'Approval Center', 'Documents & compliance', 'Unlimited employees'] },
  { name: 'Enterprise', target: 'Large organizations with complex structures',        highlight: false, features: ['Everything in Growth', 'Custom integrations', 'Advanced audit & security', 'Dedicated support', 'SLA guarantee'] },
];

function LandingPricing() {
  return (
    <section id="pricing" style={{ padding: '96px 24px', background: 'var(--panel)' }}>
      <SectionReveal>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <div style={{ textAlign: 'center', marginBottom: 60 }}>
          <FadeIn><p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>PRICING</p></FadeIn>
          <FadeIn delay={80} variant="scale"><h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(1.9rem, 4vw, 3rem)', letterSpacing: '-0.036em', margin: '0 0 14px', color: 'var(--txt)', lineHeight: 1.1 }}>
            Pricing that doesn't punish growth.
          </h2></FadeIn>
          <FadeIn delay={160}><p style={{ color: 'var(--txt-mut)', fontSize: 15, margin: 0 }}>
            We're figuring out the right numbers. Get in touch and we'll find something that works.
          </p></FadeIn>
        </div>
        <div className="lp-pricing-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 20 }}>
          {TIERS.map((t, i) => (
            <FadeIn key={t.name} delay={i * 70}>
              <div className={`lp-pricing-card${t.highlight ? ' lp-pricing-highlight' : ''}`} style={{ padding: '32px 28px', background: t.highlight ? 'var(--raised)' : 'var(--panel)', border: `1px solid ${t.highlight ? 'rgba(228,55,61,0.35)' : 'var(--line)'}`, borderRadius: 14, position: 'relative' }}>
                {t.highlight && <div style={{ position: 'absolute', top: -1, left: '50%', transform: 'translateX(-50%)', background: 'var(--brand)', color: '#fff', fontSize: 10, fontWeight: 700, padding: '3px 14px', borderRadius: '0 0 8px 8px', letterSpacing: '0.06em' }}>MOST POPULAR</div>}
                <h3 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 20, color: 'var(--txt)', margin: '0 0 6px' }}>{t.name}</h3>
                <p style={{ color: 'var(--txt-mut)', fontSize: 13, margin: '0 0 20px', lineHeight: 1.5 }}>{t.target}</p>
                <div style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 26, color: 'var(--txt)', marginBottom: 24 }}>Contact us</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 28 }}>
                  {t.features.map(f => (
                    <div key={f} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <Check size={14} color="var(--ok)" strokeWidth={2.5} style={{ flexShrink: 0 }} />
                      <span style={{ fontSize: 13.5, color: 'var(--txt-mut)' }}>{f}</span>
                    </div>
                  ))}
                </div>
                <a href={`mailto:hr@nforceone.com?subject=NForce%20OneHR%20${encodeURIComponent(t.name)}%20Pricing`} style={{ display: 'block', textAlign: 'center', boxSizing: 'border-box', width: '100%', padding: '12px', borderRadius: 9, fontSize: 14, fontFamily: 'inherit', fontWeight: 600, textDecoration: 'none', background: t.highlight ? 'var(--brand)' : 'var(--raised)', border: t.highlight ? 'none' : '1px solid var(--line2)', color: t.highlight ? '#fff' : 'var(--txt)', transition: 'opacity 0.15s' }}
                  onMouseEnter={e => ((e.currentTarget as HTMLAnchorElement).style.opacity = '0.85')}
                  onMouseLeave={e => ((e.currentTarget as HTMLAnchorElement).style.opacity = '1')}
                >Get in touch</a>
              </div>
            </FadeIn>
          ))}
        </div>
      </div>
      </SectionReveal>
    </section>
  );
}

// ── Sign-in section ───────────────────────────────────────────────────────────
function LandingSignIn() {
  const navigate = useNavigate();
  return (
    <section className="lp-signin-section" style={{ padding: '96px 24px', background: 'var(--shell)', textAlign: 'center' }}>
      <SectionReveal>
      <div style={{ maxWidth: 500, margin: '0 auto' }}>
          <FadeIn><p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 14px' }}>ALREADY INSIDE</p></FadeIn>
          <FadeIn delay={80} variant="scale"><h2 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(2rem, 4.5vw, 3rem)', letterSpacing: '-0.04em', margin: '0 0 18px', color: 'var(--txt)', lineHeight: 1.08 }}>
            Your team is already here.
          </h2></FadeIn>
          <FadeIn delay={160}><p style={{ color: 'var(--txt-mut)', fontSize: 15.5, lineHeight: 1.7, margin: '0 0 32px' }}>
            Attendance records, approval queues, org charts, documents — live and updated right now. Sign in and see what's happening with your team today.
          </p></FadeIn>
          <FadeIn delay={240}><button
            onClick={() => navigate('/login')}
            className="lp-btn-primary"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 8, background: 'var(--brand)', color: '#fff', borderRadius: 10, fontFamily: 'inherit', fontWeight: 600, fontSize: 15, padding: '14px 32px', cursor: 'pointer', border: 'none', transition: 'background 0.15s' }}
            onMouseEnter={e => ((e.currentTarget as HTMLButtonElement).style.background = 'var(--brand-bright)')}
            onMouseLeave={e => ((e.currentTarget as HTMLButtonElement).style.background = 'var(--brand)')}
          >
            <LogIn size={16} /> Sign In
          </button>
          <p style={{ color: 'var(--txt-dim)', fontSize: 12, marginTop: 18 }}>New to NForce OneHR? Your admin handles account setup.</p></FadeIn>
        </div>
      </SectionReveal>
    </section>
  );
}

// ── Connect With Us ───────────────────────────────────────────────────────────
function LandingConnect() {
  return (
    <section className="lp-connect-section" style={{ padding: '72px 24px', background: 'var(--panel)', borderTop: '1px solid var(--line)', textAlign: 'center' }}>
      <FadeIn>
        <p style={{ color: 'var(--brand-bright)', fontSize: 11, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', margin: '0 0 12px' }}>CONNECT</p>
        <h3 style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 'clamp(1.4rem, 3vw, 2rem)', letterSpacing: '-0.03em', color: 'var(--txt)', margin: '0 0 32px', lineHeight: 1.2 }}>
          Find us online.
        </h3>
        <div style={{ display: 'flex', justifyContent: 'center', gap: 20, alignItems: 'center' }}>
          {/* Instagram */}
          <a href="https://www.instagram.com/nforce_one/" target="_blank" rel="noopener noreferrer" className="lp-social-btn lp-social-ig" aria-label="NForce One on Instagram">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <rect x="2" y="2" width="20" height="20" rx="5" ry="5" />
              <circle cx="12" cy="12" r="4" />
              <circle cx="17.5" cy="6.5" r="0.8" fill="currentColor" stroke="none" />
            </svg>
            <span>Instagram</span>
          </a>
          {/* LinkedIn */}
          <a href="https://www.linkedin.com/company/nforceone/" target="_blank" rel="noopener noreferrer" className="lp-social-btn lp-social-li" aria-label="NForce One on LinkedIn">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
              <path d="M16 8a6 6 0 0 1 6 6v7h-4v-7a2 2 0 0 0-2-2 2 2 0 0 0-2 2v7h-4v-7a6 6 0 0 1 6-6z" />
              <rect x="2" y="9" width="4" height="12" />
              <circle cx="4" cy="4" r="2" />
            </svg>
            <span>LinkedIn</span>
          </a>
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
  const sectionMap: Record<string, string> = { 'Features': '#features', 'AI Agents': '#ai-agents', 'How it Works': '#how-it-works', 'Pricing': '#pricing' };
  return (
    <footer className="lp-footer" style={{ background: 'var(--panel)', borderTop: '1px solid var(--line)', padding: '72px 24px 40px' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto' }}>
        <div className="lp-footer-grid" style={{ display: 'grid', gridTemplateColumns: '260px repeat(4, 1fr)', gap: 48, marginBottom: 56 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
              <BrandMark size="sm" />
              <span style={{ fontFamily: "Inter, sans-serif", fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>NForce <span style={{ color: 'var(--brand-bright)' }}>OneHR</span></span>
            </div>
            <p style={{ color: 'var(--txt-dim)', fontSize: 13, lineHeight: 1.65, margin: '0 0 12px' }}>The HR platform your team actually uses.</p>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--txt-dim)', fontSize: 12, marginBottom: 18 }}>
              <MapPin size={11} /> Hyderabad · Dallas
            </div>
          </div>
          {Object.entries(FOOTER_LINKS).map(([col, links]) => (
            <div key={col}>
              <h4 style={{ fontFamily: "Inter, sans-serif", fontWeight: 600, fontSize: 12, color: 'var(--txt)', margin: '0 0 16px', textTransform: 'uppercase', letterSpacing: '0.06em' }}>{col}</h4>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {links.map(l => (
                  <button key={l} onClick={() => sectionMap[l] ? scrollTo(sectionMap[l]) : undefined} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', fontSize: 13.5, fontFamily: 'inherit', padding: 0, textAlign: 'left', transition: 'color 0.15s' }}
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
    <div data-theme="dark" style={{ background: 'var(--shell)', color: 'var(--txt)', minHeight: '100vh', overflowX: 'clip' }}>
      <LandingNav />
      <div style={{ paddingTop: 76 }}>
        <LandingHero />
        <LandingProductShowcase />
        <div className="lp-divider" />
        <LandingFeatures />
        <div className="lp-divider" />
        <LandingAI />
        <div className="lp-divider" />
        <LandingBrandMoment />
        <div className="lp-divider" />
        <LandingRoles />
        <div className="lp-divider" />
        <LandingHowItWorks />
        <div className="lp-divider" />
        <LandingPricing />
        <LandingSignIn />
        <LandingConnect />
        <LandingFooter />
      </div>
    </div>
  );
}
