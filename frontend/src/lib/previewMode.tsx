import { createContext, useContext } from 'react';

/**
 * Design-review preview switch. The unauthenticated /dashboard-ui-preview route wraps the real
 * dashboard in `<PreviewModeContext.Provider value>` so each widget shows canned mock data instead
 * of calling the API. Default is false, so every real (authenticated) screen is unaffected.
 */
export const PreviewModeContext = createContext(false);

export function usePreviewMode(): boolean {
  return useContext(PreviewModeContext);
}
