import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Last-resort catch for uncaught render errors. Without this, a single throw anywhere in the
 * tree (e.g. a stale/unexpected API response shape) unmounts the entire app to a blank screen
 * with no indication of what happened — must be a class component, React only supports error
 * boundaries via componentDidCatch/getDerivedStateFromError, no hook equivalent exists.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Uncaught render error', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          gap: 12, minHeight: '100vh', padding: 24, textAlign: 'center', background: 'var(--shell)',
        }}>
          <AlertTriangle size={32} color="var(--warn)" />
          <div style={{ fontSize: 15, color: 'var(--txt)' }}>Something went wrong loading this page.</div>
          <button
            onClick={() => window.location.reload()}
            style={{
              background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6,
              padding: '8px 16px', fontSize: 13, cursor: 'pointer',
            }}
          >
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
