import { useEmployeeCodes } from '../lib/useEmployeeCodes';

/**
 * The employee's ID, shown on its own line under a name wherever a list or table shows a person.
 * Pass `code` when the record already carries it; otherwise it is looked up from the user id
 * (see useEmployeeCodes, which only ever knows the employees the signed-in role may already see).
 * Renders nothing when no ID is known.
 */
export function EmpCode({ userId, code }: { userId?: string | null; code?: string | null }) {
  const codeFor = useEmployeeCodes();
  const value = code ?? codeFor(userId);
  if (!value) return null;
  return <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2, fontWeight: 400, letterSpacing: '.01em' }}>{value}</div>;
}
