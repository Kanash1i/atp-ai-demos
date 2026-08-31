package com.atp.agent.tools;

import com.atp.platform.service.ExecutionQueryService;
import com.atp.platform.vo.ExecStatsVO;
import com.atp.platform.vo.NodeVO;
import com.atp.platform.vo.RunningRunVO;
import com.atp.platform.vo.TaskDetailVO;
import com.atp.platform.vo.TaskSummaryVO;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 执行侧的**只读**查询工具。
 *
 * <h3>为什么这些不走 CLI，而写侧必须走</h3>
 *
 * CLI 是两个 agent 唯一的**写入**通道 —— 因为两份写实现会各自演化，格式必然漂。
 * 读侧没有这个问题：两边查的是同一批表，查询逻辑不同不会导致数据不一致。
 *
 * <p>所以边界是「写侧统一，读侧各自实现」。
 * 硬把只读查询也塞进 CLI，换来的是每次查状态都 fork 一个进程，
 * 以及一份需要跟着平台 VO 一起改的额外契约。
 *
 * <p>⚠️ 例外是 {@code run_case_once}（在 {@link ExecutionTools}）——
 * 它会真的产生执行记录，属于写，所以走 CLI。
 */
@Slf4j
@Component
public class ExecutionQueryTools {

    @Autowired
    private ExecutionQueryService queryService;

    @Tool(name = "query_exec_stats",
            description = "今日执行概况：总数、失败数、P0 失败数、平均耗时。用户问「今天跑得怎么样」时用它。")
    public String queryExecStats() {
        ExecStatsVO s = queryService.stats();
        log.info("[TOOL][query_exec_stats]");
        return """
                今日执行概况：
                  总计 %d 条，失败 %d 条（其中 P0 失败 %d 条）
                  平均耗时 %s 秒（较昨日 %s）"""
                .formatted(s.todayTotal(), s.failedCount(), s.failedP0Count(),
                        s.avgDurationSec(), s.avgDurationDelta());
    }

    @Tool(name = "query_running_batch",
            description = "当前正在跑的批次与实时进度。用户问「跑完了没」「现在什么进度」时用它。")
    public String queryRunningBatch() {
        RunningRunVO r = queryService.running();
        if (r == null) {
            // ⚠️ 说清是「没有批次在跑」而不是「查不到」—— 后者会让模型以为是它查错了，
            //    然后换个说法再查一遍
            return "当前没有正在执行的批次。所有派发的任务都已经跑完了。";
        }
        log.info("[TOOL][query_running_batch] {}", r.runCode());
        return """
                批次 %s（%s / %s）正在执行：
                  进度 %d/%d —— 通过 %d，失败 %d，跳过 %d，执行中 %d
                  浏览器 %s，触发来源 %s"""
                .formatted(r.runCode(), r.projectName(), r.suiteName(),
                        r.doneCount(), r.totalCount(), r.passedCount(), r.failedCount(),
                        r.skippedCount(), r.runningCount(), r.browser(), r.triggerSource());
    }

    @Tool(name = "query_recent_results",
            description = "最近的执行结果列表，含案例编号、状态、耗时、有没有录像。"
                    + "用户问「最近跑了些什么」「哪些失败了」时用它。")
    public String queryRecentResults(
            @ToolParam(name = "limit", description = "返回条数，默认 10，最多 50") Integer limit) {
        int n = limit == null ? 10 : Math.min(Math.max(limit, 1), 50);
        List<TaskSummaryVO> list = queryService.recent(n);
        if (list.isEmpty()) {
            return "还没有任何执行记录。";
        }
        StringBuilder sb = new StringBuilder("最近 ").append(list.size()).append(" 条执行结果：\n");
        list.forEach(t -> sb.append("  ").append(t.status()).append("  ")
                .append(t.caseCode()).append("  ").append(t.caseTitle())
                .append("  ").append(t.duration())
                .append("  节点 ").append(t.nodeName())
                .append(t.hasVideo() ? "  [有录像 taskId=" + t.taskId() + "]" : "")
                .append('\n'));
        log.info("[TOOL][query_recent_results] {} 条", list.size());
        return sb.toString();
    }

    @Tool(name = "query_task_detail",
            description = "一次执行的详情：失败在第几步、错误信息、录像地址、步骤级结果。"
                    + "用户问「那条为什么失败」「录像在哪」时用它 —— "
                    + "taskId 从 query_recent_results 或 query_running_batch 拿。")
    public String queryTaskDetail(
            @ToolParam(name = "task_id", description = "执行任务 id") String taskId) {
        TaskDetailVO d;
        try {
            d = queryService.taskDetail(taskId);
        } catch (RuntimeException e) {
            // 把「找不到」如实说出来，别让模型以为是自己的问题
            return "找不到这次执行（taskId=%s）。确认 id 是否正确 —— 它应当来自 query_recent_results 的返回。"
                    .formatted(taskId);
        }
        log.info("[TOOL][query_task_detail] {} → {}", taskId, d.status());

        StringBuilder sb = new StringBuilder();
        sb.append("%s  %s（%s）\n".formatted(d.caseCode(), d.caseTitle(), d.status()))
                .append("  批次 %s，节点 %s，耗时 %s\n".formatted(d.runCode(), d.nodeName(), d.duration()))
                .append("  开始 %s，结束 %s\n".formatted(d.startedAt(), d.finishedAt()));

        if (d.failedSeq() != null) {
            sb.append("  ⚠️ 失败在第 ").append(d.failedSeq()).append(" 步\n");
        }
        if (d.errorMsg() != null && !d.errorMsg().isBlank()) {
            // Playwright 的堆栈有几十行，模型和人都只看得下第一行
            String first = d.errorMsg().split("\n")[0];
            sb.append("  错误：").append(first).append('\n');
        }
        sb.append("  录像：/api/artifacts/... （在执行状态面板点这条记录可以直接播放）\n");

        sb.append("""

                ⚠️ 执行失败不等于案例写错了 —— 也可能是被测系统真有 bug，
                而那正是这条案例该发现的东西。如实把现象讲给用户，由他判断。""");
        return sb.toString();
    }

    @Tool(name = "query_exec_nodes",
            description = "执行节点池的状态：哪些在线、哪些在忙、最后心跳时间。"
                    + "用户问「有几台机器在跑」「节点还活着吗」时用它。")
    public String queryExecNodes() {
        List<NodeVO> nodes = queryService.nodes();
        long online = nodes.stream().filter(NodeVO::online).count();
        StringBuilder sb = new StringBuilder("执行节点池：%d/%d 在线\n".formatted(online, nodes.size()));
        nodes.forEach(n -> sb.append("  ").append(n.nodeName())
                .append("  ").append(n.online() ? n.status() : "OFFLINE")
                .append(n.currentTaskId() == null ? "" : "  正在跑 " + n.currentTaskId())
                .append("  心跳 ").append(n.lastHeartbeat()).append('\n'));
        log.info("[TOOL][query_exec_nodes] {}/{}", online, nodes.size());
        return sb.toString();
    }
}
