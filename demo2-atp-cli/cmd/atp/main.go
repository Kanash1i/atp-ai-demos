// Command atp 是 ATP 案例编写 CLI 的入口。
//
// 它被 agent 高频反复调用，所以整个二进制刻意保持"启动即工作"：
// 没有框架初始化、没有连接池预热、没有配置扫描。
package main

import (
	"os"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/cli"
)

func main() {
	os.Exit(cli.Execute(os.Args[1:], os.Stdout, os.Stderr))
}
