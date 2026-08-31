package apistore

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
)

// 桩用的响应体照抄平台 ApiExceptionHandler 实际发出的形状（RFC 7807）。
const problemBase = "https://atp.example/problems/"

func stub(t *testing.T, h http.HandlerFunc) *APIStore {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return New(srv.URL, "", "")
}

func writeProblem(w http.ResponseWriter, status int, kind, detail string, extra map[string]any) {
	body := map[string]any{"status": status, "detail": detail, "title": http.StatusText(status)}
	if kind != "" {
		body["type"] = problemBase + kind
	}
	for k, v := range extra {
		body[k] = v
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func okView(w http.ResponseWriter, v map[string]any) {
	if v["status"] == nil {
		v["status"] = "AI_DRAFT"
	}
	if v["caseType"] == nil {
		v["caseType"] = "PC_WEB"
	}
	if v["platformStatus"] == nil {
		v["platformStatus"] = "AI_DRAFT"
	}
	_ = json.NewEncoder(w).Encode(v)
}

// ⭐ 这条是整张映射表里最要紧的一对：10 和 13 绝不合并。
// 两者都是 409，只有 RFC 7807 的 type 能区分 —— 而下一步动作完全相反：
// 前者"重新拉一遍再确认"，后者"停下问人，重试没用"。
func TestConflict_VersionAndStateMustNotMerge(t *testing.T) {
	for _, tc := range []struct {
		kind string
		want model.ExitCode
	}{
		{kindVersionConflict, model.VersionConflict},
		{kindStateConflict, model.StateConflict},
	} {
		s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
			writeProblem(w, 409, tc.kind, "冲突了", nil)
		})
		if got := s.Commit(context.Background(), "c1", 3).Code; got != tc.want {
			t.Fatalf("409 type=%s 应映射成 %s，实际 %s", tc.kind, tc.want, got)
		}
	}
}

// 没有 type 的 409 无法区分。宁可报 20 让人来看，也不要猜一个 ——
// 猜错的那一半会让 agent 对着"重试也没用"的状态无限重来。
func TestConflict_WithoutTypeIsInfraError(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		writeProblem(w, 409, "", "冲突了", nil)
	})
	r := s.Commit(context.Background(), "c1", 3)
	if r.Code != model.InfraError {
		t.Fatalf("无 type 的 409 应报 20，实际 %s", r.Code)
	}
	if !strings.Contains(r.Message, "无法区分") {
		t.Fatalf("错误信息该说清为什么报 20，实际：%s", r.Message)
	}
}

// 判断只看 status + type，绝不解析 detail 文案 ——
// 平台改一次措辞就让文本匹配静默失效，而测试还是绿的。
func TestConflict_DoesNotParseDetailText(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		// detail 说的是"版本"，type 说的是状态冲突。以 type 为准。
		writeProblem(w, 409, kindStateConflict, "版本不一致：库中 version=5", nil)
	})
	if got := s.Commit(context.Background(), "c1", 3).Code; got != model.StateConflict {
		t.Fatalf("应以 type 为准判成 13，实际 %s —— 说明代码在读 detail 文案", got)
	}
}

// 422 要把每一条违反明细带回去：只给一句"校验失败"的话 agent 只能瞎改。
func TestValidationFailed_CarriesFindings(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		writeProblem(w, 422, kindValidationFailed, "规范校验未通过", map[string]any{
			"violatedCodes": []string{"STD-001"},
			"findings": []map[string]any{
				{"std": "STD-001", "severity": "ERROR", "seq": 3, "message": "禁止绝对 XPath"},
				{"std": "STD-007", "severity": "ERROR", "seq": 0, "message": "缺少断言"},
			},
		})
	})
	r := s.Update(context.Background(), "c1", 1, `{"steps":[]}`)
	if r.Code != model.ValidationFailed {
		t.Fatalf("422 应映射成 12，实际 %s", r.Code)
	}
	if len(r.Violations) != 2 {
		t.Fatalf("两条 finding 都该透出来，实际 %d 条：%v", len(r.Violations), r.Violations)
	}
	if !strings.Contains(r.Violations[0], "第 3 步") {
		t.Fatalf("带 seq 的 finding 要指出第几步，实际：%s", r.Violations[0])
	}
	if strings.Contains(r.Violations[1], "第 0 步") {
		t.Fatalf("seq<=0 时不该硬凑步号，实际：%s", r.Violations[1])
	}
}

