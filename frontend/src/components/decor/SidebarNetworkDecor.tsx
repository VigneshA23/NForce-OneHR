import sidebarReferenceArt from '../../assets/onehr-sidebar-reference.png.png';

/**
 * The reference artwork, shown as-is in the empty lower portion of the dark
 * sidebar, above the profile card. Sits in normal flow as its own block
 * (not overlaid), so it can never cover the nav items or the profile
 * section above/below it. No filters, no overlays, no animation — just the
 * image, aspect ratio preserved.
 */
export function SidebarNetworkDecor() {
  return (
    <div className="wf-sd-wrap" aria-hidden="true">
      <img src={sidebarReferenceArt} alt="" className="wf-sd-img" />

      <style>{`
        .wf-sd-wrap {
          position: relative;
          width: 100%;
          aspect-ratio: 264 / 330;
          flex-shrink: 0;
          overflow: hidden;
        }
        .wf-sd-img {
          position: absolute;
          inset: 0;
          width: 100%;
          height: 100%;
          object-fit: cover;
          object-position: center;
          display: block;
        }

        @media (max-width: 1024px) {
          .wf-sd-wrap { aspect-ratio: 264 / 200; }
          .wf-sd-img { object-position: center top; }
        }
        @media (max-width: 767px) {
          .wf-sd-wrap { display: none; }
        }
      `}</style>
    </div>
  );
}
