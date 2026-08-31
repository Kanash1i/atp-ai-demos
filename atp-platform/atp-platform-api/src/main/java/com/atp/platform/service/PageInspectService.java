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

    /** 被测系统入口。与执行器的 {@code atp.runner.variables.base_url} 是同一个值 */
    @Value("${atp.inspect.base-url}")
    private String baseUrl;

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
