/**
 * 生成 UUID v4。
 *
 * ⚠️ **不要直接用 `crypto.randomUUID()`** —— 它只在 **secure context** 里存在：
 *
 *     http://localhost      浏览器特批为 secure context   有
 *     https://…             secure context                有
 *     http://154.83.13.157  **不是** secure context       没有 → TypeError
 *
 * dev 跑在 localhost 所以永远撞不上；部署成裸 IP 的 HTTP 就整棵 React 树卸载、白屏。
 * 线上已经真实发生过一次（AI 助手面板与「新建案例」按钮）。
 *
 * 三级降级，尽量保住随机性质量：
 *   1. `crypto.randomUUID()`        —— 有就用，最正
 *   2. `crypto.getRandomValues()`   —— **在非安全上下文里也有**（只有 randomUUID
 *      和 crypto.subtle 被门禁），所以这一级仍然是密码学强随机
 *   3. `Math.random()`              —— 最后兜底，不该走到
 *
 * 三级产出的都是**格式合法**的 v4：`caseId` 是幂等键，后端 `tc_case.case_id`
 * 是 `VARCHAR(36)`，CLI 那侧也按 UUID 形状校验，格式不对会在落库时才炸。
 *
 * 长期正解是上 HTTPS（有域名之后 Caddy 自动签发，`randomUUID` 自然回来），
 * 但这个 fallback 该留着 —— 它挡的不只是这一次。
 */
export function uuidv4(): string {
  const c: Crypto | undefined = globalThis.crypto;

  if (typeof c?.randomUUID === 'function') {
    return c.randomUUID();
  }

  if (typeof c?.getRandomValues === 'function') {
    const b = c.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40; // version 4
    b[8] = (b[8] & 0x3f) | 0x80; // variant 10xx
    const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0;
    return (ch === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
