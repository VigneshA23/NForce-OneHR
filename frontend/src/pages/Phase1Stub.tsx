import { useLocation } from 'react-router-dom';

export default function Phase1Stub() {
  const { pathname } = useLocation();
  const name = pathname.replace('/', '').replace(/-/g, ' ');

  return (
    <div
      style={{
        background: 'var(--panel)',
        border: '1px solid var(--line)',
        borderRadius: 10,
        padding: '32px 24px',
        textAlign: 'center',
        color: 'var(--txt-mut)',
        fontSize: 13,
      }}
    >
      <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 15, color: 'var(--txt)', marginBottom: 6, textTransform: 'capitalize' }}>
        {name}
      </div>
      Phase 1 module — content builds in its dedicated slice.
    </div>
  );
}
