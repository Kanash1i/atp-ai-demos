package com.atp.platform.service;

import com.atp.platform.inspect.InspectQueue;
import com.atp.platform.inspect.InspectRequest;
import com.atp.platform.inspect.InspectResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 页面探查 —— 平台问，执行机答。
 *
 * <p>这是为了消灭一类真实发生过两次的错误：agent 把商品详情页 URL 编成
 * {@code /product/p001}，而真实路由是 {@code /products/{id}}。
 * 规范校验全绿，一跑就挂。
 *
 * <blockquote>
 * <b>agent 编造，通常是因为它没有查询的工具，而不是因为它不老实。</b>
 * 加约束（"不要编造 URL"）只会让它换个方式绕；加工具才是真的把路堵上。
 * </blockquote>
 */
@Slf4j
@Service
// ⚠️ 只在配了 base-url 的进程里创建 —— 执行节点也依赖 atp-platform-api，
//    会把这个包下的 @Service 一并扫到，而节点是**答方**不是问方，不该持有这个 bean。
@ConditionalOnProperty("atp.inspect.base-url")
public class PageInspectService {

    /** 等执行机答复的上限。含冷启动浏览器的一两秒 */
    private static final int AWAIT_TIMEOUT_SEC = 30;

    @Autowired
    private InspectQueue queue;

    /**
     * 被测系统入口。与执行器的 {@code atp.runner.variables.base_url} 是同一个值。
     *
     * <h3>⭐ 这是「执行机视角」的地址，不是平台视角的</h3>
     *
     * 平台只负责把它拼进请求，真正去访问的是执行机。按隔离拓扑
     * （{@code 00-SHARED-CONTEXT.md} §2.0）**平台自己连不到被测系统** ——
     * 所以这里配一个平台访问不了的地址是正常的；
     * 配一个平台能访问、执行机访问不了的地址才是错的。
     *
     * <p>⚠️ 实测踩过：容器化部署时给了 {@code host.docker.internal}（容器能解析），
     * 而节点跑在宿主机上解析不了，最终由 Playwright 报出
     * {@code Cannot navigate to invalid URL} —— 错误出现在三跳之外的另一台机器上。
     */
    @Value("${atp.inspect.base-url}")
    private String baseUrl;

    /**
     * 启动时就确认 base-url 是个绝对地址。
     *
     * <h3>不校验的后果（实测撞过）</h3>
     *
     * 容器里 {@code MOCK_SHOP_URL} 是**空字符串**而不是未设置 —— yml 里
     * {@code ${MOCK_SHOP_URL:http://localhost:8088}} 的默认值因此不生效，
     * baseUrl 成了空串。于是 {@link #resolve} 拼出 {@code /products/p001}，
     * 一路传到执行机，最后由 Playwright 报出来：
     *
     * <pre>Cannot navigate to invalid URL — navigating to "/products/p001"</pre>
     *
     * 错误发生在三跳之外的另一台机器上，而根因是这里的一个空配置。
     * **配置缺失应该在启动时炸，不该让它静默产出非法输入。**
     */
    @jakarta.annotation.PostConstruct
    void validateBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()
                || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalStateException(
                    "atp.inspect.base-url 必须是绝对地址（http:// 或 https:// 开头），当前是 ["
                            + baseUrl + "]。检查环境变量 MOCK_SHOP_URL —— "
                            + "⚠️ 设成空字符串和不设置是两回事，空字符串会让 yml 里的默认值失效");
        }
        log.info("[INSPECT] 被测系统入口 {}", baseUrl);
    }

    /**
     * @param pathOrUrl 可以是 {@code /products/p001} 这样的路径，
     *                  也可以是含 {@code ${base_url}} 的写法 —— 案例里就是那么写的，
     *                  agent 多半会直接把步骤里的值贴过来
     */
    public InspectResponse inspect(String pathOrUrl) {
        String url = resolve(pathOrUrl);
        String requestId = UUID.randomUUID().toString();

        queue.ask(new InspectRequest(requestId, url, 15000));
        InspectResponse resp = queue.await(requestId, AWAIT_TIMEOUT_SEC);

        if (resp == null) {
            // ⚠️ 这是 INFRA 而不是 NOT_FOUND：没有任何一台执行机应答，
            //    与「页面不存在」是完全不同的两件事，agent 的下一步动作也不同
            log.warn("[INSPECT] {} 无执行机应答", url);
            return InspectResponse.infra(requestId, url,
                    "没有执行机在 " + AWAIT_TIMEOUT_SEC + " 秒内应答 —— 探查能力当前不可用");
        }
        return resp;
    }

    /** 变量在平台侧展开，节点不认识 {@code ${base_url}} —— 一个值只有一个来源 */
    private String resolve(String pathOrUrl) {
        String s = pathOrUrl == null ? "" : pathOrUrl.trim();
        s = s.replace("${base_url}", baseUrl);
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        return baseUrl + (s.startsWith("/") ? s : "/" + s);
    }
}
