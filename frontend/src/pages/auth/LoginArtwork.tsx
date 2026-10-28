// Left ~65% of the login page: dark brand panel with the red/chrome wave artwork.
// login-wave.jpg is purely decorative — it contains no text. Every piece of visible text
// (headline, description, feature labels, footer) is real DOM.
//
// A full-viewport dark layer sits behind everything (including the login card) so the panel and the
// card area read as one continuous screen; the wave artwork is anchored to the bottom of the panel
// and its top and right edges fade into that dark background.
//
// Mobile / tablet (<= 1024px): the same artwork and text stack in one column above the login card.
// Phones (<= 768px) hide the artwork panel entirely (see Login.tsx).
import wave from '../../assets/login-wave.jpg';
import logo from '../../assets/nforce-logo.png';

// The logo's own palette: pure white, and a red that runs from deep maroon to bright red (its "1").
const LOGO_RED_TEXT: React.CSSProperties = {
  background: 'linear-gradient(90deg, #A80000 0%, #E00000 50%, #FF0000 100%)',
  WebkitBackgroundClip: 'text', backgroundClip: 'text', WebkitTextFillColor: 'transparent', color: 'transparent',
};

export function LoginArtwork() {
  return (
    <>
      {/* Full-viewport dark background, continuous behind the login card */}
      <div
        aria-hidden="true"
        className="nf-la-bg"
        style={{
          position: 'absolute', top: 0, bottom: 0, left: 0, width: '65%', zIndex: 0, overflow: 'hidden', pointerEvents: 'none',
          background: '#000',
        }}
      />

      {/* Phones (<= 768px): compact dark brand header above the light sign-in panel */}
      <div className="nf-la-mobile">
        <img src={wave} alt="" aria-hidden="true" draggable={false} className="nf-la-mimg" />
        <div aria-hidden="true" className="nf-la-moverlay" />
        <div className="nf-la-mbrand">
          <img src={logo} alt="NForce One" />
          <span aria-hidden="true" className="nf-la-mdiv" />
          <span>NForce <span style={LOGO_RED_TEXT}>OneHR</span></span>
        </div>
        <h2 className="nf-la-mh2">
          The modern way to manage <span style={LOGO_RED_TEXT}>your workforce</span>
        </h2>
      </div>

      <div
        className="nf-login-art"
        style={{
          position: 'absolute', top: 0, bottom: 0, left: 0, zIndex: 2, width: '65%', overflow: 'hidden', background: '#000',
          fontFamily: 'Inter, "Segoe UI", sans-serif',
        }}
      >
        {/* Decorative wave, anchored to the bottom; top + right edges fade into the dark panel */}
        <img
          src={wave}
          alt=""
          aria-hidden="true"
          className="nf-la-wave"
          draggable={false}
          style={{
            position: 'absolute', left: 0, right: 0, bottom: 0, width: '100%', height: '60%',
            objectFit: 'fill', display: 'block',
            WebkitMaskImage: 'linear-gradient(180deg, transparent 0, #000 38%)',
            maskImage: 'linear-gradient(180deg, transparent 0, #000 38%)',
          }}
        />

        <div className="nf-la-text" style={{ position: 'relative', zIndex: 1, padding: 'clamp(28px, 5vh, 56px) clamp(32px, 4.2vw, 72px) 0' }}>
          <div className="nf-la-brand" style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 'clamp(8px, 1.5vh, 18px)' }}>
            <img src={logo} alt="NForce One" style={{ height: 'clamp(46px, 5.4vh, 60px)', width: 'clamp(46px, 5.4vh, 60px)', borderRadius: '50%', objectFit: 'cover', display: 'block' }} />
            <span aria-hidden="true" style={{ width: 1, height: 36, background: 'rgba(255,255,255,0.28)' }} />
            <span style={{ color: '#FFFFFF', fontSize: 'clamp(21px, 1.9vw, 31px)', fontWeight: 700, letterSpacing: '-0.01em' }}>NForce <span style={LOGO_RED_TEXT}>OneHR</span></span>
          </div>
          <h2
            className="nf-la-h2"
            style={{
              margin: 0, color: '#FFFFFF', fontSize: 'clamp(24px, 2.5vw, 44px)', lineHeight: 1.12,
              fontFamily: 'Inter, "Segoe UI", sans-serif', fontWeight: 700, letterSpacing: '-0.03em',
            }}
          >
            The modern way to
            <br />
            manage <span style={LOGO_RED_TEXT}>your workforce</span>
          </h2>

          <p
            className="nf-la-desc"
            style={{
              margin: 'clamp(14px, 2vh, 24px) 0 0', maxWidth: 'min(52ch, 100%)',
              color: '#C1CAD4', fontSize: 'clamp(14px, 1.2vw, 20px)', lineHeight: 1.5, fontWeight: 400,
            }}
          >
            Streamline HR processes, empower your people and build a stronger tomorrow, together.
          </p>

        </div>

        <p
          className="nf-la-foot"
          style={{
            position: 'absolute', zIndex: 1, left: 'clamp(32px, 4.2vw, 72px)', bottom: 'clamp(18px, 3.5vh, 36px)', margin: 0,
            color: '#E2E6EC', fontSize: 'clamp(11px, 0.8vw, 13px)', lineHeight: 1.2, fontWeight: 400, whiteSpace: 'nowrap',
          }}
        >
          <span className="nf-la-copy" style={{ color: '#E2E6EC', letterSpacing: '0.02em' }}>
            © 2026 NForce One · Enterprise Workforce Platform
          </span>
        </p>

        <style>{`
          .nf-la-mobile { display: none; }
          @media (max-width: 768px) {
            .nf-la-mobile {
              display: block; position: relative; z-index: 2; box-sizing: border-box;
              padding: 26px 24px 64px; background-color: #000; overflow: hidden;
              border-radius: 0 0 28px 28px;
            }
            .nf-la-mimg { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: left bottom; border-radius: 0 0 28px 28px; }
            .nf-la-moverlay { position: absolute; inset: 0; border-radius: 0 0 28px 28px; background: linear-gradient(180deg, rgba(0,0,0,0.92) 0%, rgba(0,0,0,0.55) 60%, rgba(0,0,0,0.2) 100%); }
            .nf-la-mbrand, .nf-la-mh2 { position: relative; z-index: 1; }
            .nf-la-mbrand { display: flex; align-items: center; gap: 12px; color: #ffffff; font-size: 19px; font-weight: 700; }
            .nf-la-mbrand img { width: 44px; height: 44px; border-radius: 50%; object-fit: cover; display: block; }
            .nf-la-mdiv { width: 1px; height: 26px; background: rgba(255,255,255,0.28); }
            .nf-la-mh2 { margin: 22px 0 0; color: #ffffff; font-family: Inter, "Segoe UI", sans-serif; font-weight: 700; font-size: clamp(22px, 6.4vw, 28px); line-height: 1.15; letter-spacing: -0.03em; }
          }
          /* <= 1024px: same artwork + text, one column above the card. */
          @media (max-width: 1024px) {
            .nf-la-bg { display: none !important; }
            .nf-login-art {
              position: relative !important; top: auto !important; bottom: auto !important; left: auto !important;
              width: 100% !important; overflow: hidden !important; min-height: 420px;
              background: #000;
            }
            .nf-la-text { padding: 32px 24px 0 !important; }
            .nf-la-h2 { font-size: clamp(22px, 5.8vw, 32px) !important; }
            .nf-la-brand { margin-bottom: 28px !important; }
            .nf-la-desc { font-size: 14px !important; }
            .nf-la-wave { height: 55% !important; }
            .nf-la-foot { position: absolute !important; left: 24px !important; white-space: normal !important; }
            .nf-la-copy { display: block; margin: 4px 0 0 !important; }
          }
        `}</style>
      </div>
    </>
  );
}
