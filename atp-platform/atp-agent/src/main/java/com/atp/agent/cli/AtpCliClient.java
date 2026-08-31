package com.atp.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code atp} CLI 的进程封装 —— 平台 agent 的写入出口。
 *
 * <h3>为什么 agent 不直接调 CaseWriteService</h3>
 *
 * 本仓库有两条 AI 赋能路线：客户机器上的 opencode，和平台内的 agent。
 * 两者若各写一份落库实现，格式迟早会漂 —— 不是因为谁写错了，
 * 而是因为**两份实现会各自演化**。让它们 exec 同一个二进制，
 * 漂移就不是「靠纪律避免」而是「物理上不可能」。
 *
 * <p>代价是每次写操作 fork 一个进程。在这个场景下无所谓：
 * agent 写案例是人在对话里驱动的，QPS 以分钟计。
 *
 * <h3>凭据在哪一侧</h3>
 *
 * CLI 读 {@code ATP_DB_URL / ATP_DB_USER / ATP_DB_PASSWORD}，
 * 与平台自己用的是同一组环境变量名，所以子进程**直接继承**即可。
 * 数据库凭据始终在平台侧，不因为「走了 CLI」而落到别处。
 *
 * <p>（opencode 那条路线上的 CLI 是另一回事 —— 那时凭据在客户机器上，
 * 这正是两条路线在安全模型上的真实差别，不要抹平它。）
 */
@Slf4j
@Component
public class AtpCliClient {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${atp.cli.bin}")
    private String bin;

    @Value("${atp.cli.timeout-seconds:30}")
    private int timeoutSeconds;

    /**
     * 启动时确认二进制在位并打印版本。
     *
     * <p>不 fail-fast：平台的传统功能（前端那条链路）不依赖 CLI，
     * 少一个二进制不该让整个平台起不来。但 agent 的写工具会立刻报错，
     * 所以这里的 warn 必须说清后果。
     */
    @PostConstruct
    void probe() {
        try {
            CliResult r = run("--version");
            if (r.success()) {
                log.info("[CLI] {} → {}", bin, r.stdout().trim());
                return;
            }
            log.warn("[CLI] {} 探测返回非零：{}", bin, r.stderr().trim());
        } catch (Exception e) {
            log.warn("[CLI] 二进制不可用（{}）：{} —— agent 将无法写案例，"
                    + "请构建 demo2-atp-cli 并用 atp.cli.bin 指向它", bin, e.getMessage());
        }
    }

    /**
     * 跑一条 atp 命令。{@code --json} 由这里统一追加，调用方不用管。
     *
     * @param args 子命令与参数，**不经过 shell**，所以参数里有空格、引号都不需要转义
     */
    public CliResult run(String... args) {
        return runWithin(timeoutSeconds, args);
    }

    /**
     * 指定这一次的等待上限。
     *
     * <p>⚠️ 存在的理由是**两层超时必须有正确的层级关系**：CLI 自己也在等平台
     * （比如 {@code atp run} 要等执行机出结果，它的 HTTP 超时是 timeoutSec+30s）。
     * 我这边的进程超时若比它短，就会在 CLI 还没拿到结论时把它强杀 ——
     * agent 收到的是「没拿到结论」，而实际上再等十几秒结论就有了。
     *
     * <p>实测撞过：默认 30 秒 exec 超时把 {@code atp run} 杀在半路，
     * agent 如实报告「状态未知」——行为是对的，但那个未知是我造成的。
     */
    public CliResult runWithin(int seconds, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        // --version / --help 这类不吃 --json，多带一个反而报错
        boolean wantsJson = args.length > 0 && !args[0].startsWith("-");
        if (wantsJson) {
            // ⚠️ --json 紧跟子命令，不能放在参数列表末尾：
            //    cobra 是顺序解析的，末尾的话前面只要有一个非法 flag，
            //    它在读到 --json 之前就已经失败并按纯文本报错了 —— 那正是最需要结构化输出的时候。
            cmd.add(args[0]);
            cmd.add("--json");
            cmd.addAll(List.of(args).subList(1, args.length));
        } else {
            cmd.addAll(List.of(args));
        }

        long t0 = System.currentTimeMillis();
        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 CLI：" + bin + " —— " + e.getMessage(), e);
        }

