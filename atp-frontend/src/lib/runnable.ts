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
 * 刻意保留的不稳定案例。
 *
 * ⚠️ 这里原本叫 KNOWN_CONFLICT_CASE，说法是「它与 ATP-CART-0007 对购物车初始状态的
 * 要求相反」。那个说法是**错的** —— 查执行历史，它在 0007 没跑的批次里照样时绿时红，
 * 跟 0007 跑不跑没关系。
 *
 * 真实成因在案例自己身上：precondition 写着「购物车内有 1 件有货商品」，
 * 但第 1 步直接 OPEN_URL 打开购物车，**没有任何一步去准备这个状态**。
 * 而购物车初始有三件商品（含缺货的 P300），且会被前面跑过的任何案例改掉。
 *
 * 于是第 2 步的「结算」被缺货拦下，第 3 步等的地址选项永远不出现 ——
 * 报错落在第 3 步，离真正的原因隔着一层。提示渲染得快时则是第 2 步的断言失败，
 * 两种表现交替出现，取决于点击时机。
 *
 * 留着它是有意的：这是测试数据准备缺失的典型症状，比一张全绿的表更能说明问题。
 * 不要当缺陷报，也不要说成「设计冲突」。
 */
export const UNSTABLE_CASE = 'ATP-ORDER-0003';
