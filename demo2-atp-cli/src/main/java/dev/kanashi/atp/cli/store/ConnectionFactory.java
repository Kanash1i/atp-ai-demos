package dev.kanashi.atp.cli.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 每次调用开一条连接。
 *
 * <p>⚠️ 这里刻意<b>不用连接池</b>：CLI 进程只活 300ms 左右，
 * HikariCP 的预热成本比它省下来的还多。连接池属于长驻服务，不属于 CLI。
 */
@FunctionalInterface
public interface ConnectionFactory {

    Connection open() throws SQLException;

    static ConnectionFactory of(String jdbcUrl, String user, String password) {
        return () -> DriverManager.getConnection(jdbcUrl, user, password);
    }
}
