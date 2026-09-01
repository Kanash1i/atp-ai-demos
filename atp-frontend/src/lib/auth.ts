import type { AuthUser } from './types';

/**
 * 登录态。存 localStorage，跨刷新保持。
 *
 * ⚠️ 这里**不保存密码**，也不内置任何演示口令 —— 口令在后端的 `.env`
 * （`ATP_DEMO_PASSWORD`），前端只负责把用户敲进来的那一次转成 token。
 */
const TOKEN_KEY = 'atp.token';
const USER_KEY = 'atp.user';

interface Session {
  token: string;
  user: AuthUser;
}

let current: Session | null = load();
const listeners = new Set<() => void>();

function load(): Session | null {
  try {
    const token = localStorage.getItem(TOKEN_KEY);
    const raw = localStorage.getItem(USER_KEY);
    if (!token || !raw) return null;
    return { token, user: JSON.parse(raw) as AuthUser };
  } catch {
    return null;
  }
}

function emit(): void {
  listeners.forEach((fn) => fn());
}

export function getSession(): Session | null {
  return current;
}

export function getToken(): string | null {
  return current?.token ?? null;
}

export function setSession(token: string, user: AuthUser): void {
  current = { token, user };
  try {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  } catch {
    /* 隐私模式下写不进去，本次会话内仍然可用 */
  }
  emit();
}

/**
 * 清除登录态。
 *
 * ⚠️ 只在 **401**（没带 token / token 无效或过期）时调 ——
 * **403 不要调**：那是 token 有效但缺 scope，重新登录也拿不到那个权限，
 * 把人踢回登录页只会让他再登一次、再撞一次同样的 403。
 */
export function clearSession(): void {
  current = null;
  try {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  } catch {
    /* 同上 */
  }
  emit();
}

/** 给 useSyncExternalStore 用 */
export function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function snapshot(): Session | null {
  return current;
}
