package com.atp.web.controller;

import com.atp.platform.mapper.SysUserMapper;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcModuleMapper;
import com.atp.platform.mapper.TcProjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 *
 * <p>⚠️ 不只返回 {@code {"status":"UP"}} —— 那种健康检查在这个项目里已经骗过人一次
 * （TEI 服务 health 200、API 正常返回 1024 维向量，实际却跑在 CPU 上）。
 * 这里直接把各表的行数报出来：连得上库、表在、数据在，一眼能看全。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Autowired
    private TcProjectMapper projectMapper;
    @Autowired
    private TcModuleMapper moduleMapper;
    @Autowired
    private TcCaseMapper caseMapper;
    @Autowired
    private SysUserMapper userMapper;

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("tc_project", projectMapper.selectCount(null));
        counts.put("tc_module", moduleMapper.selectCount(null));
        counts.put("tc_case", caseMapper.selectCount(null));
        counts.put("sys_user", userMapper.selectCount(null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("counts", counts);
        return body;
    }
}
