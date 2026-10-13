// Left ~65% of the login page: the deployed OneHR visual, rebuilt so that every piece of
// visible text is real DOM. login-artwork.png is purely decorative — it contains no text
// (headline, description, feature labels and footer were removed from it) and no form.
//
// Desktop: the frame below is a 1671x941 logical canvas (the size the artwork was designed at).
// It is scaled to the page and text is positioned in canvas coordinates using container-query
// units, so text and artwork always scale together. The artwork itself is never stretched.
//
// Background: a full-viewport layer sits behind everything (including the login card): the artwork's
// own edges extended outward and heavily blurred (login-artwork-bg.png). It is atmosphere only — the
// crisp artwork is never enlarged or stretched — and it makes the design read as one continuous
// screen behind the card, with the crisp artwork fading into its own blurred continuation.
//
// Mobile / tablet (<= 1024px): the same artwork and the same DOM text are kept (never hidden).
// They reflow in a single column above the login card: headline + description, the artwork
// stage (sphere + five feature nodes with their labels), the footer line, then the copyright.
import artwork from '../../assets/login-artwork.png';
import artworkBg from '../../assets/login-artwork-bg.png';

const CANVAS_W = 1671;
const CANVAS_H = 941;
const ART_W = 1040; // width of login-artwork.png in canvas units (crop before the old baked-in card)
// login-artwork-bg.png = the artwork with its edges replicated outward (400 above/below, 700 to the right)
// and heavily blurred: pure atmosphere that continues the artwork's own edge colours behind the card.
const BG_PAD_Y = 400;
const BG_W = ART_W + 700;
const BG_H = CANVAS_H + 2 * BG_PAD_Y;

// Region of the canvas shown as the "stage" on small screens (sphere + all five feature nodes).
const STAGE = { x: 175, y: 215, w: 780, h: 535 };

// px in canvas units -> container-relative CSS
const cx = (px: number) => `${(px / CANVAS_W) * 100}%`;
const cy = (px: number) => `${(px / CANVAS_H) * 100}%`;
const fs = (px: number) => `${(px / CANVAS_W) * 100}cqw`;

const ART_W_VAR = `min(100vw, calc(100dvh * ${CANVAS_W} / ${CANVAS_H}))`;

const FEATURES = [
  { label: ['Employee', 'Management'], x: 622.5, y: 328.5 },
  { label: ['Attendance', 'Tracking'], x: 281.5, y: 485.5 },
  { label: ['Approvals &', 'Workflows'], x: 870, y: 484.5 },
  { label: ['Time & Leave'], x: 377.5, y: 667.5 },
  { label: ['Workforce', 'Insights'], x: 771.5, y: 678.5 },
] as const;

// One consistent, brighter treatment for all five captions: white text, a faint dark
// under-shadow for legibility and a very subtle white/red glow.
const featureStyle: React.CSSProperties = {
  position: 'absolute',
  transform: 'translate(-50%, -50%)',
  margin: 0,
  padding: 0,
  listStyle: 'none',
  textAlign: 'center',
  color: '#FFFFFF',
  fontSize: fs(13.7),
  lineHeight: 1.46,
  fontWeight: 600,
  whiteSpace: 'nowrap',
  textShadow: '0 1px 2px rgba(0,0,0,0.65), 0 0 10px rgba(255,255,255,0.22), 0 0 14px rgba(228,55,61,0.32)',
};

