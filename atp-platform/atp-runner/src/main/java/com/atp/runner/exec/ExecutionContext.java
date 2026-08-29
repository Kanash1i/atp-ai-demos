package com.atp.runner.exec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次执行的变量与凭据。
 *
 * <h3>两套占位符语法，故意分开</h3>
 *
 * 存量案例里同时出现这两种写法：
 * <pre>
 *   input_data: "${test_user}"              ← 普通变量：账号、URL、商品号
 *   input_data: "@cred{test_user_password}" ← 凭据：口令、token
 * </pre>
 *
 * 分开不是为了好看。凭据要满足三件普通变量不需要的事：
 * <ul>
 *   <li><b>不落在案例里</b> —— 案例会进版本库、会被 agent 读、会在 UI 上展示</li>
 *   <li><b>不进日志</b> —— {@link #describe} 把凭据渲染成 {@code ***}，
 *       执行日志与错误信息里都不该出现明文</li>
 *   <li><b>不进录像</b> —— 输入框是 {@code type=password}，浏览器自己会打码；
 *       但如果谁把口令填进普通文本框，录像就直接拍下来了。这是本设计能挡住的最后一层</li>
 * </ul>
 *
 * <p>⚠️ 未定义的占位符**当场失败**，不是原样留着。
 * 留着的话 Playwright 会去找一个叫「${sample_product_id}」的商品，
 * 报出来的是「元素找不到」—— 排查方向完全错了。
 */
public final class ExecutionContext {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    private static final Pattern CRED = Pattern.compile("@cred\\{([A-Za-z0-9_]+)}");

    /** 案例里上传文件用的虚拟前缀。真实目录由执行节点配置 */
    private static final String TESTDATA_PREFIX = "/testdata/";

    private final Map<String, String> variables;
    private final Map<String, String> credentials;
    private final String testdataRoot;

    public ExecutionContext(Map<String, String> variables, Map<String, String> credentials) {
        this(variables, credentials, null);
    }

    public ExecutionContext(Map<String, String> variables, Map<String, String> credentials, String testdataRoot) {
        this.variables = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        this.credentials = new LinkedHashMap<>(credentials == null ? Map.of() : credentials);
        this.testdataRoot = testdataRoot;
    }

    /**
     * 解析上传文件的路径。
     *
     * <p>案例里写的是 {@code /testdata/return/defect_screen.png} —— 一个**约定的虚拟路径**，
     * 不是任何一台机器上的真实路径。案例要在不同的执行节点上跑（Linux 笔记本、Windows 台式机），
     * 写死绝对路径必然只在一台机器上成立。
     *
     * <p>所以由节点把前缀映射到自己的测试数据目录。案例保持可移植，
     * 「文件放哪」是部署问题，不该泄漏进案例。
     */
    public String resolveFile(String raw) {
        String resolved = resolve(raw);
        if (testdataRoot != null && resolved != null && resolved.startsWith(TESTDATA_PREFIX)) {
            return java.nio.file.Path.of(testdataRoot,
                    resolved.substring(TESTDATA_PREFIX.length())).toString();
        }
        return resolved;
    }

    /**
     * 把页面上读到的实际值存进变量，供后续步骤引用。
     *
     * <p>存量案例用 {@code expected: "->removed_item_name"} 这种写法表示
     * 「不要断言，把当前值记下来」，后面再用 {@code ${removed_item_name}} 取。
     * 典型用法是「先记下第一行商品名 → 删除它 → 断言这个名字的行已经不在了」——
     * 案例因此不必写死具体商品，换套数据照样跑。
     *
     * <p>⚠️ 捕获只在**本条案例内**有效：每条案例新建一个 ExecutionContext，
     * 否则前一条案例记下的值会漏到后一条，产生那种「单跑能过、批量跑就挂」的鬼故事。
     */
    public void capture(String name, String value) {
        variables.put(name, value == null ? "" : value);
    }

    /** 解析成真正要用的值。给 Playwright 的就是这个 */
    public String resolve(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String out = replace(VAR, raw, variables, "变量");
        return replace(CRED, out, credentials, "凭据");
    }

    /**
     * 解析成能写进日志的值 —— 凭据一律 {@code ***}。
     *
     * <p>⚠️ 步骤日志、错误信息、SSE 推送**只能用这个**，不能用 {@link #resolve}。
     */
    public String describe(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // ⚠️ 这里用宽容版：变量没定义就把 ${xxx} 原样留着。
        //    描述是给人看的，不该因为缺一个变量就抛异常 ——
        //    真那样的话，一个「变量未定义」会被包装成「日志生成失败」，
        //    错误信息离根因隔了一层，排查方向完全被带偏。
        //    严格校验在 resolve() 那边，执行时该失败还是会失败。
        String out = replaceLenient(VAR, raw, variables);
        return CRED.matcher(out).replaceAll("***");
    }

    /** 未定义的占位符原样保留 */
    private String replaceLenient(Pattern pattern, String raw, Map<String, String> source) {
        Matcher m = pattern.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = source.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? m.group(0) : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String replace(Pattern pattern, String raw, Map<String, String> source, String kind) {
        Matcher m = pattern.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = source.get(name);
            if (value == null) {
                throw new IllegalStateException(
                        "%s %s 未定义 —— 检查执行环境配置。原文：%s".formatted(kind, name, describeSafely(raw)));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 报错信息里也不能漏凭据 */
    private String describeSafely(String raw) {
        return CRED.matcher(raw).replaceAll("***");
    }

    public Map<String, String> variables() {
        return Map.copyOf(variables);
    }
}
