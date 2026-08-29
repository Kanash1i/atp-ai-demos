/**
 * 有 mock 页面、能真实跑通的模块。
 *
 * `mock-shop` 只做了这三个 —— 它们在设计稿里案例数最多，也覆盖了
 * 「登录失败计数」「库存状态」「订单状态流转」三种有状态的页面行为。
 * 其余模块（SEARCH / USER / PAYMENT / REPORT / ADMIN 及移动端）没有对应页面，
 * 派发出去会**全部超时失败** —— 所以派发面板里根本不给选，而不是让人自己踩。
 */
export const RUNNABLE_MODULE_CODES = ['LOGIN', 'CART', 'ORDER'] as const;

export function isRunnableModule(moduleCode: string): boolean {
  return (RUNNABLE_MODULE_CODES as readonly string[]).includes(moduleCode);
}

/**
 * 刻意保留的失败案例。
 *
 * 它与 ATP-CART-0007 对购物车初始状态的要求相反：一条要求含缺货商品并被结算拦下，
 * 一条要求没有缺货能进结算页。真实平台靠每条案例独立的测试数据准备解决。
 * 演示时这条红的比一张全绿的表更能说明测试数据管理的重要性 —— 不要当缺陷报。
 */
export const KNOWN_CONFLICT_CASE = 'ATP-ORDER-0003';