// 400 与 422 对 agent 是同一个动作：读错误、改内容、重发。
// 分成两个码不会让它做出不同的事。
func TestBadRequest_MapsToValidationFailed(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		writeProblem(w, 400, "", "草稿不是合法 JSON：Unexpected character", nil)
	})
	if got := s.Update(context.Background(), "c1", 1, "{bad").Code; got != model.ValidationFailed {
		t.Fatalf("400 应映射成 12，实际 %s", got)
	}
}

func TestNotFound_MapsTo11(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		writeProblem(w, 404, "", "案例不存在：c9", nil)
	})
	if got := s.Show(context.Background(), "c9").Code; got != model.NotFound {
		t.Fatalf("404 应映射成 11，实际 %s", got)
	}
}

// 403 是 scope 不够 —— 换 token 也没用，停下问人。归 13 而不是 20。
func TestForbidden_IsStateConflictNotRetryable(t *testing.T) {
	calls := 0
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		calls++
		writeProblem(w, 403, "", "当前 token 没有 case:write 权限", nil)
	})
	r := s.Commit(context.Background(), "c1", 1)
	if r.Code != model.StateConflict {
		t.Fatalf("403 应映射成 13，实际 %s", r.Code)
	}
	if calls != 1 {
		t.Fatalf("403 不该重试，实际请求了 %d 次", calls)
	}
	if !strings.Contains(r.Message, "case:write") {
		t.Fatalf("缺的权限名要原样给人看，实际：%s", r.Message)
	}
}

func TestServerError_MapsTo20(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		writeProblem(w, 500, "", "NullPointerException: null", nil)
	})
	if got := s.Show(context.Background(), "c1").Code; got != model.InfraError {
		t.Fatalf("500 应映射成 20，实际 %s", got)
	}
}

// 成功路径：字段要落到 CaseRow 的正确位置上。
// ⚠️ status 与 platformStatus 是两张表的两个状态机，绝不能互相顶替。
func TestOk_MapsFieldsToCorrectTables(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		okView(w, map[string]any{
			"caseId": "c1", "draftJson": `{"steps":[]}`, "version": 7,
			"status": "AI_DRAFT", "caseType": "IOS", "platformStatus": "DRAFT",
		})
	})
	r := s.Show(context.Background(), "c1")
	if r.Code != model.OK {
		t.Fatalf("应当成功，实际 %s: %s", r.Code, r.Message)
	}
	if r.Row.Status != model.StatusAIDraft {
		t.Fatalf("status 该取 tc_step 的 AI_DRAFT，实际 %s", r.Row.Status)
	}
	if r.Row.PlatformStatus != model.StatusDraft {
		t.Fatalf("platformStatus 该取 tc_case 的 DRAFT，实际 %s", r.Row.PlatformStatus)
	}
	if r.Row.Version != 7 {
		t.Fatalf("version 该是 tc_step 的 7，实际 %d", r.Row.Version)
	}
	if r.Row.CaseType != model.TypeIOS {
		t.Fatalf("caseType 该是 IOS，实际 %s", r.Row.CaseType)
	}
}

// 平台传的是名字不是码值。收到无法识别的值要报错，不能默默当成 0 ——
// 静默取零值会让一个 AI_DRAFT 案例看起来像个非法状态。
func TestOk_UnknownEnumIsReportedNotSilentlyZeroed(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		okView(w, map[string]any{"caseId": "c1", "version": 1, "status": "PENDING_REVIEW"})
	})
	r := s.Show(context.Background(), "c1")
	if r.Code != model.InfraError {
		t.Fatalf("无法识别的枚举该报 20，实际 %s", r.Code)
	}
	if !strings.Contains(r.Message, "PENDING_REVIEW") {
		t.Fatalf("错误信息要带上那个值本身，实际：%s", r.Message)
	}
}

// ⭐ WARN 不拦操作，但 agent 看不到就不会去改。
// passed=true 且 warnCount>0 是正常状态，不是矛盾。
func TestOk_SurfacesWarningsOnSuccess(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		okView(w, map[string]any{
			"caseId": "c1", "version": 2,
			"validation": map[string]any{
				"passed": true, "errorCount": 0, "warnCount": 1,
				"findings": []map[string]any{
					{"std": "STD-002", "severity": "WARN", "seq": 3, "message": "定位器过于宽泛"},
				},
			},
		})
	})
	r := s.Update(context.Background(), "c1", 1, `{"steps":[]}`)
	if r.Code != model.OK {
		t.Fatalf("有 WARN 无 ERROR 应当成功，实际 %s", r.Code)
	}
	if len(r.Violations) != 1 || !strings.Contains(r.Violations[0], "STD-002") {
		t.Fatalf("WARN 该透出来，实际 %v", r.Violations)
	}
}

