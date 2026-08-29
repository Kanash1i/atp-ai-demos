package com.atp.web.controller;

import com.atp.platform.service.ExecutionQueryService;
import com.atp.platform.vo.ExecStatsVO;
import com.atp.platform.vo.NodeVO;
import com.atp.platform.vo.RunningRunVO;
import com.atp.platform.vo.TaskDetailVO;
import com.atp.platform.vo.TaskSummaryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 案例执行状态面板。
 *
 * <p>历史数据来自种子，正在跑的批次来自真实执行 —— 分工见 {@code ExecutionSeed} 的类注释。
 */
@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    @Autowired
    private ExecutionQueryService executionQueryService;

    /** 顶部四张卡片：今日执行 / 通过率 / 平均耗时 / 失败案例 */
    @GetMapping("/stats")
    public ExecStatsVO stats() {
        return executionQueryService.stats();
    }

    /**
     * 执行中的批次。
     *
     * <p>没有正在跑的批次时返回 **204 No Content** 而不是 200 加一个空对象 ——
     * 前端一看状态码就知道该渲染「当前无执行中的批次」，不用再判断字段是不是都为 0。
     */
    @GetMapping("/running")
    public ResponseEntity<RunningRunVO> running() {
        RunningRunVO running = executionQueryService.running();
        return running == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(running);
    }

    /** 最近执行结果，默认 200 条 */
    @GetMapping("/recent")
    public List<TaskSummaryVO> recent(@RequestParam(defaultValue = "200") int limit) {
        return executionQueryService.recent(limit);
    }

    /** 失败详情：步骤级结果 + 录像 + 失败截图 */
    @GetMapping("/tasks/{taskId}")
    public TaskDetailVO task(@PathVariable String taskId) {
        return executionQueryService.taskDetail(taskId);
    }

    /** 执行节点池 —— 顶栏那个「6/8」 */
    @GetMapping("/nodes")
    public List<NodeVO> nodes() {
        return executionQueryService.nodes();
    }
}
