package com.atp.rag.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 判断服务机是否可用。
 *
 * <p>刻意<b>不</b>依赖 Spring 的配置加载 —— 它要在上下文启动之前就给出答案，
 * 那时 {@code AtpProperties} 还不存在。所以这里自己读一遍 {@code .env}，
 * 只取判断连通性需要的三个键。
 *
 * <p>这是唯一一处还在手工解析 {@code .env} 的地方，属于「测试基础设施必须先于容器」
 * 这个约束下的必要重复，不是漏改。
 */
public class ServicesAvailableCondition implements ExecutionCondition {

    /** 只查一次，同一次测试运行里复用结果，避免每个测试类都探一遍网络。 */
    private static volatile ConditionEvaluationResult cached;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (cached == null) {
            synchronized (ServicesAvailableCondition.class) {
                if (cached == null) {
                    cached = evaluate();
                }
            }
        }
        return cached;
    }

    private ConditionEvaluationResult evaluate() {
        Map<String, String> env = loadDotEnv();
        String host = env.get("SERVICE_HOST");
        if (host == null || host.isEmpty()) {
            return ConditionEvaluationResult.disabled(
                    "找不到 .env 或其中没有 SERVICE_HOST，跳过需要服务机的测试");
        }

        String embedding = value(env, "EMBEDDING_BASE_URL", "http://" + host + ":8081");
        String rerank = value(env, "RERANK_BASE_URL", "http://" + host + ":8082");
        String qdrantPort = value(env, "QDRANT_PORT", "6333");

        if (!reachable(embedding + "/health")) {
            return ConditionEvaluationResult.disabled("TEI embedding 不可用，跳过");
        }
        if (!reachable(rerank + "/health")) {
            return ConditionEvaluationResult.disabled("TEI rerank 不可用，跳过");
        }
        if (!reachable("http://" + host + ":" + qdrantPort + "/")) {
            return ConditionEvaluationResult.disabled("Qdrant 不可用，跳过");
        }
        return ConditionEvaluationResult.enabled("服务机可用");
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String raw = env.get(key);
        return raw == null || raw.isEmpty() ? fallback : raw;
    }

    /** 读 {@code .env} 并展开 {@code ${VAR}}（只需支持一层，够用）。 */
    private static Map<String, String> loadDotEnv() {
        Map<String, String> values = new HashMap<String, String>();
        Path path = findDotEnv();
        if (path == null) {
            return values;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            return values;
        }
        // 展开 ${VAR}
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String v = entry.getValue();
            for (Map.Entry<String, String> other : values.entrySet()) {
                v = v.replace("${" + other.getKey() + "}", other.getValue());
            }
            entry.setValue(v);
        }
        return values;
    }

    private static Path findDotEnv() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth <= 4 && dir != null; depth++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static boolean reachable(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
