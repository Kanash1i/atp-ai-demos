package atpcli_test

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// ⭐ 机械地守住凭证边界：整个 CLI 不得出现任何 SQL。
//
// 迁移之前这条断言是「SQL 只能出现在 internal/store 包」—— 那时需要划一道
// 包边界。现在那个包没了，规则反而更简单了：一句都不许有。
//
// 这不只是整洁问题。CLI 里出现 SQL 就意味着它要连数据库，
// 也就意味着它要持数据库凭证，而 agent 那一层就又能读到密码了。
// D-123 记过一次真实事故：agent 想删草稿、没找到工具，就读 .env 拼 SQL 删了。
//
// 靠约定守不住 —— 下一个人图方便在某个命令里拼一句 SQL，约定就破了，
// 而且没有任何东西会提醒他。所以把它写成测试：
// 机器能判定的规则，就该交给机器强制，而不是写在文档里指望人自觉。
func TestNoSQLAnywhereInCLI(t *testing.T) {
	sqlPattern := regexp.MustCompile(
		`(?i)\b(SELECT\s+\w|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|ALTER\s+TABLE|CREATE\s+TABLE)\b`)

	var offenders []string
	err := filepath.Walk(".", func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			if info.Name() == "bin" || strings.HasPrefix(info.Name(), ".") {
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") {
			return nil
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		for i, line := range strings.Split(string(b), "\n") {
			if code := stripComment(line); sqlPattern.MatchString(code) {
				offenders = append(offenders, path+":"+itoa(i+1)+"  "+strings.TrimSpace(line))
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(offenders) > 0 {
		t.Fatalf("CLI 里不该有任何 SQL —— 有 SQL 就要连库，要连库就要持凭证，\n"+
			"而凭证边界的全部意义就是让 agent 那一层拿不到数据库密码。\n"+
			"下面这些地方漏出来了:\n  %s", strings.Join(offenders, "\n  "))
	}
}

// 去掉行注释，避免文档注释里提一句 SELECT 就误报。
func stripComment(line string) string {
	t := strings.TrimSpace(line)
	if strings.HasPrefix(t, "//") || strings.HasPrefix(t, "*") || strings.HasPrefix(t, "/*") {
		return ""
	}
	if i := strings.Index(line, "//"); i >= 0 {
		return line[:i]
	}
	return line
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}
