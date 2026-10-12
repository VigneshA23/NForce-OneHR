import { useRef, useState } from 'react';
import { useToast } from '../../context/ToastContext';
import { talentDirectoryApi, type TalentEmployeeDetail } from '../../api/talentOrg';

/**
 * Fetches an employee's read-only Skills/Certificates/Learning detail, guarding against the
 * close-while-in-flight race: closing (or opening a different employee) bumps requestId, so a
 * response that lands after that is dropped instead of flipping the modal back open/stale.
 */
export function useEmployeeDetail(token: string) {
  const { showToast } = useToast();
  const [detail, setDetail] = useState<TalentEmployeeDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const requestId = useRef(0);

  async function openDetail(employeeUserId: string) {
    const id = ++requestId.current;
    setDetail(null);
    setDetailLoading(true);
    try {
      const d = await talentDirectoryApi.getDetail(token, employeeUserId);
      if (requestId.current === id) setDetail(d);
    } catch (e) {
      if (requestId.current === id) showToast('error', e instanceof Error ? e.message : 'Could not load this employee');
    } finally {
      if (requestId.current === id) setDetailLoading(false);
    }
  }

  function closeDetail() {
    requestId.current++;
    setDetail(null);
    setDetailLoading(false);
  }

  return { detail, detailLoading, openDetail, closeDetail };
}
