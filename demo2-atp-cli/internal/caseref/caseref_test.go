package caseref

import (
	"context"
	"errors"
	"strings"
	"testing"
)

type fakeDoer struct {
	paths  []string
	status int
	body   string
}

func (f *fakeDoer) Do(_ context.Context, _, path string, _ any) (int, []byte, error) {
	f.paths = append(f.paths, path)
	return f.status, []byte(f.body), nil
}

// ⭐ 形状识别：编号去查，UUID 原样用。
// 靠形状而不是 flag —— 加 --by-code 等于把「我该用哪个」推给调用方，
// 而调用方手上只有一个，它自己知道那是什么。
func TestIsCode(t *testing.T) {
	for _, c := range []struct {
		in   string
		want bool
	}{
		{"ATP-CART-0014", true},
		{"atp-cart-0014", true},    // 用户手敲的可能是小写
		{"  ATP-CART-0014 ", true}, // 复制粘贴常带空白
		{"ATP-SEARCH-0011", true},
		{"9f2d7c1e-1234-4567-89ab-cdef01234567", false},
		{"ATP-CART-14", false},    // 位数不对
		{"ATP-CART2-0014", false}, // 中段必须是字母
		{"", false},
	} {
		if got := IsCode(c.in); got != c.want {
			t.Errorf("IsCode(%q) = %v，期望 %v", c.in, got, c.want)
		}
	}
}

func TestResolve_CodeIsLookedUp(t *testing.T) {
	d := &fakeDoer{status: 200, body: `{"caseId":"9f2d-uuid","caseCode":"ATP-CART-0014"}`}
	got, err := Resolve(context.Background(), d, "ATP-CART-0014")
	if err != nil {
		t.Fatal(err)
	}
	if got != "9f2d-uuid" {
		t.Fatalf("应解析成 caseId，实际 %q", got)
	}
	if len(d.paths) != 1 || !strings.HasSuffix(d.paths[0], "/api/cases/by-code/ATP-CART-0014") {
		t.Fatalf("打错了路径: %v", d.paths)
	}
}

// caseId 不该触发查询 —— agent 刚 draft 完，多一次往返没有意义。
func TestResolve_CaseIDPassesThroughWithoutRequest(t *testing.T) {
	d := &fakeDoer{status: 500, body: `{}`} // 真发请求就会挂
	got, err := Resolve(context.Background(), d, "9f2d7c1e-1234-4567-89ab-cdef01234567")
	if err != nil {
		t.Fatal(err)
	}
	if got != "9f2d7c1e-1234-4567-89ab-cdef01234567" {
		t.Fatalf("caseId 该原样返回，实际 %q", got)
	}
	if len(d.paths) != 0 {
		t.Fatalf("caseId 不该触发查询，实际发了 %v", d.paths)
	}
}

// ⭐ 编号查不到与 caseId 查不到是同一个码。
// 对调用方来说「你给的标识找不到案例」是同一件事，
// 不该因为标识的种类不同而分成两个码。
func TestResolve_UnknownCodeIsNotFound(t *testing.T) {
	d := &fakeDoer{status: 404, body: `{"detail":"编号不存在"}`}
	_, err := Resolve(context.Background(), d, "ATP-NOPE-9999")

	var nf *NotFoundError
	if !errors.As(err, &nf) {
		t.Fatalf("应返回 NotFoundError，实际 %v", err)
	}
	if !strings.Contains(err.Error(), "ATP-NOPE-9999") {
		t.Fatalf("错误信息要带上那个编号，实际：%s", err)
	}
}

// 不是编号也不是合法 UUID 时不去猜 —— 让平台判。
// 猜错了报的是"格式不对"，而真正的原因可能是案例不存在，
// 那会把人指向错误的方向。
func TestResolve_GarbageIsNotGuessed(t *testing.T) {
	d := &fakeDoer{status: 500}
	got, err := Resolve(context.Background(), d, "随便写的")
	if err != nil {
		t.Fatalf("不该在本地判定格式，实际报错 %v", err)
	}
	if got != "随便写的" {
		t.Fatalf("应原样透传，实际 %q", got)
	}
	if len(d.paths) != 0 {
		t.Fatal("不像编号就不该查")
	}
}

// 平台返回 200 但没有 caseId —— 这是契约被破坏，要报得清楚。
func TestResolve_MissingCaseIDInResponse(t *testing.T) {
	d := &fakeDoer{status: 200, body: `{"caseCode":"ATP-CART-0014"}`}
	if _, err := Resolve(context.Background(), d, "ATP-CART-0014"); err == nil {
		t.Fatal("响应里没有 caseId 应当报错，而不是返回空串")
	} else if !strings.Contains(err.Error(), "caseId") {
		t.Fatalf("错误信息要说清缺什么，实际：%s", err)
	}
}
