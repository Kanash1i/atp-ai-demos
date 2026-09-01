import { useSyncExternalStore } from 'react';
import { snapshot, subscribe } from './auth';

/** 登录态。外部 store，因为 api.ts 在 React 之外也要清它（401 时） */
export function useSession() {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
