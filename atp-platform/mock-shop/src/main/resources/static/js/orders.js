/**
 * 订单状态。
 *
 * ⚠️ 同样是首次访问时预置 —— 每条案例跑在全新的 BrowserContext 里，
 *    而案例前置写着「账号下已有至少 1 笔订单」。
 *
 * ⚠️ 第一笔必须是**待支付**：ATP-ORDER-0009 打开列表就断言首行状态是「支払い待ち」，
 *    ATP-ORDER-0006 直接点首行的取消按钮，ATP-ORDER-0001 断言首行第 4 列含「支払い」。
 *    顺序变了这三条一起挂。
 */
const Orders = (() => {
  const KEY = 'atp_orders';

  const STATUS = {
    PENDING:   '支払い待ち',
    PAID:      '支払い済み',
    DELIVERED: '配達済み',
    CANCELLED: 'キャンセル済み',
  };

  const seed = () => ([
    { no: 'ORD-20260830-0001', date: '2026-08-30 09:12', amount: 12800, status: 'PENDING',
      items: [{ name: 'ワイヤレスイヤホン', qty: 1, price: 12800 }],
      address: '東京都渋谷区神宮前1-2-3  田中 直樹' },
    { no: 'ORD-20260829-0002', date: '2026-08-29 15:40', amount: 24000, status: 'DELIVERED',
      items: [{ name: 'スマートウォッチ', qty: 1, price: 24000 }],
      address: '東京都渋谷区神宮前1-2-3  田中 直樹' },
    { no: 'ORD-20260828-0003', date: '2026-08-28 11:05', amount: 3980, status: 'CANCELLED',
      items: [{ name: 'モバイルバッテリー', qty: 1, price: 3980 }],
      address: '東京都渋谷区神宮前1-2-3  田中 直樹' },
  ]);

  function all() {
    const raw = localStorage.getItem(KEY);
    if (raw === null) { const s = seed(); write(s); return s; }
    return JSON.parse(raw);
  }
  const write = (list) => localStorage.setItem(KEY, JSON.stringify(list));

  return {
    STATUS,
    all,
    find: (no) => all().find(o => o.no === no),
    label: (o) => STATUS[o.status],
    update(no, patch) {
      const list = all();
      const hit = list.find(o => o.no === no);
      if (hit) { Object.assign(hit, patch); write(list); }
      return hit;
    },
    /** 下单：生成订单号并置为待支付。ATP-ORDER-0003 捕获这个号 */
    create(amount, items) {
      const list = all();
      const seq = String(list.length + 1).padStart(4, '0');
      const no = 'ORD-' + new Date().toISOString().slice(0, 10).replace(/-/g, '') + '-' + seq;
      list.unshift({ no, date: new Date().toISOString().slice(0, 16).replace('T', ' '),
                     amount, status: 'PENDING', items,
                     address: '東京都渋谷区神宮前1-2-3  田中 直樹' });
      write(list);
      return no;
    },
    yen: (n) => '¥' + n.toLocaleString('ja-JP'),
  };
})();
