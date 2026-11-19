/**
 * Small confetti burst fired from the sign-in button when login succeeds: 18 dots fly out in a
 * ring and fade (same effect as the NForce retail ops app's sign-in). Render it as a sibling
 * of the button inside a `position: relative` wrapper (.nf-lg-btnwrap). Decorative only.
 */
const COLORS = ['#DC1C2E', '#E8394A', '#F2C13C', '#ffffff'];
const DOTS = Array.from({ length: 18 }, (_, i) => {
  const angle = (i / 18) * Math.PI * 2;
  const radius = 55 + (i % 3) * 18;
  return {
    dx: Math.round(Math.cos(angle) * radius),
    dy: Math.round(Math.sin(angle) * radius),
    color: COLORS[i % 4],
    delay: i * 0.028,
    size: 5 + (i % 3),
  };
});

export function SignInConfetti() {
  return (
    <>
      {DOTS.map((d, i) => (
        <span
          key={i}
          className="nf-lg-confetti"
          aria-hidden="true"
          style={{ ['--dx' as string]: `${d.dx}px`, ['--dy' as string]: `${d.dy}px`, width: d.size, height: d.size, background: d.color, animationDelay: `${d.delay}s` }}
        />
      ))}
    </>
  );
}