// commit 只带 version，不带内容 —— 提交的是库里那份用户确认过的快照。
func TestCommit_SendsVersionOnly(t *testing.T) {
	var got map[string]any
	s := stub(t, func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&got)
		okView(w, map[string]any{"caseId": "c1", "version": 4, "status": "DRAFT", "platformStatus": "DRAFT"})
	})
	s.Commit(context.Background(), "c1", 3)
	if _, ok := got["draftJson"]; ok {
		t.Fatal("commit 不该带内容 —— 否则确认的和提交的可能不是同一份")
	}
	if got["version"] != float64(3) {
		t.Fatalf("version 该原样带上，实际 %v", got["version"])
	}
}

// ⭐ 整套幂等的第一条规则：重放在语义上是成功，必须返回 0。
//
// 场景：commit 成功了，响应在网络上丢了，agent 用同一个 version 重试。
// 平台此刻看到状态已离开编写态，抛的是 409 state-conflict ——
// 但正确答案是"你那次已经成功了"。返回 13 会让 agent 停下来问人，
// 问的是一个其实已经提交好了的案例。
func TestCommit_LostResponseThenRetryIsReplayNotConflict(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/commit") {
			writeProblem(w, 409, kindStateConflict, "案例已经提交过了", nil)
			return
		}
		// 探测读回来：版本恰好前进一格，状态已离开编写态
		okView(w, map[string]any{"caseId": "c1", "version": 4,
			"status": "DRAFT", "platformStatus": "DRAFT"})
	})

	r := s.Commit(context.Background(), "c1", 3)
	if r.Code != model.OK {
		t.Fatalf("响应丢失后重试必须返回 0，实际 %s: %s", r.Code, r.Message)
	}
	if !r.Replayed {
		t.Fatal("要标成重放，否则调用方分不清这次到底改了没有")
	}
}

// 真正的状态冲突不能被当成重放放过去。
// 判据是版本恰好 expected+1；别人改过的话对不上。
func TestCommit_RealStateConflictStaysAt13(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/commit") {
			writeProblem(w, 409, kindStateConflict, "案例已经提交过了", nil)
			return
		}
		// 版本跳了好几格 —— 中间有别人的改动，不是我这次的重放
		okView(w, map[string]any{"caseId": "c1", "version": 9,
			"status": "DRAFT", "platformStatus": "ACTIVE"})
	})

	if got := s.Commit(context.Background(), "c1", 3).Code; got != model.StateConflict {
		t.Fatalf("版本对不上就是真冲突，应为 13，实际 %s", got)
	}
}

// 403 同样映射成 13，但它跟状态无关 —— 不该为它多打一次探测请求。
func TestForbidden_DoesNotTriggerReplayProbe(t *testing.T) {
	var paths []string
	s := stub(t, func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		writeProblem(w, 403, "", "当前 token 没有 case:write 权限", nil)
	})

	if got := s.Commit(context.Background(), "c1", 3).Code; got != model.StateConflict {
		t.Fatalf("403 应为 13，实际 %s", got)
	}
	if len(paths) != 1 {
		t.Fatalf("403 不该触发重放探测，实际请求了 %d 次：%v", len(paths), paths)
	}
}

// case_code 撞号是 agent 自己能修的 —— 归 12 而不是 13。
// 它跟"状态不允许"不同：换个号重写草稿再提交就行，不用停下问人。
func TestDuplicateCaseCode_IsAgentFixable(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		writeProblem(w, 409, kindDuplicateCode, "case_code ATP-CART-0001 已存在", nil)
	})
	r := s.Commit(context.Background(), "c1", 3)
	if r.Code != model.ValidationFailed {
		t.Fatalf("撞号应为 12（自己换号重来），实际 %s", r.Code)
	}
	if len(r.Violations) == 0 || !strings.Contains(r.Violations[0], "换一个号") {
		t.Fatalf("要告诉 agent 下一步怎么做，实际 %v", r.Violations)
	}
}

// 探测本身失败时维持原判，不猜。
func TestStateConflict_ProbeFailureKeepsOriginalVerdict(t *testing.T) {
	s := stub(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/commit") {
			writeProblem(w, 409, kindStateConflict, "案例已经提交过了", nil)
			return
		}
		writeProblem(w, 500, "", "探测的时候平台挂了", nil)
	})
	if got := s.Commit(context.Background(), "c1", 3).Code; got != model.StateConflict {
		t.Fatalf("探测失败该维持 13，实际 %s", got)
	}
}
