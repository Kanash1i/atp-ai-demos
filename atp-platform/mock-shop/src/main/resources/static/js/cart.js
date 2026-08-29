/**
 * 商品与购物车状态。
 *
 * ⚠️ **首次访问会自动预置一车商品** —— 这不是偷懒，是必需的：
 *    每条案例跑在**全新的 BrowserContext** 里，localStorage 是空的，
 *    而 CART 的案例前置条件写着「购物车内已有至少 2 件不同商品」。
 *    真实被测环境本来就有预置数据，这里等价。
 */
const Cart = (() => {
  const PRODUCTS = {
    P100: { name: 'ワイヤレスイヤホン',   price: 12800, stock: 'in'  },
    P200: { name: 'スマートウォッチ',     price: 24000, stock: 'in'  },
    // ⚠️ 缺货商品是 ATP-CART-0007 的前提：结算时要被拦下并高亮
    P300: { name: 'モバイルバッテリー',   price: 3980,  stock: 'out' },
  };

  const KEY = 'atp_cart';
  const COUPON_KEY = 'atp_coupon';
  const COUPONS = { 'SAVE1000': 1000, 'WELCOME500': 500 };

  // 免运费门槛。案例前置条件是「金额未达免运费门槛」，预置车的小计要低于它
  const FREE_SHIPPING_THRESHOLD = 50000;
  const SHIPPING_FEE = 800;

  const defaultCart = () => [
    { id: 'P100', qty: 1 },
    { id: 'P200', qty: 1 },
    { id: 'P300', qty: 1 },
  ];

  function read() {
    const raw = localStorage.getItem(KEY);
    return raw === null ? [] : JSON.parse(raw);
  }

  /**
   * 预置一车商品 —— **只有购物车页会调用**。
   *
   * ⚠️ 不能放进 read()：商品页的案例（ATP-CART-0003）期望
   *    「加入购物车后角标变成 1」，也就是初始必须是空车。
   *    而购物车页的案例前置写着「已有至少 2 件商品」。两边要求相反，
   *    所以预置的时机只能是「打开购物车页」。
   *
   * ⚠️ 判据是 key 是否存在，不是数组是否为空：
   *    ATP-CART-0009 清空购物车后要看到空车状态，
   *    按「空就预置」的话，清空之后立刻又冒出三件商品。
   */
  function seedIfAbsent() {
    if (localStorage.getItem(KEY) === null) {
      write(defaultCart());
    }
  }

  const write = (items) => localStorage.setItem(KEY, JSON.stringify(items));

  const rows = () => read().map(i => ({ ...i, ...PRODUCTS[i.id], subtotal: PRODUCTS[i.id].price * i.qty }));

  const subtotal = () => rows().reduce((n, r) => n + r.subtotal, 0);
  const discount = () => COUPONS[localStorage.getItem(COUPON_KEY)] || 0;
  const shipping = () => (subtotal() >= FREE_SHIPPING_THRESHOLD ? 0 : SHIPPING_FEE);
  const grandTotal = () => Math.max(0, subtotal() + shipping() - discount());

  /** ¥12,800 —— 千分位是案例断言「¥」时的实际展示形态 */
  const yen = (n) => '¥' + n.toLocaleString('ja-JP');

  return {
    PRODUCTS, rows, subtotal, discount, shipping, grandTotal, yen, seedIfAbsent,
    count: () => read().reduce((n, i) => n + i.qty, 0),
    lineCount: () => read().length,
    add(id) {
      const items = read();
      const hit = items.find(i => i.id === id);
      if (hit) { hit.qty += 1; } else { items.push({ id, qty: 1 }); }
      write(items);
    },
    setQty(index, qty) {
      const items = read();
      if (items[index]) { items[index].qty = Math.max(1, parseInt(qty, 10) || 1); write(items); }
    },
    removeAt(index) {
      const items = read();
      items.splice(index, 1);
      write(items);
    },
    clear() { write([]); localStorage.removeItem(COUPON_KEY); },
    hasOutOfStock: () => rows().some(r => r.stock === 'out'),
    applyCoupon(code) {
      if (!COUPONS[code]) return false;
      localStorage.setItem(COUPON_KEY, code);
      return true;
    },
    appliedCoupon: () => localStorage.getItem(COUPON_KEY),
  };
})();
