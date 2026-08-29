package com.atp.web;

import com.atp.platform.seed.ApprovalSeed;
import com.atp.platform.seed.SeedImporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 启动时按需导入种子数据。
 *
 * <p>默认**关闭**。开启方式：{@code --atp.seed.enabled=true}，或在 .env 里设 {@code ATP_SEED_ENABLED=true}。
 *
 * <p>⚠️ 之所以不默认开：库是两条路线共用的，CLI 那边可能正拿它做幂等测试。
 * 每次重启都往里灌数据，会让「这条案例是谁写进去的」变得说不清。
 */
@Slf4j
@Order(1)
@Component
public class SeedRunner implements ApplicationRunner {

    @Autowired
    private SeedImporter importer;

    @Autowired
    private ApprovalSeed approvalSeed;

    @Value("${atp.seed.enabled:false}")
    private boolean enabled;

    @Value("${atp.seed.dir:../seed}")
    private String seedDir;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("种子导入未启用（atp.seed.enabled=false），跳过");
            return;
        }
        Path dir = Path.of(seedDir).toAbsolutePath().normalize();
        log.info("开始导入种子数据，目录 {}", dir);
        importer.importUsers();
        importer.importCases(dir);
        // 审批必须在案例之后 —— 它要按 case_code 反查 case_id
        approvalSeed.importApprovals();
        // ⚠️ 执行历史不在这里导 —— 它会过期（按「今天」生成），
        //    所以交给 ExecutionRefreshTask 在每次启动与每天零点重造，与 --seed 开关无关。
    }
}
