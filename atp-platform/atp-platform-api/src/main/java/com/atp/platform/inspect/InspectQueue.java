package com.atp.platform.inspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 探查请求的 Redis RPC 通道 —— 平台问，执行机答。
 *
 * <h3>为什么探查必须在执行机上做</h3>
 *
 * 案例最终是在执行机资源池里跑的。探查如果在别处做，看到的 DOM 未必是执行时会看到的 DOM
 * （浏览器版本、网络路径、渲染时机都可能不同），那探出来的定位器仍然可能跑不通 ——
 * 工具就白做了。**探查环境必须与执行环境是同一个。**
 *
 * <h3>为什么是 RPC 而不是 fire-and-forget</h3>
 *
 * 执行任务是异步的（派发完就走，结果写回 PG），探查是**对话中同步等**的：
 * agent 问完要立刻拿结果继续写案例。所以这里用 请求队列 + 每请求一个响应 key 的模式。
 *
 * <p>响应 key 带 TTL —— 调用方超时走人之后，节点迟到的答复不能永远躺在 Redis 里。
 */
@Slf4j
@Component
public class InspectQueue {

    /** 待探查请求。节点上有独立线程 BRPOP 它 */
    public static final String QUEUE_KEY = "atp:inspect:pending";

    /** 响应 key 前缀，一次请求一个 key，取完即弃 */
    public static final String REPLY_PREFIX = "atp:inspect:reply:";

    /** 迟到的响应最多躺这么久。比调用方的等待上限略长即可 */
    private static final Duration REPLY_TTL = Duration.ofMinutes(2);

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private StringRedisTemplate redis;

    // ── 平台侧 ────────────────────────────────────────────────

    /** 投递请求。左进右出，先问的先答 */
    public void ask(InspectRequest req) {
        redis.opsForList().leftPush(QUEUE_KEY, write(req));
    }

    /**
     * 等一个响应。到点返回 null —— 调用方据此报「执行机没响应」，
     * 而这属于 INFRA_ERROR，不是「页面不存在」。
     */
    public InspectResponse await(String requestId, int timeoutSec) {
        String raw = redis.opsForList().rightPop(REPLY_PREFIX + requestId, timeoutSec, TimeUnit.SECONDS);
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readValue(raw, InspectResponse.class);
        } catch (Exception e) {
            log.warn("[INSPECT] 响应解析失败 {}：{}", requestId, e.getMessage());
            return null;
        }
    }

    // ── 节点侧 ────────────────────────────────────────────────

    /** 节点取请求，阻塞等待。到点返回 null，让循环有机会检查停止标志 */
    public InspectRequest take(int timeoutSec) {
        String raw = redis.opsForList().rightPop(QUEUE_KEY, timeoutSec, TimeUnit.SECONDS);
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readValue(raw, InspectRequest.class);
        } catch (Exception e) {
            log.warn("[INSPECT] 请求解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 节点回话。⚠️ 一定要设 TTL，否则没人取的响应会永远留在 Redis 里 */
    public void reply(InspectResponse resp) {
        String key = REPLY_PREFIX + resp.requestId();
        redis.opsForList().leftPush(key, write(resp));
        redis.expire(key, REPLY_TTL);
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("探查消息序列化失败", e);
        }
    }
}
