package com.atp.rag.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 在最早期确认配置真的读到了。
 *
 * <h3>为什么需要它</h3>
 *
 * {@code spring.config.import} 用的是 {@code optional:} 前缀，这是必要的 ——
 * CI 和容器里没有 {@code .env}，配置靠环境变量注入，不加 {@code optional:} 会直接启动失败。
 *
 * <p><b>代价是配置源缺失时零提示。</b> 而后果比「没有提示」严重得多：
 * 未解析的占位符会被当成字面值一路传下去，最后在某个完全无关的地方炸掉。
 * 实测缺 {@code .env} 时的报错是
 *
 * <pre>java.lang.IllegalArgumentException: Expected URL scheme 'http' or 'https' but no colon was found</pre>
 *
 * 这是 okhttp 在构造 embedding 客户端时抛的，<b>完全指不到「配置没读到」这个根因</b>。
 * 人会去查 URL 拼接、查 TEI 服务、查 okhttp 版本，唯独想不到是 {@code .env} 没找到。
 *
 * <p>这个类把它变回显式失败 —— 项目里早先手写的配置加载器就做过同样的加固，
 * 换成框架原生能力之后那个能力丢了，这里补回来。
 *
 * <h3>检查的是「值拿不到」，不是「文件不存在」</h3>
 *
 * 刻意如此：CI / 容器里没有 {@code .env} 是<b>正常状态</b>，只要环境变量提供了值就该放行。
 * 所以判据是「必填项最终能否解析出值」，与它来自文件还是环境变量无关。
 */
public class ConfigSourceCheck implements EnvironmentPostProcessor, Ordered {

    /** 任何任务都要用到的，缺了必定跑不起来。 */
    private static final List<String> ALWAYS_REQUIRED = Arrays.asList(
            "EMBEDDING_BASE_URL", "QDRANT_HOST");

    /**
     * 判据是<b>「值最终拿不到，<i>并且</i>这个值是必需的」</b>。
     *
     * <p>后半句不能省。只判「值拿不到」会把<b>本来就可选</b>的配置项也报成错 ——
     * {@code RERANK_BASE_URL} 就是例子：{@code atp.rerank.enabled=false} 时根本不需要它，
     * 而<b>消融表第 1~3 行全是 rerank 关闭的</b>。无条件要求它，
     * 等于让 baseline 配置在没有该变量的环境里跑不起来。
     *
     * <p>这个精确化来自 demo2 的提醒 —— 它那边有一批带合理默认值的配置项
     * （端口、protocol），缺失时静默回落是<b>特性</b>而不是 bug。
     * 判据要能区分「静默回落」和「静默失败」。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        List<String> required = new ArrayList<String>(ALWAYS_REQUIRED);
        // rerank 开着才需要它的地址。这里读的是 Spring 的属性（命令行 / yml / 环境变量都算），
        // 所以 --atp.rerank.enabled=false 能正确地把这一项排除掉
        if (!"false".equalsIgnoreCase(environment.getProperty("atp.rerank.enabled", "true"))) {
            required.add("RERANK_BASE_URL");
        }

        List<String> missing = new ArrayList<String>();
        for (String key : required) {
            String value = environment.getProperty(key);
            // 未解析的占位符会以字面形式留下来（"${SERVICE_HOST}"），那和缺失是一样的后果
            if (value == null || value.trim().isEmpty() || value.contains("${")) {
                missing.add(key + (value == null ? "" : "=" + value));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        throw new IllegalStateException(buildMessage(missing));
    }

    private String buildMessage(List<String> missing) {
        Path expected = dotEnvPath();
        boolean exists = expected != null && Files.isRegularFile(expected);

        StringBuilder sb = new StringBuilder("\n\n配置读不到，缺少：").append(missing).append("\n");
        if (exists) {
            sb.append("\n已找到 ").append(expected)
                    .append("，但其中没有上述项（或它们的值里还有没展开的 ${...}）。")
                    .append("\n对照 .env.example 补齐。");
        } else {
            sb.append("\n**没有找到 .env 文件** —— 这多半是路径问题，不是配置漏写。")
                    .append("\n  期望位置：").append(expected == null ? "(算不出来)" : expected)
                    .append("\n  当前工作目录：").append(Paths.get("").toAbsolutePath())
                    .append("\n\n处理方式：")
                    .append("\n  · 从模块根目录（demo1-atp-rag/）启动，.env 在仓库根目录")
                    .append("\n  · 在 git worktree 里跑，要补软链：ln -s <仓库根>/.env <worktree 根>/.env")
                    .append("\n  · 换位置的话设 ATP_DOTENV_DIR，例如 -DATP_DOTENV_DIR=/path/to/dir/")
                    .append("\n  · CI / 容器里没有 .env 是正常的，用环境变量注入这几个键即可");
        }
        return sb.append('\n').toString();
    }

    /** 按 application.yml 里 {@code spring.config.import} 的规则算出 .env 的期望位置。 */
    private Path dotEnvPath() {
        String dir = System.getProperty("ATP_DOTENV_DIR",
                System.getenv("ATP_DOTENV_DIR") == null ? "../" : System.getenv("ATP_DOTENV_DIR"));
        try {
            return Paths.get(dir).resolve(".env").toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        // 在配置文件都导入完之后才跑，否则会误判成缺失
        return Ordered.LOWEST_PRECEDENCE;
    }
}
