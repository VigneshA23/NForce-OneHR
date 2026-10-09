import { useEffect, useState } from 'react';
import {
  assetsApi,
  type AssetAssignmentResponse,
  type AssetCategory,
  type AssetRequestResponse,
  type AssetTileEmployee,
} from '../../api/assets';

// Shared "my assets" data — used by both AssetsExpensesPage's EmployeeView and the My Profile
// Assets tab, so the two never drift out of sync or duplicate the fetch logic.
export function useEmployeeAssetsData(token: string) {
  const [tiles, setTiles] = useState<AssetTileEmployee | null>(null);
  const [assignments, setAssignments] = useState<AssetAssignmentResponse[]>([]);
  const [requests, setRequests] = useState<AssetRequestResponse[]>([]);
  const [categories, setCategories] = useState<AssetCategory[]>([]);

  function reload() {
    assetsApi.employeeTiles(token).then(setTiles).catch(() => {});
    assetsApi.myAssignments(token).then(setAssignments).catch(() => {});
    assetsApi.myRequests(token).then(setRequests).catch(() => {});
  }

  useEffect(() => {
    reload();
    assetsApi.categories(token).then(setCategories).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  return { tiles, assignments, requests, categories, reload, setAssignments, setRequests };
}
