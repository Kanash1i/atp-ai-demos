package com.atp.runner.node;

import com.atp.platform.inspect.InspectQueue;
import com.atp.platform.inspect.InspectRequest;
import com.atp.platform.inspect.InspectResponse;
import com.atp.platform.inspect.LocatorCandidate;
import com.atp.runner.RunnerProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 页面探查消费者 —— 让 agent 能真的看一眼被测系统，而不是猜。
 *
 * <h3>为什么单独一个线程，不挂在 TaskConsumer 的循环里</h3>
 *
 * 执行一条案例要几十秒，而探查是**对话中同步等**的。挂在同一个循环里，
 * agent 问一句要等前面那条案例跑完 —— 对话就断了。
 *
 * <p>⚠️ Playwright 必须在同一个线程里创建和使用（{@link TaskConsumer} 已经踩过），
 * 所以这里是另一个 Playwright 实例，不是共用 TaskConsumer 那个 Browser。
 *
 * <h3>浏览器懒启动 + 空闲关闭</h3>
 *
 * 探查不是高频操作，让一个 chromium 常驻纯属浪费（节点机器上还跑着执行用的那个）。
 * 第一次请求时启动，空闲超过 {@link #IDLE_SHUTDOWN_MS} 就关掉，下次再起。
 * 代价是冷启动多一两秒 —— 在一次对话里可以接受。
 */
@Slf4j
@Component
public class InspectConsumer implements ApplicationRunner {

    /** 空闲多久关掉浏览器 */
    private static final long IDLE_SHUTDOWN_MS = 5 * 60 * 1000L;

    /** BRPOP 阻塞上限。到点返回让循环有机会检查空闲与停止标志 */
    private static final int POLL_TIMEOUT_SEC = 5;

    @Autowired
    private InspectQueue queue;

    @Autowired
    private RunnerProperties props;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atp-inspect-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;

    @Override
    public void run(ApplicationArguments args) {
        worker.submit(this::loop);
    }

    /**
     * ⚠️ 没有这个的话，关停时循环会永远转下去 ——
     * Redis 连接工厂已经销毁，每次 take 都抛异常、被 catch、continue，
     * 一秒钟能刷出上千行 "LettuceConnectionFactory was destroyed"。
     * 实测撞到过：日志里全是这一行，真正的关停信息被冲没了。
     */
    @PreDestroy
    void stop() {
        running = false;
        worker.shutdownNow();
    }

    private void loop() {
        Playwright playwright = null;
        Browser browser = null;
        long lastUsed = 0;

        while (running) {
            try {
                InspectRequest req = queue.take(POLL_TIMEOUT_SEC);

                if (req == null) {
                    // 空闲：把浏览器还回去，别让它白占内存
                    if (browser != null && System.currentTimeMillis() - lastUsed > IDLE_SHUTDOWN_MS) {
                        log.info("[INSPECT] 空闲 {} 分钟，关闭浏览器", IDLE_SHUTDOWN_MS / 60000);
                        browser.close();
                        playwright.close();
                        browser = null;
                        playwright = null;
                    }
                    continue;
                }

                if (browser == null) {
                    playwright = Playwright.create();
                    browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                            .setChannel(props.getBrowserChannel())
                            .setHeadless(true));  // 探查不录像，headless 就够
                }
                lastUsed = System.currentTimeMillis();
                queue.reply(inspect(browser, req));

            } catch (Exception e) {
                // 关停途中的异常不值得记 —— 连接工厂已销毁，这是意料之中的
                if (!running || Thread.currentThread().isInterrupted()) {
                    break;
                }
                // ⚠️ 除此之外，循环绝不能因为一次失败而退出 —— 那会让节点静默失去探查能力，
                //    而心跳一切正常，从外面完全看不出来
                log.warn("[INSPECT] 本轮异常：{}", e.getMessage());
                if (browser != null) {
                    try {
                        browser.close();
                        playwright.close();
                    } catch (Exception ignored) {
                        // 已经坏了，下一轮重开
                    }
                    browser = null;
                    playwright = null;
                }
            }
        }
    }

    private InspectResponse inspect(Browser browser, InspectRequest req) {
        Page page = null;
        try {
            page = browser.newPage();
            page.setDefaultTimeout(req.timeoutMs());
            Response resp = page.navigate(req.url());
            int status = resp == null ? 0 : resp.status();

            // 404 是「你查错了」，不是「环境坏了」—— 必须让 agent 能分辨
            if (status >= 400) {
                return InspectResponse.notFound(req.requestId(), status, req.url());
            }

            // mock-shop 是前端渲染，DOM 要等 JS 跑完才有东西
            page.waitForLoadState(LoadState.NETWORKIDLE);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) page.evaluate(EXTRACT_JS);
            return InspectResponse.ok(req.requestId(), status, page.url(), page.title(), toCandidates(raw));

        } catch (Exception e) {
            log.warn("[INSPECT] {} 失败：{}", req.url(), e.getMessage());
            return InspectResponse.infra(req.requestId(), req.url(), e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Exception ignored) {
                    // 页面关不上不影响结果，浏览器还能用
                }
            }
        }
    }

    private List<LocatorCandidate> toCandidates(List<Map<String, Object>> raw) {
        List<LocatorCandidate> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            out.add(new LocatorCandidate(
                    str(m.get("kind")), str(m.get("locatorType")), str(m.get("locatorValue")),
                    str(m.get("text")), str(m.get("note"))));
        }
        return out;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 在页面里抓候选定位器。
     *
     * <p>⚠️ 产出的写法必须是**规范允许**的，否则 agent 照抄之后被 STD 校验挡下，
     * 工具反而添乱：优先 data-testid（STD-003 首选），其次 name / 稳定的 id，
     * 绝不产出绝对路径 XPath（STD-001 是 ERROR 档），带随机后缀的 id 直接跳过（STD-002）。
     *
     * <p>只取可见元素 —— 页面里藏着的模板节点对写案例没有意义，反而会让模型挑错。
     */
    private static final String EXTRACT_JS = """
            () => {
              const out = [];
              const seen = new Set();
              const DYNAMIC = /[0-9a-f]{8,}|\\d{6,}|^(ember|react|vue|ng)[-_]?\\d+/i;

              const visible = el => {
                const r = el.getBoundingClientRect();
                if (r.width === 0 || r.height === 0) return false;
                const s = getComputedStyle(el);
                return s.visibility !== 'hidden' && s.display !== 'none';
              };
              const text = el => (el.innerText || el.value || el.placeholder || '')
                .trim().replace(/\\s+/g, ' ').slice(0, 60);

              const add = (el, kind, note) => {
                if (!visible(el)) return;
                const tag = el.tagName.toLowerCase();
                const tid = el.getAttribute('data-testid');
                const name = el.getAttribute('name');
                const id = el.id;
                let type, value;

                if (tid) {                                  // STD-003 首选
                  type = 'XPATH'; value = `//${tag}[@data-testid='${tid}']`;
                } else if (name) {
                  type = 'NAME'; value = name;
                } else if (id && !DYNAMIC.test(id)) {        // STD-002：动态 id 不要
                  type = 'ID'; value = id;
                } else if (tag === 'a' && text(el)) {
                  type = 'LINK_TEXT'; value = text(el);
                } else if (el.className && typeof el.className === 'string') {
                  const cls = el.className.trim().split(/\\s+/).filter(c => !DYNAMIC.test(c))[0];
                  if (!cls) return;
                  type = 'CSS'; value = `${tag}.${cls}`;     // 相对选择器，不是绝对路径
                } else {
                  return;
                }

                const key = type + '|' + value;
                if (seen.has(key)) return;
                seen.add(key);
                out.push({ kind, locatorType: type, locatorValue: value, text: text(el), note: note || '' });
              };

              document.querySelectorAll('[data-testid]').forEach(el => add(el, 'testid'));
              document.querySelectorAll('button, input[type=submit], input[type=button]')
                .forEach(el => add(el, 'button', el.disabled ? 'disabled' : ''));
              document.querySelectorAll('a[href]').forEach(el => add(el, 'link', el.getAttribute('href')));
              document.querySelectorAll('input:not([type=submit]):not([type=button]), select, textarea')
                .forEach(el => add(el, 'input', el.getAttribute('type') || el.tagName.toLowerCase()));
              document.querySelectorAll('h1, h2, h3').forEach(el => add(el, 'heading'));

              return out.slice(0, 60);   // 够模型挑了，再多是噪音
            }
            """;
}
