import { useAuthStore } from '../store/authStore';
import { useNavigate } from 'react-router-dom';

export default function DashboardPlaceholder() {
  const { user, clearAuth } = useAuthStore();
  const navigate = useNavigate();

  function handleSignOut() {
    clearAuth();
    navigate('/login');
  }

  return (
    <div className="min-h-dvh bg-[#080808] flex items-center justify-center">
      <div className="text-center space-y-4">
        <p
          className="text-xs font-mono tracking-widest uppercase"
          style={{ color: '#b11116' }}
        >
          Phase 1 — Login slice complete
        </p>
        <h1
          className="text-3xl font-bold"
          style={{ fontFamily: 'Space Grotesk, system-ui, sans-serif', color: '#f0f0f0' }}
        >
          Welcome to NForce OneHR
        </h1>
        {user && (
          <p style={{ color: '#a0a0a0' }}>
            Signed in as{' '}
            <span style={{ fontFamily: 'JetBrains Mono, monospace', color: '#f0f0f0' }}>
              {user.email}
            </span>
          </p>
        )}
        <p className="text-sm" style={{ color: '#5a5a5a' }}>
          Dashboard modules load in the next slice.
        </p>
        <button
          onClick={handleSignOut}
          className="mt-6 px-4 py-2 rounded text-sm cursor-pointer transition-colors"
          style={{ background: '#1a1a1a', color: '#a0a0a0', border: '1px solid #2a2a2a' }}
        >
          Sign out
        </button>
      </div>
    </div>
  );
}
