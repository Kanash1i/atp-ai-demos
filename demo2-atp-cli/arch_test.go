package atpcli_test

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// ⭐ 机械地守住一条架构不变式：SQL 只出现在 internal/store 包。
//
// 面试时"翻一个包就能把并发设计讲完"这个说法，靠的就是这条不变式。
// 但靠约定守不住 —— 下一个人图方便在某个命令里拼一句 SQL，
// 约定就破了，而且没有任何东西会提醒他。所以把它写成测试。
//
// 这跟本项目反复出现的那条判据是同一件事：
// 机器能判定的规则，就该交给机器强制，而不是写在文档里指望人自觉。
func TestSQLOnlyInStorePackage(t *testing.T) {
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
		if strings.HasPrefix(filepath.ToSlash(path), "internal/store/") {
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
		t.Fatalf("SQL 必须留在 internal/store 包里，下面这些地方漏出来了:\n  %s",
			strings.Join(offenders, "\n  "))
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
