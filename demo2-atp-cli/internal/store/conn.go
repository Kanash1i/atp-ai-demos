package store

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Open 开一条连接。
//
// ⚠️ 刻意不用连接池：CLI 进程只活几百毫秒，池的预热成本比它省下来的还多。
// 连接池属于长驻服务，不属于 CLI。
func Open(ctx context.Context, dsn string) (*pgx.Conn, error) {
	return pgx.Connect(ctx, dsn)
}

func newUUID() string { return uuid.NewString() }
