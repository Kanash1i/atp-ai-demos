package com.atp.web.auth;

import com.atp.common.enums.ApiScope;
import com.atp.platform.entity.SysApiClient;
import com.atp.platform.mapper.SysApiClientMapper;
import com.atp.platform.service.ApiClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 首次启动时把 CLI 的机器主体建出来。
 *
 * <h3>为什么 secret 只打印一次到日志</h3>
 *
 * 库里存的是 hash，平台自己也读不出明文。所以创建那一刻是**唯一**能拿到它的时机 ——
 * 打印出来让人抄进 `.env`，之后就再也读不到了。丢了只能重建主体换一份。
 *
 * <p>⚠️ 这个引导是**开发与演示用**的。真实生产该走管理后台创建、密文下发，
 * 而不是打进应用日志 —— 日志会被收集、会被转发、会被截图。
 * 这里这么做是因为本仓库是单机 demo，且这一条在文档里说明了边界。
 */
@Slf4j
@Component
public class ApiClientBootstrap implements CommandLineRunner {

    /** CLI 用的主体 id。两条路线（opencode 与平台 agent）共用同一个客户端，也共用这个身份 */
    private static final String CLI_CLIENT_ID = "atp-cli";

    @Autowired
    private ApiClientService apiClientService;

    @Autowired
    private SysApiClientMapper mapper;

    @Override
    public void run(String... args) {
        if (mapper.selectById(CLI_CLIENT_ID) != null) {
            return;
        }
        // ⭐ 给的是「写案例 + 单条自验 + 探查」，**没有 exec:dispatch** ——
        //    派发批次是平台调度权，涉及排队与配额，不该发到客户机器上。
        //    客户写个循环就能把执行机资源池占满。
        String secret = apiClientService.create(CLI_CLIENT_ID, "atp CLI（opencode 与平台 agent 共用）",
                List.of(ApiScope.CASE_WRITE.code(), ApiScope.EXEC_RUN_ONCE.code(), ApiScope.INSPECT.code()),
                "bootstrap");

        log.warn("""

                ╔══════════════════════════════════════════════════════════════════════╗
                ║  已创建 CLI 的机器主体 —— secret 只显示这一次，抄进仓库根 .env         ║
                ╠══════════════════════════════════════════════════════════════════════╣
                ║  ATP_CLIENT_ID={}
                ║  ATP_CLIENT_SECRET={}
                ╠══════════════════════════════════════════════════════════════════════╣
                ║  权限：case:write  exec:run-once  inspect                             ║
                ║  不含 exec:dispatch —— 派发批次是平台调度权，不发给客户端              ║
                ╚══════════════════════════════════════════════════════════════════════╝
                """, CLI_CLIENT_ID, secret);
    }
}
