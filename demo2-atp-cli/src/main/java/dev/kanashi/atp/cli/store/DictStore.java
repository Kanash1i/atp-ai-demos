package dev.kanashi.atp.cli.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 字典表的只读访问（tc_project / tc_module）。
 *
 * <p>放在 {@code store} 包里是为了守住那条不变式：
 * <b>SQL 只出现在 {@code store} 包</b>（由 {@code SqlContainmentTest} 机械检查）。
 */
public final class DictStore {

    private final ConnectionFactory connections;

    public DictStore(ConnectionFactory connections) {
        this.connections = connections;
    }

    /** 模块字典 —— agent 用它确认 module_id 的取值范围，防编造。 */
    public List<ModuleEntry> listModules() throws SQLException {
        List<ModuleEntry> out = new ArrayList<>();
        try (Connection conn = connections.open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT p.project_id, p.project_code, p.project_name,
                            m.module_id, m.module_code, m.module_name
                       FROM tc_module m
                       JOIN tc_project p ON p.project_id = m.project_id
                      ORDER BY p.project_code, m.module_code
                     """)) {
            while (rs.next()) {
                out.add(new ModuleEntry(
                        rs.getString("project_id"), rs.getString("project_code"),
                        rs.getString("project_name"), rs.getString("module_id"),
                        rs.getString("module_code"), rs.getString("module_name")));
            }
        }
        return out;
    }

    public record ModuleEntry(
            String projectId, String projectCode, String projectName,
            String moduleId, String moduleCode, String moduleName) {}
}
