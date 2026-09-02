package com.atp.platform.seed;

import com.atp.common.enums.CaseType;
import com.atp.common.enums.UserRole;
import com.atp.common.model.TestCase;
import com.atp.platform.entity.SysUser;
import com.atp.platform.entity.TcCase;
import com.atp.platform.entity.TcStep;
import com.atp.platform.mapper.SysUserMapper;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcStepMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * 把 {@code seed/} 里的虚构语料灌进库。
 *
 * <p>80 条案例是**两条路线共同的存量数据**：激进路线拿它们当「相似案例」的检索源，
 * 保守路线的 CLI 往同一张表里追加新案例。其中 15 条带着刻意植入的规范违反 ——
 * 那是演示「这条可以参考，但它违反了 STD-004，别照抄」的素材。
 *
 * <p>⚠️ 导入是**幂等**的：按 case_id 存在即跳过。
 * 不做 drop-and-recreate —— 库是两条路线共用的，CLI 可能已经往里写了东西。
 */
@Slf4j
@Service
public class SeedImporter {

    private static final DateTimeFormatter SEED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private TcCaseMapper caseMapper;
    @Autowired
    private TcStepMapper stepMapper;
    @Autowired
    private SysUserMapper userMapper;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param seedDir seed 目录
     * @return 本次真正插入的案例条数（已存在的不计）
     */
    @Transactional
    public int importCases(Path seedDir) {
        Path caseDir = seedDir.resolve("cases");
        if (!Files.isDirectory(caseDir)) {
            throw new IllegalStateException("找不到种子目录 " + caseDir.toAbsolutePath()
                    + " —— 检查 atp.seed.dir 配置");
        }

        int inserted = 0;
        int skipped = 0;
        try (Stream<Path> files = Files.list(caseDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                TestCase seed = json.readValue(file.toFile(), TestCase.class);

                if (caseMapper.selectById(seed.caseId()) != null) {
                    skipped++;
                    continue;
                }
                caseMapper.insert(toCase(seed));
                stepMapper.insert(toStep(seed));
                inserted++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取种子案例失败", e);
        }

        log.info("种子案例导入完成：新增 {} 条，已存在跳过 {} 条", inserted, skipped);
        return inserted;
    }

    private TcCase toCase(TestCase seed) {
        TcCase entity = new TcCase();
        entity.setCaseId(seed.caseId());
        // 种子 JSON 里没有 case_type —— 存量案例全是 PC Web 的，老平台的默认值也是它
        entity.setCaseType(CaseType.PC_WEB);
        entity.setCaseCode(seed.caseCode());
        entity.setTitle(seed.title());
        entity.setModuleId(seed.moduleId());
        entity.setPriority(seed.priority());
        entity.setAuthor(seed.author());
        entity.setPrecondition(seed.precondition());
        entity.setStatus(seed.status());
        // ⚠️ seed.browser() 与 seed.timeoutSec() **刻意不写进表**：
        //    browser 是执行参数（exec_run / exec_task 才有），timeout_sec 没有消费方。
        //    种子 JSON 保留这两个字段只是因为它是历史语料的原样快照。
        entity.setVersion(0);
        entity.setCreatedAt(parse(seed.createdAt()));
        entity.setUpdatedAt(parse(seed.updatedAt()));
        return entity;
    }

    private TcStep toStep(TestCase seed) {
        TcStep entity = new TcStep();
        // 步骤表与案例一比一，主键直接派生 —— 重复导入时唯一约束会立刻挡下，不会写出孤儿行
        entity.setStepId(seed.caseId() + "-S");
        entity.setCaseId(seed.caseId());
        entity.setStepJson(writeSteps(seed));
        // 存量案例是已提交状态，不是 AI 编写中
        entity.setStatus((short) 1);
        entity.setVersion(0);
        entity.setUpdatedAt(parse(seed.updatedAt()));
        return entity;
    }

    private String writeSteps(TestCase seed) {
        try {
            return json.writeValueAsString(seed.stepsOrEmpty());
        } catch (IOException e) {
            throw new UncheckedIOException("序列化步骤失败：" + seed.caseCode(), e);
        }
    }

    private OffsetDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return OffsetDateTime.now();
        }
        return LocalDateTime.parse(text, SEED_TIME).atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /**
     * 演示用户。三个人来自前端设计稿：
     * 金城 悠人（QA 工程师，顶栏那个 KY）、佐藤 美咲、田中 直樹（审批中心里的提交人）。
     *
     * <p>⚠️ 口令是明文占位符，**只用于本地演示**。真做登录时换成 BCrypt。
     */
    @Transactional
    public int importUsers() {
        List<SysUser> users = List.of(
                // ⚠️ 金城在设计稿里的职位标签是「QA 工程师」，但他要在审批中心做决策，
                //    所以权限至少是 REVIEWER —— 职位标签与权限角色是两回事，别被 UI 文案带跑。
                //
                // ⭐ 给到 ADMIN 是因为凭证签发（client:manage）只发给 ADMIN：
                //    测试人员要用 atp CLI，得找项目经理要一对 client_id/secret，
                //    而这个演示环境里需要有一个人扮演那个角色。
                //    其余两位保持 REVIEWER —— 正好演示「不是谁都能签发凭证」。
                user("U001", "kaneshiro", "金城 悠人", UserRole.ADMIN, "KY"),
                user("U002", "sato", "佐藤 美咲", UserRole.REVIEWER, "SM"),
                user("U003", "tanaka", "田中 直樹", UserRole.REVIEWER, "TN"));

        int inserted = 0;
        for (SysUser u : users) {
            if (userMapper.selectById(u.getUserId()) == null) {
                userMapper.insert(u);
                inserted++;
            }
        }
        log.info("种子用户导入完成：新增 {} 个", inserted);
        return inserted;
    }

    private SysUser user(String id, String username, String displayName, UserRole role, String avatar) {
        SysUser u = new SysUser();
        u.setUserId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPasswordHash("demo");
        u.setRole(role);
        u.setAvatarText(avatar);
        u.setCreatedAt(OffsetDateTime.now());
        return u;
    }
}
