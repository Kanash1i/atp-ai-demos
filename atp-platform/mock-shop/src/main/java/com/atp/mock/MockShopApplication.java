package com.atp.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 被测的 mock 电商站。
 *
 * <h3>为什么不是一堆静态 HTML</h3>
 *
 * 存量案例里有 {@code /orders/${pending_order_no}} 这类**路径参数**，
 * 静态目录服务器直接 404。所以要一层路由把它们落到对应页面上。
 *
 * <h3>为什么和执行节点同机</h3>
 *
 * 浏览器访问被测页面如果要绕 Tailscale 回云端，每个 OPEN_URL 都多几十毫秒，
 * 而且家里网络一抖执行就失败 —— 那会让「执行失败」变成噪音，掩盖真正的用例失败。
 *
 * <p>⚠️ 页面的 DOM 结构不是随便写的：它必须让 88 个存量定位器全部命中，
 * 包括那 4 条 {@code /html/body/...} 绝对路径和 3 条框架动态 id。
 * 改动任何一层 div 的嵌套都可能让绝对路径指向别的元素 —— 那正是 STD-001 想说明的事。
 */
@SpringBootApplication
@Controller
public class MockShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockShopApplication.class, args);
    }

    // ── 路由：把路径映射到静态页面 ────────────────────────────
    // forward 而不是 redirect：URL 要保持案例里写的那个，
    // 否则 Playwright 断言当前地址时会对不上。

    @GetMapping({"/", "/home"})
    public String home() {
        return "forward:/pages/home.html";
    }

    @GetMapping("/login")
    public String login() {
        return "forward:/pages/login.html";
    }

    @GetMapping("/mypage")
    public String mypage() {
        return "forward:/pages/mypage.html";
    }

    /** 重置密码带 ?token=xxx，token 内容由页面 JS 读，路由不管 */
    @GetMapping("/password/reset")
    public String passwordReset() {
        return "forward:/pages/password-reset.html";
    }

    /** SSO 门户。ATP-LOGIN-0010 从这里跳进来 */
    @GetMapping({"/sso", "/sso/apps/{app}"})
    public String sso() {
        return "forward:/pages/sso.html";
    }

    /**
     * SSO 免密入口：直接建立会话再进首页。
     *
     * <p>⚠️ 用 302 而不是 forward —— 案例第 3 步要 SWITCH_WINDOW 到标题含「ATP Shop」的窗口，
     * 新窗口得真的落在首页上，标题才对得上。
     */
    @GetMapping("/sso/launch")
    public String ssoLaunch() {
        return "forward:/pages/sso-launch.html";
    }

    @GetMapping("/cart")
    public String cart() {
        return "forward:/pages/cart.html";
    }

    /** 商品详情：/products/{id} */
    @GetMapping("/products/{id}")
    public String product() {
        return "forward:/pages/product.html";
    }

    @GetMapping("/checkout")
    public String checkout() {
        return "forward:/pages/checkout.html";
    }

    @GetMapping("/orders")
    public String orders() {
        return "forward:/pages/orders.html";
    }

    /** 订单详情：/orders/{orderNo} */
    @GetMapping("/orders/{orderNo}")
    public String orderDetail() {
        return "forward:/pages/order-detail.html";
    }
}
