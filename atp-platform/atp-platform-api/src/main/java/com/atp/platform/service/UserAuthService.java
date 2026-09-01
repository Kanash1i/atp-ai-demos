package com.atp.platform.service;

import com.atp.common.enums.ApiScope;
import com.atp.common.enums.UserRole;
import com.atp.common.util.Secrets;
import com.atp.platform.entity.SysUser;
import com.atp.platform.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 人的登录。机器主体的那套在 {@link ApiClientService}。
 *
 * <h3>为什么人和机器要分开，而不是给 agent 发一个「机器人账号」</h3>
 *
 * 分开之后，审批记录里的 {@code decided_by} 一定是人，案例的 {@code created_by}
 * 能看出是人写的还是 agent 写的。合成一个的话，「谁做的」这个问题在审计上就没法回答了 ——
 * 而这个平台的核心叙事之一正是「AI 介入之后，责任边界在哪」。
 *
 * <p>具体差别在 scope 上：{@link ApiScope#APPROVAL_DECIDE} **只发给人**。
 * agent 能写案例、能自验，但「这条变更该不该放行」是人的判断 ——
 * 发给机器等于让 agent 自己批准自己提交的东西。
 */
@Slf4j
@Service
public class UserAuthService {

    @Autowired
    private SysUserMapper userMapper;

    /**
     * 演示账号的统一口令。
     *
     * <p>⚠️ 这是**虚构演示数据**，不是真实凭据。配置在 {@code .env}，
     * 不硬编码在代码里 —— 即便是 demo，把口令写进源码也是个会被复制的坏习惯。
     */
    @Value("${atp.auth.demo-password}")
    private String demoPassword;

    /**
     * 首次启动时把演示账号的密码种进去。
     *
     * <p>迁移里只清空了 M1 留下的明文占位（{@code password_hash = 'demo'}），
     * 没有写 hash —— 因为那样改口令就要改迁移文件，而迁移是不该被修改的历史。
     */
    @PostConstruct
    void seedDemoPasswords() {
        List<SysUser> pending = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getPasswordHash, ""));
        if (pending.isEmpty()) {
            return;
        }
        for (SysUser u : pending) {
            String salt = Secrets.randomHex(16);
            u.setPasswordSalt(salt);
            u.setPasswordHash(Secrets.hash(salt, demoPassword));
            userMapper.updateById(u);
        }
        log.info("[AUTH] 已为 {} 个演示账号种入口令（来自 atp.auth.demo-password）", pending.size());
    }

    /**
     * 校验用户名密码。
     *
     * @return 通过时返回该用户；不通过返回 empty
     */
    public Optional<SysUser> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        SysUser u = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));

        // ⚠️ 「用户不存在」和「密码不对」必须是同一个结果 ——
        //    分开返回等于告诉试探者哪些用户名是存在的
        if (u == null || !Secrets.constantTimeEquals(
                Secrets.hash(u.getPasswordSalt(), password), u.getPasswordHash())) {
            log.warn("[AUTH] 登录失败：{}", username);
            return Optional.empty();
        }
        return Optional.of(u);
    }

    /** 取权限。给 Sa-Token 的 StpInterface 用，每次现查库 —— 改了角色立刻生效 */
    public List<String> permissionsOf(String userId) {
        SysUser u = userMapper.selectById(userId);
        if (u == null) {
            return List.of();
        }
        List<String> scopes = new ArrayList<>();
        // 人都能写案例 —— 这个平台上「写案例」本来就是测试工程师的日常工作
        scopes.add(ApiScope.CASE_WRITE.code());
        scopes.add(ApiScope.INSPECT.code());
        scopes.add(ApiScope.EXEC_RUN_ONCE.code());
        // ⭐ 只有审阅者以上能决策审批
        if (u.getRole() == UserRole.REVIEWER || u.getRole() == UserRole.ADMIN) {
            scopes.add(ApiScope.APPROVAL_DECIDE.code());
        }
        return scopes;
    }

    public boolean canApprove(SysUser u) {
        return u.getRole() == UserRole.REVIEWER || u.getRole() == UserRole.ADMIN;
    }
}
