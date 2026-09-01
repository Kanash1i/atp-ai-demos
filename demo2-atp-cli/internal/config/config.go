// Package config 从环境变量与仓库根 .env 读取配置。
//
// ⚠️ 代码里不出现任何硬编码的 URL / 账号 / 口令 —— 一条都不许。
// 取不到就 fail fast 并说清楚缺哪个变量，不要给默认值：
// 默认值会让"配置漏了"变成"连到了错的库"，后者难查得多。
package config

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	// EnvAPIURL 平台的 HTTP 入口。目前只有 inspect 用它 ——
	// 它是 CLI 第一个不碰数据库的命令，也是「CLI 改调平台 API」那步的试水。
	EnvAPIURL = "ATP_API_URL"

	// 换 token 用的凭证。⭐ 迁移之后 CLI 只持这一对 ——
	// 数据库账号密码留在平台那一侧，agent 那一层拿不到。
	EnvClientID     = "ATP_CLIENT_ID"
	EnvClientSecret = "ATP_CLIENT_SECRET"

	EnvDBURL      = "ATP_DB_URL"
	EnvDBUser     = "ATP_DB_USER"
	EnvDBPassword = "ATP_DB_PASSWORD"
)

type Config struct{ values map[string]string }

// Load 优先级：环境变量 > 仓库根 .env。
func Load() *Config {
	v := dotEnv()
	for _, kv := range os.Environ() {
		if i := strings.IndexByte(kv, '='); i > 0 {
			v[kv[:i]] = kv[i+1:]
		}
	}
	return &Config{values: v}
}

func (c *Config) Require(key string) (string, error) {
	if s := strings.TrimSpace(c.values[key]); s != "" {
		return s, nil
	}
	return "", fmt.Errorf("缺少配置 %s。请在仓库根目录 .env 里设置，或用环境变量传入", key)
}

// Optional 口令允许为空（本地无口令的 PG 实例）。
func (c *Config) Optional(key string) string { return c.values[key] }

// APIBase 平台 HTTP 入口，去掉尾部斜杠。
//
// ⚠️ inspect 只需要这一个变量，不需要任何数据库凭证 ——
// 这一点要保住：它是「agent 那一层不该看到 DB 密码」这个方向上第一个真正做到的命令。
func (c *Config) APIBase() (string, error) {
	v, err := c.Require(EnvAPIURL)
	if err != nil {
		return "", err
	}
	return strings.TrimRight(v, "/"), nil
}

// DSN 拼出 pgx 能用的连接串。ATP_DB_URL 支持两种写法：
// 直接给 postgres://... 就原样用；给 jdbc:postgresql://host:port/db 则转换 ——
// 因为 .env 是两侧共用的，知识侧那边是 Java。
func (c *Config) DSN() (string, error) {
	raw, err := c.Require(EnvDBURL)
	if err != nil {
		return "", err
	}
	user, err := c.Require(EnvDBUser)
	if err != nil {
		return "", err
	}
	pass := c.Optional(EnvDBPassword)

	if strings.HasPrefix(raw, "postgres://") || strings.HasPrefix(raw, "postgresql://") {
		return raw, nil
	}
	hostPart, ok := strings.CutPrefix(raw, "jdbc:postgresql://")
	if !ok {
		return "", fmt.Errorf("%s 格式无法识别: %s（支持 postgres://... 或 jdbc:postgresql://...）", EnvDBURL, raw)
	}
	return fmt.Sprintf("postgres://%s:%s@%s", user, pass, hostPart), nil
}

// 从当前目录向上找仓库根的 .env，最多找 5 层。找不到返回空表，不报错 ——
// 环境变量可能就够了，让 Require 去报缺哪个。
func dotEnv() map[string]string {
	out := map[string]string{}
	dir, err := os.Getwd()
	if err != nil {
		return out
	}
	for i := 0; i < 5; i++ {
		f, err := os.Open(filepath.Join(dir, ".env"))
		if err == nil {
			defer f.Close()
			sc := bufio.NewScanner(f)
			for sc.Scan() {
				line := strings.TrimSpace(sc.Text())
				if line == "" || strings.HasPrefix(line, "#") {
					continue
				}
				k, v, ok := strings.Cut(line, "=")
				if !ok {
					continue
				}
				out[strings.TrimSpace(k)] = strings.Trim(strings.TrimSpace(v), `"'`)
			}
			return out
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return out
}
