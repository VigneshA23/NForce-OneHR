import type { CSSProperties } from 'react';
import noraRobot from '../../assets/nora-robot.png';

/** Where the disc sits inside the (square) robot artwork, as a share of its width — measured from
 *  the source image so the robot's body lines up with the disc edge. */
const DISC_SHARE = 0.865;
/** The artwork is drawn oversized (and offset) so the disc fills the whole box with no empty margin;
 *  only the antenna pokes out past the edge. */
const IMG_SIZE = `${(100 / DISC_SHARE).toFixed(2)}%`;
const IMG_OFFSET = `${(-10.6 / (DISC_SHARE * 100) * 100).toFixed(2)}%`;

/**
 * The NORA robot on a disc that takes the active theme/accent colour. Only the disc follows the
 * theme (`--brand*` are set by the accent picker and the light/dark blocks); the robot artwork is
 * a transparent PNG and is never recoloured.
 */
export function NoraAvatar({ style }: { style?: CSSProperties }) {
  return (
    <span style={{ position: 'relative', display: 'block', width: '100%', height: '100%', ...style }}>
      <span
        aria-hidden="true"
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: '50%',
          background: 'radial-gradient(circle at 30% 25%, var(--brand-bright), var(--brand) 62%, var(--brand-deep, var(--brand)))',
        }}
      />
      <img
        src={noraRobot}
        alt=""
        draggable={false}
        style={{ position: 'absolute', left: IMG_OFFSET, top: IMG_OFFSET, width: IMG_SIZE, height: IMG_SIZE, maxWidth: 'none' }}
      />
    </span>
  );
}
