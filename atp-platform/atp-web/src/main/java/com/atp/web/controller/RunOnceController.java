package com.atp.web.controller;

import com.atp.platform.service.RunOnceService;
import com.atp.platform.service.RunOnceService.RunOnceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跑一次并等结果 —— 给 {@code atp run} 命令用。
 *
 * <h3>状态码的分派语义</h3>
 *
 * <ul>
 *   <li>{@code 200} → **拿到结论了**，不论案例是通过还是失败。
 *       案例跑挂是一个有效结果，不是接口错误</li>
 *   <li>{@code 504} → **没拿到结论**：没有执行机认领，或者等超时了。
 *       这时 agent 该报告"验不了"，而不是"案例有问题"</li>
 * </ul>
 *
 * <p>两者混成一个错误码的话，agent 分不清「案例有问题」和「环境有问题」——
 * 它会把没有执行机在线误当成自己写的案例不行，然后开始改一份本来没问题的案例。
 */
@RestController
@RequestMapping("/api/executions")
public class RunOnceController {

    /** 同步等待的上限。执行一条案例通常几百毫秒到几秒，给足余量 */
    private static final int DEFAULT_TIMEOUT_SEC = 120;

    @Autowired
    private RunOnceService service;

    @PostMapping("/run-once")
    public ResponseEntity<RunOnceResult> runOnce(@RequestBody RunOnceRequest body) {
        int timeout = body.timeoutSec() == null || body.timeoutSec() <= 0
                ? DEFAULT_TIMEOUT_SEC : Math.min(body.timeoutSec(), 300);
        RunOnceResult r = service.runOnce(
                body.projectId() == null ? "P001" : body.projectId(),
                body.caseId(), timeout);
        return ResponseEntity.status(r.terminal() ? HttpStatus.OK : HttpStatus.GATEWAY_TIMEOUT).body(r);
    }

    public record RunOnceRequest(String caseId, String projectId, Integer timeoutSec) {
    }
}
