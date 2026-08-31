package com.atp.web.controller;

import com.atp.platform.service.ModuleDictService;
import com.atp.platform.service.ModuleDictService.ModuleEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模块字典 —— 给 {@code atp modules} 用。
 *
 * <p>补这个接口是为了合上凭证边界：{@code atp modules} 是最后一个只为读一份字典
 * 而直连 PostgreSQL 的命令。**只要还有一个命令直连 PG，CLI 就仍然需要数据库账号密码，
 * agent 那一层就仍然读得到** —— 写路径迁得再干净都没用，边界是二元的。
 *
 * <p>只读字典，没有任何敏感性，scope 归 {@code inspect}。
 */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    @Autowired
    private ModuleDictService service;

    @GetMapping
    public List<ModuleEntry> all() {
        return service.all();
    }

    /**
     * 该模块的下一个可用案例编号。
     *
     * <p>新建案例时用它，**不要自己按「已有条数 + 1」推** —— 中间删过一条就会撞号，
     * 而撞号的表现是提交时 {@code uk_case_code} 报错，离「编号推错了」这个原因很远。
     */
    @GetMapping("/{moduleId}/next-case-code")
    public Map<String, String> nextCaseCode(@PathVariable String moduleId) {
        return Map.of("caseCode", service.nextCaseCode(moduleId));
    }
}
