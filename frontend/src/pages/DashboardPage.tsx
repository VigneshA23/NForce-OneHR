export default function DashboardPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div
        style={{
          background: 'var(--panel)',
          border: '1px solid var(--line)',
          borderRadius: 10,
          padding: '20px 24px',
          color: 'var(--txt-mut)',
          fontSize: 13,
        }}
      >
        Dashboard widgets load in the next slice — routing and shell are wired.
      </div>
    </div>
  );
}
