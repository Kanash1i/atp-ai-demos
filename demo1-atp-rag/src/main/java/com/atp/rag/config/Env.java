package com.atp.rag.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置读取。**唯一的配置来源是仓库根目录的 {@code .env}** —— 代码里不得出现硬编码的 key / URL / IP。
 *
 * <p>两个特性值得说明：
 * <ul>
 *   <li>支持 {@code ${VAR}} 展开。{@code .env} 里写的是
 *       {@code EMBEDDING_BASE_URL=http://${SERVICE_HOST}:8081}，
 *       shell 的 source 会自动展开，但 Java 读文件不会 —— 得自己做。</li>
 *   <li>进程环境变量优先于 {@code .env}，方便临时覆盖（例如消融实验里切 embedding 模型）。</li>
 * </ul>
 */
public final class Env {

    /** 只匹配 ${VAR} 形式；裸 $VAR 不支持，避免和 URL 里的字面 $ 混淆。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * 从工作目录向上找 {@code .env} 的最大层数。
     *
     * <p>⚠️ 这个值曾经<b>正好卡在边界上</b>：在 git worktree 里跑时，
     * {@code .claude/worktrees/demo1/demo1-atp-rag} 到仓库根恰好是 4 层，
     * 再深一层、或把 worktree 建到仓库外面，向上查找就断了。
     *
     * <p>所以建 worktree 时必须补 {@code .env} 软链 —— 「不加也能跑」是运气，不是设计。
     * 真正的兜底是 {@link #require} 里那份把搜索路径打出来的报错。
     */
    private static final int MAX_LOOKUP_DEPTH = 4;

    /** 找过的路径，只在 {@link #require} 失败时用来生成一份能指对方向的报错。 */
    private static final List<Path> SEARCHED = new ArrayList<Path>();

    private static final Path DOT_ENV = findDotEnv();

    private static final Map<String, String> VALUES = load();

    private Env() {
    }

    /**
     * 取必填配置，缺失或为空直接抛 —— 让配置错误在启动时暴露，而不是在半路变成一个诡异的 404。
     *
     * <p>报错刻意区分了两种失败：<b>找到了 .env 但没有这个 key</b>，
     * 和 <b>压根没找到 .env</b>。前者去补一行配置，后者是路径问题 ——
     * 如果两种都只说「缺少必填配置 XXX」，人会去翻 .env，确认那一行明明就在，然后卡住。
     *
     * <p>不在加载时就抛，是因为 CI / 容器里没有 .env 是<b>正常的</b>（配置从环境变量注入）。
     * 缺失本身不是错误，缺失导致取不到值才是 —— 所以把判断推迟到真正要用的这一刻。
     */
    public static String require(String key) {
        String value = get(key, null);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        StringBuilder message = new StringBuilder("缺少必填配置 ").append(key).append("。");
        if (DOT_ENV != null) {
            message.append("已加载 ").append(DOT_ENV)
                    .append("，但其中没有该项（也没有同名环境变量）。")
                    .append("对照 .env.example 补上这一行。");
        } else {
            message.append("且**没有找到任何 .env 文件** —— 这多半是路径问题，不是配置漏写。")
                    .append("从 ").append(Paths.get("").toAbsolutePath())
                    .append(" 向上找过：").append(SEARCHED)
                    .append("。若在 git worktree 里跑，补一条软链：")
                    .append("ln -s <仓库根>/.env <worktree 根>/.env");
        }
        throw new IllegalStateException(message.toString());
    }

    public static String get(String key, String defaultValue) {
        String fromProcess = System.getenv(key);
        if (fromProcess != null && !fromProcess.isEmpty()) {
            return fromProcess;
        }
        String fromFile = VALUES.get(key);
        return (fromFile == null || fromFile.isEmpty()) ? defaultValue : fromFile;
    }

    public static int getInt(String key, int defaultValue) {
        String raw = get(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " 必须是整数，实际值：" + raw, e);
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String raw = get(key, null);
        return raw == null ? defaultValue : Boolean.parseBoolean(raw.trim());
    }

    /**
     * 定位到的 {@code .env} 路径，供启动日志打印用（只打印路径，不打印内容）。
     * 没找到时返回 {@code null} —— 调用方应把这个情况显式打出来，别默默当成空配置。
     */
    public static Path dotEnvPath() {
        return DOT_ENV;
    }

    private static Map<String, String> load() {
        Path path = DOT_ENV;
        if (path == null) {
            return Collections.emptyMap();
        }
        Map<String, String> raw = new LinkedHashMap<String, String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, raw);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + path + " 失败", e);
        }
        return expandAll(raw);
    }

    private static void parseLine(String line, Map<String, String> into) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        // 兼容 shell 里常见的 `export KEY=VALUE` 写法
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.substring("export ".length()).trim();
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = trimmed.substring(0, eq).trim();
        String value = stripQuotes(trimmed.substring(eq + 1).trim());
        into.put(key, value);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 展开 {@code ${VAR}}。迭代若干轮以支持间接引用（QDRANT_HOST=${SERVICE_HOST}），
     * 轮数上限同时兜住了循环引用 —— 解不开的占位符原样留着，
     * 后续 {@link #require} 拿到一个明显不像 URL 的值时会更容易被发现。
     */
    private static Map<String, String> expandAll(Map<String, String> raw) {
        Map<String, String> resolved = new LinkedHashMap<String, String>(raw);
        for (int round = 0; round < 5; round++) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                String expanded = expandOnce(entry.getValue(), resolved);
                if (!expanded.equals(entry.getValue())) {
                    entry.setValue(expanded);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static String expandOnce(String value, Map<String, String> scope) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getenv(name);
            if (replacement == null || replacement.isEmpty()) {
                replacement = scope.get(name);
            }
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 从工作目录逐级向上找 {@code .env}，这样在 demo1 目录和仓库根目录下都能直接跑。
     *
     * <p>沿途记下找过的路径 —— 找不到时这份清单是唯一能把人引到「路径不对」而不是
     * 「配置漏写」的线索。见 {@link #require}。
     */
    private static Path findDotEnv() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth <= MAX_LOOKUP_DEPTH && dir != null; depth++) {
            Path candidate = dir.resolve(".env");
            SEARCHED.add(candidate);
            // isRegularFile 跟随软链，所以 worktree 里的 .env 软链能正常命中
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