export function LoginArtwork() {
  return (
    <>
      {/* Full-viewport background: continuous behind the login card (decorative, never stretched artwork) */}
      <div
        aria-hidden="true"
        className="nf-la-bg"
        style={{
          position: 'absolute', inset: 0, zIndex: 0, overflow: 'hidden', pointerEvents: 'none',
          ['--nf-art-w' as string]: ART_W_VAR,
        }}
      >
        <div
          className="nf-la-bgimg"
          style={{
            position: 'absolute', left: 0, top: '50%', transform: 'translateY(-50%)',
            width: `calc(var(--nf-art-w) * ${BG_W / CANVAS_W})`,
            height: `calc(var(--nf-art-w) * ${BG_H / CANVAS_W})`,
            backgroundImage: `url(${artworkBg})`, backgroundSize: '100% 100%', backgroundRepeat: 'no-repeat',
          }}
        />
        <div
          style={{
            position: 'absolute', inset: 0,
            background: 'linear-gradient(90deg, rgba(4,5,10,0) 55%, rgba(4,5,10,0.38) 100%)',
          }}
        />
      </div>

      <div
        className="nf-login-art"
        style={{
          position: 'absolute', top: 0, bottom: 0, left: 0, zIndex: 2,
          width: '65%',
          overflow: 'hidden',
          // Same framing as the deployed login: the artwork frame is as wide as the page allows
          // while keeping its 1671:941 ratio, and is centred vertically.
          ['--nf-art-w' as string]: ART_W_VAR,
        }}
      >
        <div
          className="nf-la-frame"
          style={{
            position: 'absolute', left: 0, top: '50%', transform: 'translateY(-50%)',
            width: 'var(--nf-art-w)', aspectRatio: `${CANVAS_W} / ${CANVAS_H}`,
            containerType: 'inline-size',
            fontFamily: 'Inter, "Segoe UI", sans-serif',
          }}
        >
          {/* Headline */}
          <h2
            className="nf-la-h2"
            style={{
              position: 'absolute', zIndex: 1, left: cx(70), top: cy(161), margin: 0,
              color: '#FAFAFA', fontSize: fs(40.2), lineHeight: 1.24,
              fontWeight: 600, letterSpacing: '-0.01em', whiteSpace: 'nowrap',
            }}
          >
            The modern way to
            <br />
            manage <span style={{ color: '#F71D2A' }}>your workforce</span>
          </h2>

          {/* Description: one paragraph, one font size, natural wrapping */}
          <p
            className="nf-la-desc"
            style={{
              position: 'absolute', zIndex: 1, left: cx(70), top: cy(279), width: cx(365), margin: 0,
              color: '#C1CAD4', fontSize: fs(15), lineHeight: 1.5, fontWeight: 400,
            }}
          >
            Streamline HR processes, empower your people and build a stronger tomorrow, together.
          </p>

          {/* Artwork stage: decorative image + the five feature labels (icons/bubbles are in the image) */}
          <div className="nf-la-stage">
            <div className="nf-la-inner">
              <div
                aria-hidden="true"
                style={{
                  position: 'absolute', left: 0, top: 0, height: '100%', width: cx(ART_W),
                  backgroundImage: `url(${artwork})`, backgroundSize: '100% 100%', backgroundRepeat: 'no-repeat',
                  pointerEvents: 'none',
                  // the image is cropped just before the old baked-in login card; feather that cut edge only
                  WebkitMaskImage: 'linear-gradient(90deg, #000 0, #000 88%, transparent 100%)',
                  maskImage: 'linear-gradient(90deg, #000 0, #000 88%, transparent 100%)',
                }}
              />
              <ul style={{ margin: 0, padding: 0 }}>
                {FEATURES.map((f) => (
                  <li
                    key={f.label.join(' ')}
                    className="nf-la-li"
                    style={{ ...featureStyle, left: cx(f.x), top: cy(f.y) }}
                  >
                    {f.label.map((line) => (
                      <span key={line} style={{ display: 'block' }}>{line}</span>
                    ))}
                  </li>
                ))}
              </ul>
            </div>
          </div>

          {/* Footer line + copyright (one row at the bottom of the artwork) */}
          <p
            className="nf-la-foot"
            style={{
              position: 'absolute', zIndex: 1, left: cx(70), top: cy(859.5), transform: 'translateY(-50%)', margin: 0,
              color: '#E2E6EC', fontSize: fs(11.6), lineHeight: 1.2, fontWeight: 400, whiteSpace: 'nowrap',
            }}
          >
            Building a stronger workforce, together.
          </p>
          <p
            className="nf-la-copy"
            style={{
              position: 'absolute', zIndex: 1, left: cx(560), top: cy(859.5), transform: 'translateY(-50%)', margin: 0,
              color: 'rgba(226,230,236,0.62)', fontSize: fs(11.6), lineHeight: 1.2, fontWeight: 400,
              letterSpacing: '0.02em', whiteSpace: 'nowrap',
            }}
          >
            © 2026 NForce One · Enterprise Workforce Platform
          </p>
        </div>

        <style>{`
          /* Desktop: stage and inner canvas simply fill the frame (identical geometry to before). */
          .nf-la-stage { position: absolute; inset: 0; z-index: 0; }
          .nf-la-inner { position: absolute; inset: 0; container-type: inline-size; }

          /* Wide, short windows (wider than 16:9): the artwork would otherwise stop well short of the login
             panel. Scale it up (never beyond what keeps the headline..footer block on screen) and centre
             that text block vertically. 16:10 / 16:9-and-narrower windows keep the deployed-page scale. */
          @media (min-aspect-ratio: 16/9) and (min-width: 1025px) {
            .nf-login-art, .nf-la-bg, .nfs-auth-panel { --nf-art-w: min(104.4vw, 231dvh) !important; }
            .nf-la-frame { transform: translateY(-54.57%) !important; }
            .nf-la-bgimg { transform: translateY(-52.47%) !important; }
          }

          /* <= 1024px: keep the same artwork + DOM text, reflow into one column above the card. */
          @media (max-width: 1024px) {
            .nf-la-bg { display: none !important; }
            .nf-login-art {
              position: relative !important;
              top: auto !important; bottom: auto !important; left: auto !important;
              width: 100% !important;
              overflow: visible !important;
              display: flex; flex-direction: column;
              padding: 32px 24px 0; box-sizing: border-box;
            }
            .nf-la-frame { display: contents !important; }
            .nf-la-h2 {
              position: static !important; order: 1; margin: 0 0 12px !important;
              font-size: clamp(26px, 7.2vw, 40px) !important; line-height: 1.2 !important;
              white-space: normal !important;
            }
            .nf-la-desc {
              position: static !important; order: 2; margin: 0 0 18px !important; width: auto !important;
              font-size: 14px !important; line-height: 1.55 !important; white-space: normal !important;
            }
            .nf-la-stage {
              position: relative !important; inset: auto !important; order: 3;
              width: min(100%, 680px); margin: 0 auto; overflow: hidden;
              aspect-ratio: ${STAGE.w} / ${STAGE.h};
            }
            .nf-la-inner {
              inset: auto !important;
              left: ${-(STAGE.x / STAGE.w) * 100}% !important;
              top: ${-(STAGE.y / STAGE.h) * 100}% !important;
              width: ${(CANVAS_W / STAGE.w) * 100}% !important;
              aspect-ratio: ${CANVAS_W} / ${CANVAS_H};
            }
            .nf-la-li { font-size: max(9.5px, 0.85cqw) !important; }
            .nf-la-foot {
              position: static !important; transform: none !important; order: 4;
              margin: 14px 0 0 !important; font-size: 12px !important; white-space: normal !important;
            }
            .nf-la-copy {
              position: static !important; transform: none !important; order: 5;
              margin: 6px 0 0 !important; font-size: 11px !important; white-space: normal !important;
            }
          }
        `}</style>
      </div>
    </>
  );
}
