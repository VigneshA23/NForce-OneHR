import { useEffect, useLayoutEffect, useRef } from 'react';

/**
 * Decorative live analog clock for the Today card. The hands show the viewer's own system time and
 * are re-read from the system clock continuously — every animation frame (smooth second hand), or once
 * a second under prefers-reduced-motion — rather than being started once and left to run on CSS
 * timers. That means it cannot drift: a throttled or hidden tab, a laptop that slept, a manual clock
 * change or a time-zone/DST change are all corrected on the very next paint. The ripple rings and the
 * orbiting dot are CSS opacity/transform animations (off under prefers-reduced-motion).
 * Display only — shows no attendance data.
 */
export default function LiveClock() {
  const hourRef = useRef<SVGGElement>(null);
  const minuteRef = useRef<SVGGElement>(null);
  const secondRef = useRef<SVGGElement>(null);

  function paint() {
    const d = new Date(); // the employee's own system clock
    const sec = d.getSeconds() + d.getMilliseconds() / 1000;
    const min = d.getMinutes() + sec / 60;
    const hr = (d.getHours() % 12) + min / 60;
    if (secondRef.current) secondRef.current.style.transform = `rotate(${sec * 6}deg)`;
    if (minuteRef.current) minuteRef.current.style.transform = `rotate(${min * 6}deg)`;
    if (hourRef.current) hourRef.current.style.transform = `rotate(${hr * 30}deg)`;
  }

  // Correct from the first paint, before the browser draws anything.
  useLayoutEffect(() => { paint(); });

  useEffect(() => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let raf = 0;
    let timer = 0;
    const loop = () => { paint(); raf = requestAnimationFrame(loop); };
    if (reduce) timer = window.setInterval(paint, 1000);
    else raf = requestAnimationFrame(loop);
    // Re-sync immediately when the tab/window comes back (rAF and timers are throttled while hidden).
    const resync = () => { if (document.visibilityState === 'visible') paint(); };
    document.addEventListener('visibilitychange', resync);
    window.addEventListener('focus', resync);
    return () => {
      cancelAnimationFrame(raf);
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', resync);
      window.removeEventListener('focus', resync);
    };
  }, []);

  const ticks = Array.from({ length: 12 }, (_, i) => i);
  return (
    <div className="nf-live-clock" aria-hidden="true">
      <svg viewBox="0 0 200 120" width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
        <defs>
          <radialGradient id="nfClockFace" cx="50%" cy="40%" r="75%">
            <stop offset="0%" stopColor="var(--panel)" />
            <stop offset="100%" stopColor="color-mix(in srgb, var(--brand) 12%, var(--panel))" />
          </radialGradient>
        </defs>

        {/* ripples */}
        <g style={{ transformOrigin: '100px 60px' }}>
          <circle className="nf-clock-ripple nf-clock-ripple-1" cx="100" cy="60" r="44" fill="none" stroke="var(--brand)" strokeWidth="1.5" />
          <circle className="nf-clock-ripple nf-clock-ripple-2" cx="100" cy="60" r="44" fill="none" stroke="var(--brand)" strokeWidth="1.5" />
          <circle className="nf-clock-ripple nf-clock-ripple-3" cx="100" cy="60" r="44" fill="none" stroke="var(--brand)" strokeWidth="1.5" />
        </g>

        {/* orbiting dot */}
        <g className="nf-clock-orbit" style={{ transformOrigin: '100px 60px' }}>
          <circle cx="100" cy="9" r="3.2" fill="var(--brand)" />
          <circle cx="100" cy="9" r="6.5" fill="var(--brand)" opacity=".18" />
        </g>

        {/* face */}
        <circle cx="100" cy="60" r="46" fill="url(#nfClockFace)" stroke="color-mix(in srgb, var(--brand) 45%, var(--line))" strokeWidth="2" />
        {ticks.map((i) => (
          <line key={i} x1="100" y1={i % 3 === 0 ? 18 : 20} x2="100" y2={i % 3 === 0 ? 25 : 23.5} stroke={i % 3 === 0 ? 'var(--txt-mut)' : 'var(--txt-dim)'} strokeWidth={i % 3 === 0 ? 2 : 1.2} strokeLinecap="round" transform={`rotate(${i * 30} 100 60)`} />
        ))}

        {/* hands — angles are written straight from the system clock by paint() above */}
        <g ref={hourRef} className="nf-clock-hand" style={{ transformOrigin: '100px 60px' }}>
          <line x1="100" y1="64" x2="100" y2="38" stroke="var(--txt)" strokeWidth="3.4" strokeLinecap="round" />
        </g>
        <g ref={minuteRef} className="nf-clock-hand" style={{ transformOrigin: '100px 60px' }}>
          <line x1="100" y1="66" x2="100" y2="29" stroke="var(--txt)" strokeWidth="2.4" strokeLinecap="round" />
        </g>
        <g ref={secondRef} className="nf-clock-hand" style={{ transformOrigin: '100px 60px' }}>
          <line x1="100" y1="70" x2="100" y2="24" stroke="var(--brand)" strokeWidth="1.3" strokeLinecap="round" />
        </g>
        <circle cx="100" cy="60" r="3.6" fill="var(--brand)" />
        <circle cx="100" cy="60" r="1.4" fill="var(--panel)" />
      </svg>
    </div>
  );
}
