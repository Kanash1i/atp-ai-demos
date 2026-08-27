package dev.kanashi.atp.cli.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置来源，优先级从高到低：<b>系统属性 &gt; 环境变量 &gt; 仓库根 {@code .env}</b>。
 *
 * <p>⚠️ 代码里不出现任何硬编码的 URL / 账号 / 口令 —— 一条都不许。
 * 取不到就 fail fast 并说清楚缺哪个变量，<b>不要给默认值</b>：
 * 默认值会让"配置漏了"变成"连到了错的库"，后者难查得多。
 */
public final class CliConfig {

    public static final String DB_URL = "ATP_DB_URL";
    public static final String DB_USER = "ATP_DB_USER";
    public static final String DB_PASSWORD = "ATP_DB_PASSWORD";

    private final Map<String, String> values;

    private CliConfig(Map<String, String> values) {
        this.values = values;
    }

    public static CliConfig load() {
        Map<String, String> merged = new HashMap<>(dotEnv());
        merged.putAll(System.getenv());
        // 系统属性优先级最高：java -DATP_DB_URL=... 可以临时覆盖，测试也靠它注入
        System.getProperties().forEach((k, v) -> merged.put(String.valueOf(k), String.valueOf(v)));
        return new CliConfig(merged);
    }

    public String require(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "缺少配置 " + key + "。请在仓库根目录 .env 里设置，或用环境变量传入。");
        }
        return v;
    }

    /** 口令允许为空（本地无口令的 PG 实例）。 */
    public String optional(String key) {
        String v = values.get(key);
        return v == null ? "" : v;
    }

    /** 从当前目录向上找仓库根的 .env，最多找 5 层。找不到就返回空表，不报错。 */
    private static Map<String, String> dotEnv() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path env = dir.resolve(".env");
            if (Files.isRegularFile(env)) {
                return parse(env);
            }
        }
        return Map.of();
    }

    private static Map<String, String> parse(Path file) {
        Map<String, String> out = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String v = t.substring(eq + 1).strip();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                out.put(t.substring(0, eq).strip(), v);
            }
        } catch (IOException e) {
            // .env 读不了不是致命错误 —— 环境变量可能就够了。让 require() 去报缺哪个。
            return Map.of();
        }
        return out;
    }
}
