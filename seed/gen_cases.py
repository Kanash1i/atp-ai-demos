#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 corpus/cases/ 下的 80 条案例 JSON。

为什么用生成器而不是手写 80 个文件：
  - 80 条 × 6 步 ≈ 500 个步骤，手写必然出现字段缺失、seq 跳号、枚举拼错
  - STD-005/006 的 wait_strategy 在真实平台上是"保存时自动补全"的，
    这里同样自动补全，等于把规范编码进了语料生成过程本身
  - 刻意植入的脏数据需要精确控制数量与分布，手写数不准

案例的标题、步骤、定位器都是逐条手写的 —— 生成器只负责序列化和规范补全，
不负责编内容。语料内容雷同会让检索评估失去意义。

⚠️ 全部为虚构合成数据，不涉及任何真实公司资产。

用法：python3 tools/gen_cases.py
"""

import json
import os
from datetime import datetime, timedelta

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "corpus", "cases")

MODULES = {
    "LOGIN": "M001", "SEARCH": "M002", "CART": "M003", "ORDER": "M004",
    "USER": "M005", "PAYMENT": "M006", "REPORT": "M007", "ADMIN": "M008",
}

AUTHORS = ["tanaka.y", "suzuki.k", "chen.wei", "yamada.t", "lim.jh", "sato.m"]

# STD-005 / STD-006：CLICK 必须 CLICKABLE，ASSERT_* 必须 VISIBLE。
# 真实平台在保存时自动补全，这里同样自动补全。
def default_wait(action):
    if action == "CLICK":
        return "CLICKABLE"
    if action.startswith("ASSERT_"):
        return "VISIBLE"
    if action in ("OPEN_URL", "SLEEP", "SWITCH_WINDOW"):
        return "NONE"
    return "PRESENCE"


class S(object):
    """一个步骤。locator 用 xp=/css=/id_= 三选一，不写表示该 action 不需要定位器。"""

    def __init__(self, action, xp=None, css=None, id_=None, name=None,
                 data=None, expect=None, wait=None, timeout=10,
                 on_failure="ABORT", desc=None, violates=None):
        self.action = action
        self.locator_type, self.locator_value = self._locator(xp, css, id_, name)
        self.data = data
        self.expect = expect
        self.wait = wait or default_wait(action)
        self.timeout = timeout
        self.on_failure = on_failure
        self.desc = desc
        self.violates = violates

    @staticmethod
    def _locator(xp, css, id_, name):
        for kind, value in (("XPATH", xp), ("CSS", css), ("ID", id_), ("NAME", name)):
            if value is not None:
                return kind, value
        return None, None


class C(object):
    """一条案例。

    序号不在这里写死 —— 按模块自动分配（见 assign_numbers）。
    手工编号在增删案例时必然出现跳号，而 case_code 的连续性是 STD-007 的要求。

    code_override 用于制造 STD-007 的命名违规。
    """

    def __init__(self, module, title, priority, steps,
                 precondition=None, browser="CHROME", timeout=30,
                 code_override=None, legacy=False, status="ACTIVE"):
        self.module = module
        self.seq_no = None        # assign_numbers 填
        self.title = title
        self.priority = priority
        self.steps = steps
        self.precondition = precondition
        self.browser = browser
        self.timeout = timeout
        self.code_override = code_override
        self.legacy = legacy      # 历史遗留案例，创建时间更早
        self.status = status


def build(case, index):
    """展开成完整 JSON。case_id 用确定性的假雪花 ID，保证重复生成结果一致。"""
    code = case.code_override or "ATP-%s-%04d" % (case.module, case.seq_no)
    case_id = "%d%04d" % (7_314_500_000_000, index)

    # 历史遗留案例的时间戳更早，呼应「规范建立之前留下的技术债」这个设定
    base = datetime(2023, 2, 6) if case.legacy else datetime(2025, 3, 11)
    created = base + timedelta(days=index * 3, hours=(index % 7) + 9, minutes=(index * 13) % 60)
    updated = created + timedelta(days=(index % 40) + 1, hours=(index % 5))

    steps = []
    for i, s in enumerate(case.steps, start=1):
        steps.append({
            "step_id": "%s-S%02d" % (case_id, i),
            "case_id": case_id,
            "seq": i,                      # 从 1 连续，生成器保证不跳号
            "action": s.action,
            "locator_type": s.locator_type,
            "locator_value": s.locator_value,
            "input_data": s.data,
            "expected": s.expect,
            "wait_strategy": s.wait,
            "wait_timeout_sec": s.timeout,
            "on_failure": s.on_failure,
            "description": s.desc,
        })

    violations = sorted({s.violates for s in case.steps if s.violates})
    if case.code_override:
        violations = sorted(set(violations) | {"STD-007"})
    if not any(s.action.startswith("ASSERT_") for s in case.steps):
        violations = sorted(set(violations) | {"STD-008"})

    return {
        "case_id": case_id,
        "case_code": code,
        "title": case.title,
        "module_id": MODULES[case.module],
        "module_code": case.module,
        "priority": case.priority,
        "author": AUTHORS[index % len(AUTHORS)],
        "precondition": case.precondition,
        "status": case.status,
        "browser": case.browser,
        "timeout_sec": case.timeout,
        "created_at": created.strftime("%Y-%m-%d %H:%M:%S"),
        "updated_at": updated.strftime("%Y-%m-%d %H:%M:%S"),
        "steps": steps,
        # 下面两项不是 tc_case 的表字段，是入库时算好存进 Qdrant payload 的，
        # 供「这条案例能参考，但它违反了 STD-004，别照抄」这类回答使用
        "has_violation": bool(violations),
        "violation_codes": violations,
        "step_count": len(steps),
        "actions_used": sorted({s.action for s in case.steps}),
    }


# ─────────────────────────────────────────────────────────────
#  语料本体。脏数据用 violates= / code_override= 显式标注，
#  自检器会核对实际分布与 §4.2 的设计是否一致。
# ─────────────────────────────────────────────────────────────

CASES = []

# ── M001 LOGIN ──────────────────────────────────────────────
CASES += [
    C("LOGIN", "正确的用户名密码登录，应跳转到首页并显示用户昵称", "P0", [
        S("OPEN_URL", data="${base_url}/login"),
        S("INPUT", xp='//input[@data-testid="login-username"]', data="${test_user}"),
        S("INPUT", xp='//input[@data-testid="login-password"]', data="@cred{test_user_password}"),
        S("CLICK", xp='//button[@data-testid="login-submit"]'),
        S("ASSERT_VISIBLE", xp='//header[@data-testid="home-header"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="user-nickname"]', expect="${test_user_nickname}"),
    ], precondition="测试账号 ${test_user} 处于正常状态，未被锁定"),

    C("LOGIN", "密码错误时登录，应提示凭据无效且不暴露账号是否存在", "P1", [
        S("OPEN_URL", data="${base_url}/login"),
        S("INPUT", xp='//input[@data-testid="login-username"]', data="${test_user}"),
        S("INPUT", xp='//input[@data-testid="login-password"]', data="wrong_password_001"),
        S("CLICK", xp='//button[@data-testid="login-submit"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="login-error"]',
          expect="ユーザー名またはパスワードが正しくありません",
          desc="错误提示必须同时覆盖两种情况，不能提示「用户不存在」，否则可被用于枚举账号"),
    ]),

    C("LOGIN", "密码连续输错 5 次后登录，应提示账号被锁定", "P1", [
        S("OPEN_URL", data="${base_url}/login"),
        S("INPUT", xp='//input[@data-testid="login-username"]', data="${lockout_test_user}"),
        S("INPUT", xp='//input[@data-testid="login-password"]', data="@cred{lockout_wrong_pwd}"),
        S("CLICK", xp='//button[@data-testid="login-submit"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="login-error"]', expect="アカウントがロックされています"),
        S("ASSERT_VISIBLE", xp='//a[@data-testid="unlock-guide-link"]'),
    ], precondition="${lockout_test_user} 已被数据准备接口置为连续失败 4 次的状态"),

    C("LOGIN", "用户名为空时提交登录，应阻止提交并高亮必填项", "P2", [
        S("OPEN_URL", data="${base_url}/login"),
        S("INPUT", xp='//input[@data-testid="login-password"]', data="@cred{test_user_password}"),
        S("CLICK", xp='//button[@data-testid="login-submit"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="username-field-error"]', expect="必須項目です"),
        S("ASSERT_NOT_EXIST", xp='//header[@data-testid="home-header"]',
          desc="确认没有发生跳转。前一步的正向断言已保证页面就绪，此处否定断言不会假阳性"),
    ]),

    C("LOGIN", "登出后回退浏览器，会话应已失效并跳回登录页", "P1", [
        S("OPEN_URL", data="${base_url}/login"),
        S("INPUT", xp='//input[@data-testid="login-username"]', data="${test_user}"),
        S("INPUT", xp='//input[@data-testid="login-password"]', data="@cred{test_user_password}"),
        S("CLICK", xp='//button[@data-testid="login-submit"]'),
        S("ASSERT_VISIBLE", xp='//header[@data-testid="home-header"]'),
        S("CLICK", xp='//button[@data-testid="user-menu-toggle"]'),
        S("CLICK", xp='//button[@data-testid="logout-button"]'),
        S("OPEN_URL", data="${base_url}/mypage"),
        S("ASSERT_VISIBLE", xp='//form[@data-testid="login-form"]',
          desc="直接访问需登录的页面应被重定向到登录页"),
    ]),

    C("LOGIN", "勾选记住登录状态后关闭标签页重开，应保持登录", "P2", [
        S("OPEN_URL", data="${base_url}/login"),
        S("INPUT", xp='//input[@data-testid="login-username"]', data="${test_user}"),
        S("INPUT", xp='//input[@data-testid="login-password"]', data="@cred{test_user_password}"),
        S("CLICK", xp='//input[@data-testid="remember-me"]'),
        S("CLICK", xp='//button[@data-testid="login-submit"]'),
        S("ASSERT_VISIBLE", xp='//header[@data-testid="home-header"]'),
        S("OPEN_URL", data="${base_url}/mypage"),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="mypage-profile"]'),
    ]),

    C("LOGIN", "通过邮件链接重置密码，应能用新密码登录", "P1", [
        S("OPEN_URL", data="${base_url}/password/reset?token=${reset_token}"),
        S("INPUT", xp='//input[@data-testid="new-password"]', data="@cred{reset_new_password}"),
        S("INPUT", xp='//input[@data-testid="new-password-confirm"]', data="@cred{reset_new_password}"),
        S("CLICK", xp='//button[@data-testid="reset-submit"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="reset-success"]', expect="パスワードを変更しました"),
    ], precondition="已通过数据准备接口为 ${reset_target_user} 生成有效的 ${reset_token}"),

    C("LOGIN", "会话超时后操作页面，应自动登出并保留原页面地址", "P2", [
        S("OPEN_URL", data="${base_url}/mypage"),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="session-expired-dialog"]', timeout=60,
          desc="等待会话超时弹窗。超时阈值由测试环境配置为 30 秒"),
        S("CLICK", xp='//button[@data-testid="relogin-button"]'),
        S("ASSERT_TEXT", xp='//input[@data-testid="redirect-hint"]', expect="/mypage"),
    ], precondition="测试环境的会话超时时间已配置为 30 秒", timeout=120),

    C("LOGIN", "从 SSO 门户跳转登录，应免密进入首页", "P1", [
        S("OPEN_URL", data="${sso_portal_url}/apps/atp-shop"),
        S("CLICK", xp='//a[@data-testid="sso-app-launch"]'),
        S("SWITCH_WINDOW", data="ATP Shop"),
        S("ASSERT_VISIBLE", xp='//header[@data-testid="home-header"]',
          desc="SSO 会在新标签页打开应用，必须先切换窗口"),
        S("ASSERT_TEXT", xp='//span[@data-testid="login-method"]', expect="SSO"),
    ]),

]

# ── M002 SEARCH ─────────────────────────────────────────────
CASES += [
    C("SEARCH", "用关键词搜索商品，应返回结果列表并显示命中件数", "P0", [
        S("OPEN_URL", data="${base_url}/"),
        S("INPUT", xp='//input[@data-testid="search-keyword"]', data="ノートPC"),
        S("CLICK", xp='//button[@data-testid="search-submit"]'),
        S("ASSERT_VISIBLE", xp='//ul[@data-testid="search-result-list"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="result-count"]', expect="件"),
        S("ASSERT_TEXT", xp='//li[@data-testid="result-item"][1]', expect="ノートPC"),
    ]),

    C("SEARCH", "搜索不存在的关键词，应显示无结果引导而非空白页", "P1", [
        S("OPEN_URL", data="${base_url}/"),
        S("INPUT", xp='//input[@data-testid="search-keyword"]', data="zzzz_no_such_product_9999"),
        S("CLICK", xp='//button[@data-testid="search-submit"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="empty-result"]', expect="見つかりませんでした"),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="search-suggestion"]',
          desc="无结果时应给出替代建议，这是产品明确要求的体验"),
    ]),

    C("SEARCH", "组合品类与价格区间筛选，结果应同时满足两个条件", "P1", [
        S("OPEN_URL", data="${base_url}/search?q=PC"),
        S("CLICK", xp='//label[@data-testid="filter-category-laptop"]'),
        S("INPUT", xp='//input[@data-testid="filter-price-min"]', data="50000"),
        S("INPUT", xp='//input[@data-testid="filter-price-max"]', data="150000"),
        S("CLICK", xp='//button[@data-testid="filter-apply"]'),
        S("ASSERT_VISIBLE", xp='//span[@data-testid="active-filter-category"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="active-filter-price"]', expect="¥50,000"),
    ]),

    C("SEARCH", "按价格升序排序，首项价格应不高于末项", "P2", [
        S("OPEN_URL", data="${base_url}/search?q=マウス"),
        S("SELECT", xp='//select[@data-testid="sort-order"]', data="価格の安い順"),
        S("ASSERT_VISIBLE", xp='//ul[@data-testid="search-result-list"]'),
        S("ASSERT_TEXT", xp='//select[@data-testid="sort-order"]', expect="価格の安い順"),
    ]),

    C("SEARCH", "翻到第二页，应显示对应页码且内容不同于第一页", "P2", [
        S("OPEN_URL", data="${base_url}/search?q=ケーブル"),
        S("ASSERT_TEXT", xp='//li[@data-testid="result-item"][1]', expect="->first_page_top_item"),
        S("SCROLL_TO", xp='//nav[@data-testid="pagination"]'),
        S("CLICK", xp='//a[@data-testid="page-link-2"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="current-page"]', expect="2"),
        S("ASSERT_NOT_EXIST", xp='//li[@data-testid="result-item"][1][contains(.,"${first_page_top_item}")]',
          desc="第二页首项不应与第一页首项相同"),
    ]),

    C("SEARCH", "输入两个字符后应弹出自动补全候选并可点选", "P2", [
        S("OPEN_URL", data="${base_url}/"),
        S("INPUT", xp='//input[@data-testid="search-keyword"]', data="ノー"),
        S("ASSERT_VISIBLE", xp='//ul[@data-testid="search-suggest-list"]'),
        S("CLICK", xp='//li[@data-testid="search-suggest-item"][1]'),
        S("ASSERT_VISIBLE", xp='//ul[@data-testid="search-result-list"]',
          desc="候选必须点选才会确定，只输入文本不会触发搜索"),
    ]),

    C("SEARCH", "关键词为空时点击搜索，应停留在原页并提示输入", "P2", [
        S("OPEN_URL", data="${base_url}/"),
        S("CLICK", xp='//button[@data-testid="search-submit"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="search-input-error"]', expect="キーワードを入力してください"),
    ]),



    C("SEARCH", "从搜索结果点击商品，应进入对应商品的详情页", "P1", [
        S("OPEN_URL", data="${base_url}/search?q=モニター"),
        S("ASSERT_TEXT", xp='//li[@data-testid="result-item"][1]//h3', expect="->target_product_name"),
        S("CLICK", xp='//li[@data-testid="result-item"][1]//a[@data-testid="result-link"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="product-detail"]'),
        S("ASSERT_TEXT", xp='//h1[@data-testid="product-title"]', expect="${target_product_name}"),
    ]),
]

# ── M003 CART ───────────────────────────────────────────────
CASES += [
    C("CART", "从商品详情页加入购物车，购物车图标数量应加一", "P0", [
        S("OPEN_URL", data="${base_url}/products/${sample_product_id}"),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-badge-count"]', expect="->before_count"),
        S("CLICK", xp='//button[@data-testid="add-to-cart"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="add-to-cart-toast"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-badge-count"]', expect="1"),
    ], precondition="当前账号购物车为空"),

    C("CART", "在购物车修改商品数量，小计应同步更新", "P1", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("INPUT", xp='//input[@data-testid="cart-item-qty"][1]', data="3"),
        S("CLICK", xp='//button[@data-testid="cart-qty-update"][1]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-item-subtotal"][1]', expect="¥"),
        S("ASSERT_TEXT", xp='//input[@data-testid="cart-item-qty"][1]', expect="3"),
    ], precondition="购物车内已有至少 1 件商品"),

    C("CART", "删除购物车中的商品，该行应消失且合计减少", "P1", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("ASSERT_TEXT", xp='//tr[@data-testid="cart-row"][1]//span[@data-testid="item-name"]',
          expect="->removed_item_name"),
        S("CLICK", xp='//button[@data-testid="cart-item-remove"][1]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="remove-success-toast"]',
          desc="先确认删除完成的正向反馈，再做否定断言，避免页面未刷新时假阳性"),
        S("ASSERT_NOT_EXIST", xp='//tr[@data-testid="cart-row"][.//span[text()="${removed_item_name}"]]'),
    ], precondition="购物车内已有至少 2 件不同商品"),

    C("CART", "购物车合计金额应等于各行小计之和加运费", "P0", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("ASSERT_VISIBLE", xp='//table[@data-testid="cart-table"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-subtotal"]', expect="¥"),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-shipping-fee"]', expect="¥"),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-grand-total"]', expect="¥"),
    ], precondition="购物车内已有商品，且金额未达免运费门槛"),

    C("CART", "结算含缺货商品的购物车，应阻止提交并高亮缺货项", "P1", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("ASSERT_VISIBLE", xp='//tr[@data-testid="cart-row"][@data-stock="out"]'),
        S("CLICK", xp='//button[@data-testid="cart-checkout"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="cart-error"]', expect="在庫切れ"),
        S("ASSERT_NOT_EXIST", xp='//div[@data-testid="checkout-address-form"]',
          desc="不应进入结算页"),
    ], precondition="购物车内含一件已置为缺货的商品"),

    C("CART", "应用有效优惠券，合计应扣减对应金额", "P1", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-grand-total"]', expect="->total_before_coupon"),
        S("INPUT", xp='//input[@data-testid="coupon-code"]', data="${valid_coupon_code}"),
        S("CLICK", xp='//button[@data-testid="coupon-apply"]'),
        S("ASSERT_VISIBLE", xp='//span[@data-testid="coupon-applied-badge"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-discount"]', expect="-¥"),
    ], precondition="${valid_coupon_code} 为未使用且在有效期内的优惠券"),

    C("CART", "清空购物车后，应显示空车状态与继续购物入口", "P2", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("CLICK", xp='//button[@data-testid="cart-clear"]'),
        S("CLICK", xp='//button[@data-testid="confirm-dialog-ok"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="cart-empty"]', expect="カートに商品がありません"),
        S("ASSERT_VISIBLE", xp='//a[@data-testid="continue-shopping"]'),
    ], precondition="购物车内已有商品"),



    C("CART", "在另一浏览器登录同一账号，购物车内容应同步", "P3", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-item-count"]', expect="->cart_count_a"),
        S("ASSERT_VISIBLE", xp='//table[@data-testid="cart-table"]'),
    ], precondition="已在 Firefox 会话中向该账号购物车加入 2 件商品",
        browser="FIREFOX"),
]

# ── M004 ORDER ──────────────────────────────────────────────
CASES += [
    C("ORDER", "从购物车完成下单，订单状态应为待支付且生成订单号", "P0", [
        S("OPEN_URL", data="${base_url}/cart"),
        S("CLICK", xp='//button[@data-testid="cart-checkout"]'),
        S("CLICK", xp='//label[@data-testid="address-option"][1]'),
        S("CLICK", xp='//button[@data-testid="checkout-next"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="order-confirm-panel"]'),
        S("CLICK", xp='//button[@data-testid="order-submit"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-no"]', expect="->new_order_no"),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-status"]', expect="支払い待ち"),
    ], precondition="购物车内有 1 件有货商品，账号已有默认收货地址", timeout=90),

    C("ORDER", "订单列表应按下单时间倒序显示且包含最新订单", "P1", [
        S("OPEN_URL", data="${base_url}/orders"),
        S("ASSERT_VISIBLE", xp='//table[@data-testid="order-list"]'),
        S("ASSERT_TEXT", xp='//tr[@data-testid="order-row"][1]//span[@data-testid="order-no"]',
          expect="ORD-"),
    ], precondition="账号下已有至少 1 笔订单"),

    C("ORDER", "打开订单详情，应显示商品明细与收货信息", "P1", [
        S("OPEN_URL", data="${base_url}/orders"),
        S("CLICK", xp='//tr[@data-testid="order-row"][1]//a[@data-testid="order-detail-link"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="order-detail"]'),
        S("ASSERT_VISIBLE", xp='//table[@data-testid="order-items"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="order-shipping-info"]'),
    ]),

    C("ORDER", "取消待支付订单，状态应变为已取消且不可再支付", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}"),
        S("CLICK", xp='//button[@data-testid="order-cancel"]'),
        S("INPUT", xp='//textarea[@data-testid="cancel-reason"]', data="テストのためキャンセル"),
        S("CLICK", xp='//button[@data-testid="cancel-confirm"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-status"]', expect="キャンセル済み"),
        S("ASSERT_NOT_EXIST", xp='//button[@data-testid="order-pay"]'),
    ], precondition="${pending_order_no} 为该账号下待支付状态的订单"),

    C("ORDER", "申请退货时上传不良品照片，应生成退货单并显示附件", "P2", [
        S("OPEN_URL", data="${base_url}/orders/${delivered_order_no}"),
        S("CLICK", xp='//button[@data-testid="order-return-apply"]'),
        S("SELECT", xp='//select[@data-testid="return-reason"]', data="商品が不良品"),
        S("INPUT", xp='//textarea[@data-testid="return-detail"]', data="梱包を開けたら画面に傷がありました"),
        S("UPLOAD", xp='//input[@data-testid="return-photo"]',
          data="/testdata/return/defect_screen.png", wait="PRESENCE",
          desc="上传控件是隐藏的 input[type=file]，页面上显示的是自定义按钮，"
               "所以等待策略用 PRESENCE 而非 VISIBLE"),
        S("ASSERT_TEXT", xp='//span[@data-testid="uploaded-filename"]', expect="defect_screen.png"),
        S("CLICK", xp='//button[@data-testid="return-submit"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="return-no"]', expect="RTN-"),
    ], precondition="${delivered_order_no} 为已收货且在退货期内的订单，"
                    "且 /testdata/return/defect_screen.png 已放置在共享存储"),

    C("ORDER", "结算时新增收货地址，应可选中并带入订单", "P1", [
        S("OPEN_URL", data="${base_url}/checkout"),
        S("CLICK", xp='//button[@data-testid="address-add-new"]'),
        S("INPUT", xp='//input[@data-testid="address-name"]', data="山田 太郎"),
        S("INPUT", xp='//input[@data-testid="address-zip"]', data="1500001"),
        S("INPUT", xp='//input[@data-testid="address-detail"]', data="東京都渋谷区神宮前1-2-3"),
        S("INPUT", xp='//input[@data-testid="address-phone"]', data="09012345678"),
        S("CLICK", xp='//button[@data-testid="address-save"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="selected-address"]', expect="渋谷区神宮前"),
    ], precondition="购物车内有商品"),

    C("ORDER", "支付完成后订单状态应从待支付流转为已支付", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}"),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-status"]', expect="支払い待ち"),
        S("CLICK", xp='//button[@data-testid="order-pay"]'),
        S("CLICK", xp='//label[@data-testid="pay-method-credit"]'),
        S("CLICK", xp='//button[@data-testid="pay-submit"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-status"]', expect="支払い済み", timeout=30),
    ], precondition="${pending_order_no} 为待支付订单，测试支付网关处于沙箱模式", timeout=120),

    C("ORDER", "按状态筛选订单列表，结果应只含该状态的订单", "P2", [
        S("OPEN_URL", data="${base_url}/orders"),
        S("SELECT", xp='//select[@data-testid="order-status-filter"]', data="キャンセル済み"),
        S("CLICK", xp='//button[@data-testid="order-filter-apply"]'),
        S("ASSERT_TEXT", xp='//tr[@data-testid="order-row"][1]//span[@data-testid="order-status"]',
          expect="キャンセル済み"),
    ], precondition="账号下存在至少 1 笔已取消订单"),


]

# ── M005 USER ───────────────────────────────────────────────
CASES += [
    C("USER", "打开个人中心，应显示昵称邮箱与会员等级", "P2", [
        S("OPEN_URL", data="${base_url}/mypage"),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="mypage-profile"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="profile-email"]', expect="@"),
        S("ASSERT_VISIBLE", xp='//span[@data-testid="member-rank"]'),
    ]),

    C("USER", "修改昵称并上传头像，页头显示应同步更新", "P2", [
        S("OPEN_URL", data="${base_url}/mypage/profile"),
        S("INPUT", xp='//input[@data-testid="profile-nickname"]', data="テスト太郎_${run_id}"),
        S("UPLOAD", xp='//input[@data-testid="avatar-file"]',
          data="/testdata/user/avatar_64x64.png", wait="PRESENCE",
          desc="头像 input 被自定义按钮遮盖，用 PRESENCE 等待"),
        S("ASSERT_VISIBLE", xp='//img[@data-testid="avatar-preview"]'),
        S("CLICK", xp='//button[@data-testid="profile-save"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="save-success-toast"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="user-nickname"]', expect="テスト太郎_${run_id}"),
    ], precondition="/testdata/user/avatar_64x64.png 已放置在共享存储"),

    C("USER", "新增收货地址，应出现在地址列表中", "P1", [
        S("OPEN_URL", data="${base_url}/mypage/addresses"),
        S("CLICK", xp='//button[@data-testid="address-add"]'),
        S("INPUT", xp='//input[@data-testid="address-name"]', data="鈴木 花子"),
        S("INPUT", xp='//input[@data-testid="address-zip"]', data="5300001"),
        S("INPUT", xp='//input[@data-testid="address-detail"]', data="大阪府大阪市北区梅田3-1-1"),
        S("INPUT", xp='//input[@data-testid="address-phone"]', data="08098765432"),
        S("CLICK", xp='//button[@data-testid="address-save"]'),
        S("ASSERT_TEXT", xp='//li[@data-testid="address-item"][last()]', expect="梅田3-1-1"),
    ]),

    C("USER", "删除收货地址，该地址应从列表消失", "P2", [
        S("OPEN_URL", data="${base_url}/mypage/addresses"),
        S("ASSERT_TEXT", xp='//li[@data-testid="address-item"][1]//span[@data-testid="address-detail"]',
          expect="->deleting_address"),
        S("CLICK", xp='//button[@data-testid="address-delete"][1]'),
        S("CLICK", xp='//button[@data-testid="confirm-dialog-ok"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="delete-success-toast"]'),
        S("ASSERT_NOT_EXIST", xp='//li[@data-testid="address-item"][contains(.,"${deleting_address}")]'),
    ], precondition="账号下已有至少 2 个收货地址"),

    C("USER", "设置默认地址后，结算页应默认选中该地址", "P2", [
        S("OPEN_URL", data="${base_url}/mypage/addresses"),
        S("CLICK", xp='//button[@data-testid="address-set-default"][2]'),
        S("ASSERT_VISIBLE", xp='//li[@data-testid="address-item"][2]//span[@data-testid="default-badge"]'),
        S("ASSERT_TEXT", xp='//li[@data-testid="address-item"][2]//span[@data-testid="address-detail"]',
          expect="->default_address"),
        S("OPEN_URL", data="${base_url}/checkout"),
        S("ASSERT_TEXT", xp='//div[@data-testid="selected-address"]', expect="${default_address}"),
    ], precondition="账号下已有至少 2 个收货地址，购物车内有商品"),

    C("USER", "修改密码后旧密码应失效、新密码可登录", "P1", [
        S("OPEN_URL", data="${base_url}/mypage/password"),
        S("INPUT", xp='//input[@data-testid="current-password"]', data="@cred{pwchange_old}"),
        S("INPUT", xp='//input[@data-testid="new-password"]', data="@cred{pwchange_new}"),
        S("INPUT", xp='//input[@data-testid="new-password-confirm"]', data="@cred{pwchange_new}"),
        S("CLICK", xp='//button[@data-testid="password-save"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="password-success"]', expect="パスワードを変更しました"),
        S("ASSERT_VISIBLE", xp='//form[@data-testid="login-form"]',
          desc="改密后平台会强制登出，应回到登录页"),
    ], precondition="使用专用账号 ${pwchange_user}，避免影响其他案例"),

    C("USER", "关闭邮件通知开关，设置应持久化", "P3", [
        S("OPEN_URL", data="${base_url}/mypage/notifications"),
        S("CLICK", xp='//input[@data-testid="notify-email-toggle"]'),
        S("CLICK", xp='//button[@data-testid="notification-save"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="save-success-toast"]'),
        S("OPEN_URL", data="${base_url}/mypage/notifications"),
        S("ASSERT_TEXT", xp='//span[@data-testid="notify-email-state"]', expect="オフ"),
    ]),



    C("USER", "绑定手机号需通过验证码校验", "P2", [
        S("OPEN_URL", data="${base_url}/mypage/phone"),
        S("INPUT", xp='//input[@data-testid="phone-number"]', data="09011112222"),
        S("CLICK", xp='//button[@data-testid="send-verify-code"]'),
        S("ASSERT_VISIBLE", xp='//input[@data-testid="verify-code"]'),
        S("INPUT", xp='//input[@data-testid="verify-code"]', data="${sandbox_verify_code}"),
        S("CLICK", xp='//button[@data-testid="phone-bind-submit"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="bound-phone"]', expect="090****2222"),
    ], precondition="测试环境短信网关为沙箱模式，验证码固定为 ${sandbox_verify_code}"),
]

# ── M006 PAYMENT ────────────────────────────────────────────
CASES += [
    C("PAYMENT", "用测试信用卡支付，应支付成功并显示交易号", "P0", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}/pay"),
        S("CLICK", xp='//label[@data-testid="pay-method-credit"]'),
        S("INPUT", xp='//input[@data-testid="card-number"]', data="@cred{sandbox_card_number}"),
        S("INPUT", xp='//input[@data-testid="card-expiry"]', data="12/30"),
        S("INPUT", xp='//input[@data-testid="card-cvv"]', data="@cred{sandbox_card_cvv}"),
        S("CLICK", xp='//button[@data-testid="pay-submit"]'),
        S("WAIT_FOR", xp='//div[@data-testid="pay-processing-mask"]', wait="PRESENCE", timeout=30,
          desc="等待「処理中」遮罩出现，确认请求已发出。"
               "遮罩消失由下一步的断言自动等待，不需要固定等待"),
        S("ASSERT_TEXT", xp='//div[@data-testid="pay-result"]', expect="お支払いが完了しました", timeout=30),
        S("ASSERT_TEXT", xp='//span[@data-testid="transaction-id"]', expect="TXN-"),
    ], precondition="支付网关为沙箱模式，${pending_order_no} 为待支付订单", timeout=120),

    C("PAYMENT", "使用会被拒付的测试卡，应提示失败且订单仍可重付", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}/pay"),
        S("CLICK", xp='//label[@data-testid="pay-method-credit"]'),
        S("INPUT", xp='//input[@data-testid="card-number"]', data="@cred{sandbox_declined_card}"),
        S("INPUT", xp='//input[@data-testid="card-expiry"]', data="12/30"),
        S("INPUT", xp='//input[@data-testid="card-cvv"]', data="@cred{sandbox_card_cvv}"),
        S("CLICK", xp='//button[@data-testid="pay-submit"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="pay-error"]', expect="お支払いが承認されませんでした", timeout=30),
        S("ASSERT_VISIBLE", xp='//button[@data-testid="pay-retry"]'),
    ], precondition="沙箱环境已配置固定拒付卡号", timeout=120),

    C("PAYMENT", "切换支付方式，表单应随之变化", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}/pay"),
        S("CLICK", xp='//label[@data-testid="pay-method-credit"]'),
        S("ASSERT_VISIBLE", xp='//input[@data-testid="card-number"]'),
        S("CLICK", xp='//label[@data-testid="pay-method-convenience"]'),
        S("ASSERT_VISIBLE", xp='//select[@data-testid="convenience-store"]'),
        S("ASSERT_NOT_EXIST", xp='//input[@data-testid="card-number"]'),
    ]),

    C("PAYMENT", "对已支付订单申请退款，应生成退款单并变更状态", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${paid_order_no}"),
        S("CLICK", xp='//button[@data-testid="refund-apply"]'),
        S("SELECT", xp='//select[@data-testid="refund-reason"]', data="注文の取り消し"),
        S("CLICK", xp='//button[@data-testid="refund-submit"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="refund-no"]', expect="RFD-"),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-status"]', expect="返金処理中"),
    ], precondition="${paid_order_no} 为已支付且在退款期内的订单"),

    C("PAYMENT", "申请开具发票，应可下载 PDF 且金额与订单一致", "P2", [
        S("OPEN_URL", data="${base_url}/orders/${paid_order_no}"),
        S("CLICK", xp='//button[@data-testid="invoice-request"]'),
        S("INPUT", xp='//input[@data-testid="invoice-title"]', data="株式会社テスト"),
        S("CLICK", xp='//button[@data-testid="invoice-submit"]'),
        S("ASSERT_VISIBLE", xp='//a[@data-testid="invoice-download"]', timeout=60),
        S("ASSERT_TEXT", xp='//span[@data-testid="invoice-amount"]', expect="¥"),
    ], precondition="${paid_order_no} 为已支付订单", timeout=150),

    C("PAYMENT", "选择 3 期分期，应显示每期金额与手续费", "P2", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}/pay"),
        S("CLICK", xp='//label[@data-testid="pay-method-credit"]'),
        S("SELECT", xp='//select[@data-testid="installment-count"]', data="3回"),
        S("ASSERT_VISIBLE", xp='//span[@data-testid="installment-per-month"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="installment-fee"]', expect="¥"),
    ]),


    C("PAYMENT", "余额不足的账户支付，应提示余额不足并保留订单", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}/pay"),
        S("CLICK", xp='//label[@data-testid="pay-method-balance"]'),
        S("CLICK", xp='//button[@data-testid="pay-submit"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="pay-error"]', expect="残高が不足しています"),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-status"]', expect="支払い待ち"),
    ], precondition="${low_balance_user} 的账户余额低于订单金额"),


    C("PAYMENT", "对已支付订单重复发起支付，应阻止并提示已完成", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${paid_order_no}/pay"),
        S("ASSERT_TEXT", xp='//div[@data-testid="pay-blocked"]', expect="お支払いは完了しています"),
        S("ASSERT_NOT_EXIST", xp='//button[@data-testid="pay-submit"]'),
    ], precondition="${paid_order_no} 为已支付订单"),
]

# ── M007 REPORT ─────────────────────────────────────────────
CASES += [
    C("REPORT", "生成指定日期的销售日报，应显示合计与明细", "P1", [
        S("OPEN_URL", data="${base_url}/admin/reports/daily"),
        S("INPUT", xp='//input[@data-testid="report-date"]', data="2025-11-01"),
        S("CLICK", xp='//button[@data-testid="report-generate"]'),
        S("ASSERT_VISIBLE", xp='//table[@data-testid="report-table"]', timeout=60),
        S("ASSERT_TEXT", xp='//span[@data-testid="report-total"]', expect="¥"),
    ], precondition="登录账号具有 REPORT 查看权限", timeout=120),

    C("REPORT", "导出报表为 CSV，应生成下载链接", "P1", [
        S("OPEN_URL", data="${base_url}/admin/reports/daily?date=2025-11-01"),
        S("CLICK", xp='//button[@data-testid="export-menu"]'),
        S("CLICK", xp='//button[@data-testid="export-csv"]'),
        S("ASSERT_VISIBLE", xp='//a[@data-testid="download-link"]', timeout=60),
        S("ASSERT_TEXT", xp='//span[@data-testid="export-filename"]', expect=".csv"),
    ], timeout=120),


    C("REPORT", "按品类筛选后导出，导出结果应保留筛选条件", "P1", [
        S("OPEN_URL", data="${base_url}/admin/reports/sales"),
        S("SELECT", xp='//select[@data-testid="filter-category"]', data="ノートPC"),
        S("CLICK", xp='//button[@data-testid="report-generate"]'),
        S("ASSERT_VISIBLE", xp='//table[@data-testid="report-table"]', timeout=60),
        S("CLICK", xp='//button[@data-testid="export-menu"]'),
        S("CLICK", xp='//button[@data-testid="export-csv"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="export-condition"]', expect="ノートPC"),
    ], timeout=150),

    C("REPORT", "导出超过 10 万行的报表，应转为异步任务并提示邮件通知", "P1", [
        S("OPEN_URL", data="${base_url}/admin/reports/sales"),
        S("INPUT", xp='//input[@data-testid="date-from"]', data="2020-01-01"),
        S("INPUT", xp='//input[@data-testid="date-to"]', data="2025-12-31"),
        S("CLICK", xp='//button[@data-testid="export-csv"]'),
        S("WAIT_FOR", xp='//div[@data-testid="export-size-check"]', wait="PRESENCE", timeout=60,
          desc="行数预估要跑一次 count 查询，比较慢。等它出现再判断是否转异步"),
        S("ASSERT_TEXT", xp='//div[@data-testid="async-export-notice"]',
          expect="メールでお知らせします", timeout=60),
        S("ASSERT_VISIBLE", xp='//a[@data-testid="export-task-link"]'),
    ], precondition="测试环境已导入超过 10 万行的历史订单数据", timeout=150),

    C("REPORT", "无报表权限的账号访问报表页，应被拒绝", "P1", [
        S("OPEN_URL", data="${base_url}/admin/reports/daily"),
        S("ASSERT_TEXT", xp='//div[@data-testid="permission-denied"]', expect="権限がありません"),
        S("ASSERT_NOT_EXIST", xp='//button[@data-testid="report-generate"]'),
    ], precondition="使用无 REPORT 权限的 ${viewer_user} 登录"),

    C("REPORT", "导出 PDF，应可预览且页数大于零", "P2", [
        S("OPEN_URL", data="${base_url}/admin/reports/daily?date=2025-11-01"),
        S("CLICK", xp='//button[@data-testid="export-menu"]'),
        S("CLICK", xp='//button[@data-testid="export-pdf"]'),
        S("ASSERT_VISIBLE", xp='//iframe[@data-testid="pdf-preview"]', timeout=60),
        S("SWITCH_FRAME", xp='//iframe[@data-testid="pdf-preview"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="pdf-page"]'),
        S("SWITCH_FRAME", xp="__default__", desc="回到主文档，否则后续步骤会找不到元素"),
    ], timeout=150),


    C("REPORT", "对没有数据的日期生成报表，应提示无数据而非报错", "P2", [
        S("OPEN_URL", data="${base_url}/admin/reports/daily"),
        S("INPUT", xp='//input[@data-testid="report-date"]', data="2019-01-01"),
        S("CLICK", xp='//button[@data-testid="report-generate"]'),
        S("ASSERT_TEXT", xp='//div[@data-testid="report-empty"]', expect="データがありません"),
        S("ASSERT_NOT_EXIST", xp='//div[@data-testid="server-error-page"]'),
    ]),

    C("REPORT", "起始日期晚于结束日期时，应阻止生成并提示", "P2", [
        S("OPEN_URL", data="${base_url}/admin/reports/sales"),
        S("INPUT", xp='//input[@data-testid="date-from"]', data="2025-12-31"),
        S("INPUT", xp='//input[@data-testid="date-to"]', data="2025-01-01"),
        S("CLICK", xp='//button[@data-testid="report-generate"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="date-range-error"]',
          expect="開始日は終了日より前である必要があります"),
    ]),
]

# ── M008 ADMIN ──────────────────────────────────────────────
CASES += [
    C("ADMIN", "按邮箱搜索用户，应返回匹配的用户行", "P1", [
        S("OPEN_URL", data="${base_url}/admin/users"),
        S("INPUT", xp='//input[@data-testid="user-search-keyword"]', data="${test_user_email}"),
        S("CLICK", xp='//button[@data-testid="user-search-submit"]'),
        S("ASSERT_TEXT", xp='//tr[@data-testid="user-row"][1]//span[@data-testid="user-email"]',
          expect="${test_user_email}"),
    ], precondition="使用管理员账号 ${admin_user} 登录"),

    C("ADMIN", "停用用户后，该用户应无法登录", "P1", [
        S("OPEN_URL", data="${base_url}/admin/users?q=${disable_target_user}"),
        S("CLICK", xp='//tr[@data-testid="user-row"][1]//button[@data-testid="user-disable"]'),
        S("CLICK", xp='//button[@data-testid="confirm-dialog-ok"]'),
        S("ASSERT_TEXT", xp='//tr[@data-testid="user-row"][1]//span[@data-testid="user-status"]',
          expect="無効"),
    ], precondition="使用管理员账号登录，${disable_target_user} 为专用的可停用测试账号"),

    C("ADMIN", "新建商品并上架，前台搜索应能找到", "P1", [
        S("OPEN_URL", data="${base_url}/admin/products/new"),
        S("INPUT", xp='//input[@data-testid="product-name"]', data="テスト商品_${run_id}"),
        S("INPUT", xp='//input[@data-testid="product-price"]', data="19800"),
        S("INPUT", xp='//input[@data-testid="product-stock"]', data="50"),
        S("SELECT", xp='//select[@data-testid="product-category"]', data="ノートPC"),
        S("UPLOAD", xp='//input[@data-testid="product-image-file"]',
          data="/testdata/product/laptop_main.jpg", wait="PRESENCE"),
        S("ASSERT_VISIBLE", xp='//img[@data-testid="product-image-preview"]',
          desc="确认图片上传完成再发布，否则会发布出没有主图的商品"),
        S("CLICK", xp='//button[@data-testid="product-publish"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="product-status"]', expect="公開中"),
    ], precondition="使用管理员账号登录，/testdata/product/laptop_main.jpg 已放置在共享存储"),

    C("ADMIN", "下架商品后，前台商品详情应提示已下架", "P1", [
        S("OPEN_URL", data="${base_url}/admin/products/${sample_product_id}"),
        S("CLICK", xp='//button[@data-testid="product-unpublish"]'),
        S("CLICK", xp='//button[@data-testid="confirm-dialog-ok"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="product-status"]', expect="非公開"),
        S("OPEN_URL", data="${base_url}/products/${sample_product_id}"),
        S("ASSERT_TEXT", xp='//div[@data-testid="product-unavailable"]', expect="現在お取り扱いできません"),
    ], precondition="使用管理员账号登录"),

    C("ADMIN", "在后台把订单状态改为已发货，前台应同步显示", "P1", [
        S("OPEN_URL", data="${base_url}/admin/orders/${paid_order_no}"),
        S("SELECT", xp='//select[@data-testid="admin-order-status"]', data="発送済み"),
        S("INPUT", xp='//input[@data-testid="tracking-number"]', data="TRK-${run_id}"),
        S("CLICK", xp='//button[@data-testid="admin-order-save"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="save-success-toast"]'),
        S("OPEN_URL", data="${base_url}/orders/${paid_order_no}"),
        S("ASSERT_TEXT", xp='//span[@data-testid="order-status"]', expect="発送済み"),
    ], precondition="使用管理员账号登录，${paid_order_no} 为已支付订单"),

    C("ADMIN", "给角色分配报表权限后，该角色用户应能访问报表", "P0", [
        S("OPEN_URL", data="${base_url}/admin/roles/${test_role_id}"),
        S("CLICK", xp='//input[@data-testid="perm-report-view"]'),
        S("CLICK", xp='//button[@data-testid="role-save"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="save-success-toast"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="perm-summary"]', expect="レポート閲覧"),
    ], precondition="使用管理员账号登录，${test_role_id} 为测试专用角色"),


    C("ADMIN", "修改免运费门槛，购物车计算应立即生效", "P1", [
        S("OPEN_URL", data="${base_url}/admin/settings/shipping"),
        S("INPUT", xp='//input[@data-testid="free-shipping-threshold"]', data="3000"),
        S("CLICK", xp='//button[@data-testid="settings-save"]'),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="save-success-toast"]'),
        S("ASSERT_TEXT", xp='//span[@data-testid="current-threshold"]', expect="¥3,000"),
    ], precondition="使用管理员账号登录"),


    C("ADMIN", "普通用户访问管理后台，应被拒绝并跳转提示页", "P0", [
        S("OPEN_URL", data="${base_url}/admin/users"),
        S("ASSERT_TEXT", xp='//div[@data-testid="permission-denied"]', expect="権限がありません"),
        S("ASSERT_NOT_EXIST", xp='//table[@data-testid="user-list"]'),
    ], precondition="使用普通用户 ${test_user} 登录（非管理员）"),
]

# ─────────────────────────────────────────────────────────────
#  刻意植入的脏数据（15 条）
#
#  这些是「规范建立之前留下的历史案例」，legacy=True 让创建时间落在 2023 年初。
#  它们的作用不是凑数 —— 有了它们，「借鉴存量案例」这个场景才真实：
#  助手不能无脑推荐，得能指出「这条可以参考结构，但它违反了 STD-004，别照抄」。
# ─────────────────────────────────────────────────────────────

CASES += [
    # --- 3 条 SLEEP（违反 STD-004）---
    C("LOGIN", "【历史】登录后等待首页加载完成再校验导航栏", "P2", [
        S("OPEN_URL", data="${base_url}/login"),
        S("INPUT", xp='//input[@data-testid="login-username"]', data="${test_user}"),
        S("INPUT", xp='//input[@data-testid="login-password"]', data="@cred{test_user_password}"),
        S("CLICK", xp='//button[@data-testid="login-submit"]'),
        S("SLEEP", data="3", violates="STD-004",
          desc="登录后首页渲染较慢，加固定等待。※ 规范建立前编写，待改造"),
        S("ASSERT_VISIBLE", xp='//nav[@data-testid="main-nav"]'),
    ], legacy=True),

    C("CART", "【历史】加入购物车后等待角标刷新", "P2", [
        S("OPEN_URL", data="${base_url}/products/${sample_product_id}"),
        S("CLICK", xp='//button[@data-testid="add-to-cart"]'),
        S("SLEEP", data="2", violates="STD-004",
          desc="角标是异步刷新的，加等待。※ 规范建立前编写，待改造"),
        S("ASSERT_TEXT", xp='//span[@data-testid="cart-badge-count"]', expect="1"),
    ], legacy=True),

    C("PAYMENT", "【历史】支付提交后等待网关回调再校验结果", "P1", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}/pay"),
        S("CLICK", xp='//label[@data-testid="pay-method-credit"]'),
        S("CLICK", xp='//button[@data-testid="pay-submit"]'),
        S("SLEEP", data="5", violates="STD-004",
          desc="等待沙箱网关回调。※ 规范建立前编写，应改为等待结果元素出现"),
        S("ASSERT_TEXT", xp='//div[@data-testid="pay-result"]', expect="完了"),
    ], legacy=True, timeout=120),

    # --- 4 条绝对路径 XPath（违反 STD-001）---
    C("SEARCH", "【历史】搜索结果首项标题校验", "P2", [
        S("OPEN_URL", data="${base_url}/search?q=PC"),
        S("ASSERT_TEXT", xp="/html/body/div[2]/div[1]/main/ul/li[1]/h3", expect="PC",
          violates="STD-001",
          desc="用浏览器复制的完整 XPath。※ 规范建立前编写，待改造"),
    ], legacy=True),

    C("ORDER", "【历史】订单列表首行状态校验", "P2", [
        S("OPEN_URL", data="${base_url}/orders"),
        S("ASSERT_TEXT", xp="/html/body/div[1]/div[3]/table/tbody/tr[1]/td[4]/span",
          expect="支払い", violates="STD-001",
          desc="※ 规范建立前编写，待改造"),
    ], legacy=True),

    C("USER", "【历史】个人中心昵称显示校验", "P3", [
        S("OPEN_URL", data="${base_url}/mypage"),
        S("ASSERT_VISIBLE", xp="/html/body/div[2]/section/div[1]/div/span[2]",
          violates="STD-001", desc="※ 规范建立前编写，待改造"),
    ], legacy=True),

    C("REPORT", "【历史】报表合计栏校验", "P2", [
        S("OPEN_URL", data="${base_url}/admin/reports/daily?date=2025-11-01"),
        S("CLICK", xp="/html/body/div[1]/div[2]/form/button[1]", violates="STD-001",
          desc="※ 规范建立前编写，待改造"),
        S("ASSERT_TEXT", xp='//span[@data-testid="report-total"]', expect="¥"),
    ], legacy=True, timeout=120),

    # --- 3 条动态 id（违反 STD-002）---
    C("ADMIN", "【历史】后台用户搜索框输入", "P2", [
        S("OPEN_URL", data="${base_url}/admin/users"),
        S("INPUT", xp='//*[@id="ext-gen1234"]', data="${test_user_email}", violates="STD-002",
          desc="※ 规范建立前编写。该 id 由组件库自动生成，待改造"),
        S("CLICK", xp='//button[@data-testid="user-search-submit"]'),
        S("ASSERT_VISIBLE", xp='//table[@data-testid="user-list"]'),
    ], legacy=True),

    C("ORDER", "【历史】结算页下一步按钮点击", "P2", [
        S("OPEN_URL", data="${base_url}/checkout"),
        S("CLICK", xp='//div[@id="mat-input-7"]//button', violates="STD-002",
          desc="※ 规范建立前编写，待改造"),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="order-confirm-panel"]'),
    ], legacy=True),

    C("PAYMENT", "【历史】支付方式单选按钮", "P2", [
        S("OPEN_URL", data="${base_url}/orders/${pending_order_no}/pay"),
        S("CLICK", xp='//*[@id="el-id-8237-14"]', violates="STD-002",
          desc="※ 规范建立前编写，待改造"),
        S("ASSERT_VISIBLE", xp='//input[@data-testid="card-number"]'),
    ], legacy=True),

    # --- 2 条无断言（违反 STD-008）---
    C("SEARCH", "【历史】搜索操作走通性确认", "P3", [
        S("OPEN_URL", data="${base_url}/"),
        S("INPUT", xp='//input[@data-testid="search-keyword"]', data="ノートPC"),
        S("CLICK", xp='//button[@data-testid="search-submit"]'),
    ], legacy=True, status="DRAFT"),

    C("CART", "【历史】加入购物车操作走通性确认", "P3", [
        S("OPEN_URL", data="${base_url}/products/${sample_product_id}"),
        S("CLICK", xp='//button[@data-testid="add-to-cart"]'),
    ], legacy=True, status="DRAFT"),

    # --- 3 条 case_code 违规（违反 STD-007）---
    C("USER", "【历史】收货地址列表显示确认", "P3", [
        S("OPEN_URL", data="${base_url}/mypage/addresses"),
        S("ASSERT_VISIBLE", xp='//ul[@data-testid="address-list"]'),
    ], legacy=True, code_override="ATP-USER-12"),

    C("REPORT", "【历史】报表页打开确认", "P3", [
        S("OPEN_URL", data="${base_url}/admin/reports/daily"),
        S("ASSERT_VISIBLE", xp='//form[@data-testid="report-form"]'),
    ], legacy=True, code_override="atp-report-0012"),

    C("ADMIN", "【历史】后台首页打开确认", "P3", [
        S("OPEN_URL", data="${base_url}/admin"),
        S("ASSERT_VISIBLE", xp='//div[@data-testid="admin-dashboard"]'),
    ], legacy=True, code_override="ATP-ADMIN-0011-V2"),
]


def assign_numbers(cases):
    """按模块分配 case_code 的序号。

    历史遗留案例排在前面 —— 它们创建得更早，序号本就该更小。
    这样脏数据不会集中在每个模块的末尾，检索时也就不会退化成「按编号大小就能猜出哪条违规」。
    """
    ordered = []
    for module in MODULES:
        group = [c for c in cases if c.module == module]
        group.sort(key=lambda c: (not c.legacy,))   # legacy 在前，组内保持书写顺序
        number = 0
        for case in group:
            # 命名违规的那几条是「规范建立前手工填写」的，不在平台的自动采番体系里，
            # 因此不占用序号 —— 否则会在正常案例的编号序列里留下空洞（STD-007 要求连续）。
            # 这个 bug 是 CorpusIntegrityTest 跑第一次时抓出来的。
            if case.code_override:
                continue
            number += 1
            case.seq_no = number
        ordered.extend(group)
    return ordered


def main():
    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)

    for stale in os.listdir(OUT_DIR):
        if stale.endswith(".json"):
            os.remove(os.path.join(OUT_DIR, stale))

    for index, case in enumerate(assign_numbers(CASES)):
        data = build(case, index)
        path = os.path.join(OUT_DIR, "%s.json" % data["case_code"])
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")

    print("生成 %d 条案例 → %s" % (len(CASES), OUT_DIR))


if __name__ == "__main__":
    main()