        // ⚠️ 必须在 waitFor 之前并发把两个流读干净。
        //    只 waitFor 不读流的话，输出稍大就会把管道缓冲区填满，子进程写阻塞、
        //    父进程等退出 —— 互相等成死锁，且只在输出变长后才出现。
        CompletableFuture<String> out = readAsync(p.getInputStream());
        CompletableFuture<String> err = readAsync(p.getErrorStream());

        int exit;
        try {
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(
                        "CLI 执行超过 %d 秒未返回：%s".formatted(seconds, String.join(" ", args)));
            }
            exit = p.exitValue();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CLI 执行被中断", e);
        }

        String stdout = out.join();
        String stderr = err.join();
        log.debug("[CLI] {} exit={} {}ms", String.join(" ", args), exit, System.currentTimeMillis() - t0);
        return parse(exit, stdout, stderr);
    }

    /**
     * 需要 {@code -f 文件} 的命令（update / validate）：把 JSON 落成临时文件再调。
     *
     * <p>用文件而不是 stdin，是因为 CLI 的契约就是 {@code -f} ——
     * 为了少写一个临时文件去改 CLI 的接口，不划算。
     */
    public CliResult runWithJsonFile(String json, String... argsWithFilePlaceholder) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("atp-draft-", ".json");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            String[] args = new String[argsWithFilePlaceholder.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = "{{FILE}}".equals(argsWithFilePlaceholder[i])
                        ? tmp.toString() : argsWithFilePlaceholder[i];
            }
            return run(args);
        } catch (IOException e) {
            throw new IllegalStateException("写临时草稿文件失败：" + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 删不掉就留着，系统临时目录会清 —— 不值得为此让工具调用失败
                }
            }
        }
    }

    private CompletableFuture<String> readAsync(InputStream in) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (in) {
                f.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                f.complete("");
            }
        });
        return f;
    }

    /**
     * 解析 JSON 信封。
     *
     * <p>⚠️ 不是所有失败都有信封：参数校验（cobra 层）失败时 CLI 输出的是
     * {@code [VALIDATION_FAILED] required flag(s) "platform" not set} 这样的纯文本。
     * 那种情况下退出码仍然有效，所以退化成「退出码 + 原始文本」，不要抛异常 ——
     * 把原文交给模型，它能据此改参数。
     */
    private CliResult parse(int exit, String stdout, String stderr) {
        JsonNode env = null;
        String trimmed = stdout.trim();
        if (trimmed.startsWith("{")) {
            try {
                env = mapper.readTree(trimmed);
            } catch (Exception e) {
                log.warn("[CLI] stdout 不是合法 JSON：{}", abbreviate(trimmed));
            }
        }

        if (env == null) {
            return new CliResult(exit, codeOf(exit), exit == 0, false, null,
                    List.of(), List.of(), "", stdout, stderr);
        }
        return new CliResult(
                exit,
                env.path("code").asText(codeOf(exit)),
                env.path("ok").asBoolean(exit == 0),
                env.path("replayed").asBoolean(false),
                env.get("data"),
                texts(env.get("violations")),
                texts(env.get("questions")),
                env.path("message").asText(""),
                stdout,
                stderr);
    }

    /** 退出码 → 符号名。与 CLI 的 model.ExitCode 一一对应，改动需两侧同步 */
    private String codeOf(int exit) {
        return switch (exit) {
            case 0 -> "OK";
            case 10 -> "VERSION_CONFLICT";
            case 11 -> "NOT_FOUND";
            case 12 -> "VALIDATION_FAILED";
            case 13 -> "STATE_CONFLICT";
            case 14 -> "NEEDS_INPUT";
            case 20 -> "INFRA_ERROR";
            default -> "UNKNOWN";
        };
    }

    private List<String> texts(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(arr.size());
        arr.forEach(n -> out.add(n.isTextual() ? n.asText() : n.toString()));
        return out;
    }

    private String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
