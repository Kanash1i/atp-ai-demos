package com.atp.agent.tools;

import com.atp.agent.cli.AtpCliClient;
import com.atp.agent.cli.CliResult;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 案例草稿的写工具 —— 全部经由 {@code atp} CLI。
 *
 * <h3>⭐ 为什么写侧不直接调 CaseWriteService</h3>
 *
 * 本仓库有两条 AI 赋能路线：客户机器上的 opencode，和平台内的这个 agent。
 * 如果两边各写一份落库实现，格式迟早会漂 —— 不是谁写错了，
 * 而是**两份实现会各自演化**，而演化不同步是必然的。
 *
 * <p>让两条路线 exec 同一个二进制，一致性就从「靠纪律维持」变成「物理上唯一」。
 * 校验规则、字段形状、状态机、CAS 仲裁 —— 全都只有一份。
 *
 * <p>前端那条传统链路仍走平台自己的 REST 实现，允许漂移、由人对账 ——
 * 这是真实团队的分工，不必强求三端同源。
 *
 * <h3>agent 不是特权用户</h3>
 *
 * 它拿到的退出码、校验结果、版本冲突，与 opencode 拿到的完全相同。
 * 人做不了的事它也做不了 —— 因为根本是同一个客户端。
 */
@Slf4j
@Component
public class CaseDraftTools {

    @Autowired
    private AtpCliClient cli;

    @Tool(name = "get_case_schema",
            description = "获取案例草稿的 JSON Schema —— 必填字段、枚举取值、以及哪些字段不该由你产出。"
                    + "第一次写案例前先调它，不要凭印象猜字段名。")
    public String getCaseSchema() {
        CliResult r = cli.run("schema");
        return r.success() ? r.stdout() : render("获取 schema 失败", r);
    }

    @Tool(name = "create_draft",
            description = "创建一条新的案例草稿，返回 caseId 与 version。"
                    + "case_id 由你生成一个 UUID 传进来 —— 重试时复用同一个即可幂等，不会建出两条。")
    public String createDraft(
            @ToolParam(name = "case_id", description = "你生成的 UUID") String caseId,
            @ToolParam(name = "title", description = "案例标题，一句话说清测什么") String title,
            @ToolParam(name = "platform",
                    description = "执行平台，取值 PC_WEB / IOS / ANDROID。不确定就填 PC_WEB") String platform) {
        CliResult r = cli.run("draft",
                "--id", caseId,
                "--title", title,
                "--by", "agent",
                "-p", platform == null || platform.isBlank() ? "PC_WEB" : platform);
        if (!r.success()) {
            return render("创建草稿失败", r);
        }
        log.info("[TOOL][create_draft] {} version={} replayed={}",
                caseId, r.intOr("version", -1), r.replayed());
        return "草稿已创建：caseId=%s version=%d%s".formatted(
                r.str("caseId"), r.intOr("version", 0),
                r.replayed() ? "（幂等重放，这条草稿之前已经建过，不是新建）" : "");
    }

    @Tool(name = "save_draft",
            description = "保存案例内容到草稿，保存前会做完整校验（形状 + ATP 规范 STD-001~008）。"
                    + "draft_json 是完整案例对象：case_code / title / module_id / priority(P0~P3) / author "
                    + "/ precondition / steps[]，每个 step 必须有 seq / action / wait_strategy "
                    + "/ wait_timeout_sec / on_failure。字段形状不确定就先调 get_case_schema。"
                    + "必须带上你手上的 version —— 中间被人改过会被拒绝。")
    public String saveDraft(
            @ToolParam(name = "case_id", description = "草稿的 caseId") String caseId,
            @ToolParam(name = "draft_json", description = "完整的案例 JSON 对象") String draftJson,
            @ToolParam(name = "version", description = "你手上的版本号") Integer version) {
        CliResult r = cli.runWithJsonFile(draftJson,
                "update", caseId, "-f", "{{FILE}}",
                "--version", String.valueOf(version == null ? 0 : version));
        if (!r.success()) {
            log.warn("[TOOL][save_draft] {} {} {}", caseId, r.code(), r.message());
            return render("保存被拒绝", r);
        }
        return "已保存：version=%d，校验通过。".formatted(r.intOr("version", 0));
    }

    @Tool(name = "validate_case",
            description = "只校验不保存（纯本地，无副作用）。写完想先看看有没有问题就用它，"
                    + "返回每一条违反的明细。")
    public String validateCase(
            @ToolParam(name = "draft_json", description = "要校验的案例 JSON") String draftJson) {
        CliResult r = cli.runWithJsonFile(draftJson, "validate", "-f", "{{FILE}}");
        return r.success() ? "校验通过，没有任何问题。" : render("校验未通过", r);
    }

    @Tool(name = "commit_case",
            description = "提交草稿，落地为老平台原生的 DRAFT 案例（执行器无感知）。"
                    + "⚠️ 校验有 ERROR 时会被拒绝。提交前应当先让用户确认内容。")
    public String commitCase(
            @ToolParam(name = "case_id", description = "草稿的 caseId") String caseId,
            @ToolParam(name = "version", description = "你手上的版本号") Integer version) {
        CliResult r = cli.run("commit", caseId,
                "--version", String.valueOf(version == null ? 0 : version));
        if (!r.success()) {
            log.warn("[TOOL][commit_case] {} 被拒绝：{} {}", caseId, r.code(), r.message());
            return render("提交被拒绝", r);
        }
        log.info("[TOOL][commit_case] {} 已落地", caseId);
        return "提交成功：caseId=%s status=%s version=%d".formatted(
                r.str("caseId"), r.str("status"), r.intOr("version", 0));
    }

    @Tool(name = "preview_case",
            description = "渲染草稿当前内容供用户确认，并返回要带回 commit 的 version。提交前先给用户看这个。")
    public String previewCase(
            @ToolParam(name = "case_id", description = "草稿的 caseId") String caseId) {
        CliResult r = cli.run("preview", caseId);
        return r.success() ? r.stdout() : render("读取草稿失败", r);
    }

    /**
     * 把 CLI 的失败信封渲染成**模型能据此行动**的文本。
     *
     * <p>三件东西缺一不可：出了什么错（message）、具体错在哪（violations/questions）、
     * 下一步该做什么（nextAction）。只说"失败了"的话，模型只会原样重试。
     *
     * <p>⚠️ violations 与 questions 是两类：前者 agent 自己能改，
     * 后者是缺信息、必须去问人。合并了模型就会开始编。
     */
    private String render(String headline, CliResult r) {
        StringBuilder sb = new StringBuilder(headline)
                .append("（").append(r.code()).append("）");
        if (!r.message().isBlank()) {
            sb.append('：').append(r.message());
        }
        append(sb, "需要修正的问题", r.violations());
        append(sb, "⚠️ 缺少的信息（必须问用户，不要自己编）", r.questions());
        if (r.violations().isEmpty() && r.questions().isEmpty()) {
            // 没有结构化明细时（比如参数写错被 cobra 挡下），把原始输出交给模型
            String raw = (r.stderr() + r.stdout()).trim();
            if (!raw.isEmpty()) {
                sb.append('\n').append(raw);
            }
        }
        String next = r.nextAction();
        if (!next.isEmpty()) {
            sb.append("\n\n下一步：").append(next);
        }
        return sb.toString();
    }

    private void append(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append("：\n");
        items.forEach(v -> sb.append("  - ").append(v).append('\n'));
    }
}
