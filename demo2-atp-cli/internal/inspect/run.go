package inspect

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/httpx"
	"time"
)

// RunResult 一次自验执行的结果。
//
// ⭐ 不变量：Terminal 是「有没有拿到结论」，Status 是「结论是什么」。
// 这两个不能合并成一个字段 —— 合并之后 agent 就分不清
// 「案例有问题」和「环境有问题」，会把"没有执行机在线"误当成自己写的案例不行，
// 然后开始改一份本来没问题的案例。
//
// ⚠️ Terminal 为 false 时 Status 不可信（只有 TIMEOUT 这一种值），别拿它当执行结论。
type RunResult struct {
	Terminal   bool    `json:"terminal"`
	RunCode    string  `json:"runCode"`
	TaskID     *string `json:"taskId"`
	Status     string  `json:"status"` // PASSED / FAILED / SKIPPED / ABORTED / TIMEOUT
	DurationMs *int64  `json:"durationMs"`
	FailedSeq  *int    `json:"failedSeq"`
	ErrorMsg   *string `json:"errorMsg"`
	VideoURL   *string `json:"videoUrl"`
	Note       *string `json:"note"`
}

// Run 跑一次自验。
//
// ⭐ 只跑一次，不做「失败就改、改完再跑」的闭环。两个理由任一都足以否掉自动重试：
//
//  1. 执行失败 ≠ 案例写错了 —— 被测系统真有 bug 时，自动改案例会把 bug 改没，
//     而发现 bug 正是测试的目的。
//  2. 改到能跑通 ≠ 改对了 —— 以"跑通"为目标，agent 最省力的路径是【削弱断言】：
//     断言不了就删掉，等不到就放宽。测试变绿，但什么也不保证了。
//
// 所以命令的语义是：跑一次、如实报告、人决定。
func (c *Client) Run(ctx context.Context, caseID string, timeoutSec int) (*Result2, error) {
	// ⚠️ HTTP 客户端必须比平台等得久 —— 否则平台还在等执行机，
	//    我这边先超时了，agent 拿到的会是 20（环境坏了）而不是平台的真实结论。
	c.c.HTTP.Timeout = time.Duration(timeoutSec)*time.Second + 30*time.Second

	status, raw, err := c.c.Do(ctx, http.MethodPost, "/api/executions/run-once",
		map[string]any{"caseId": caseID, "timeoutSec": timeoutSec})
	if err != nil {
		return nil, fmt.Errorf("调不通平台执行接口 %s: %w", c.c.Base, err)
	}
	var run RunResult
	if err := json.Unmarshal(raw, &run); err != nil {
		return nil, fmt.Errorf("平台返回的不是合法 JSON（HTTP %d）: %s",
			status, httpx.Truncate(string(raw), 200))
	}
	return &Result2{Status: status, Run: run}, nil
}

// Result2 带上 HTTP 状态 —— 退出码的分派依据是它。
type Result2 struct {
	Status int
	Run    RunResult
}
