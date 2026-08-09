package dev.kanashi.atp.mcp.profile.atp;

import dev.kanashi.atp.mcp.domain.Action;
import dev.kanashi.atp.mcp.domain.Browser;
import dev.kanashi.atp.mcp.domain.CaseStatus;
import dev.kanashi.atp.mcp.domain.LocatorType;
import dev.kanashi.atp.mcp.domain.OnFailure;
import dev.kanashi.atp.mcp.domain.Priority;
import dev.kanashi.atp.mcp.domain.WaitStrategy;
import dev.kanashi.atp.mcp.profile.AliasDictionary;
import dev.kanashi.atp.mcp.profile.Enforcement;
import dev.kanashi.atp.mcp.profile.EnumNormalizer;
import dev.kanashi.atp.mcp.profile.ModuleEntry;
import dev.kanashi.atp.mcp.profile.PlatformProfile;
import dev.kanashi.atp.mcp.profile.StandardRule;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ATP 平台的 profile。共享契约 §1.2~§1.5 的代码化。
 * <p>
 * 字典与规范在此处硬编码是<b>刻意的</b>：本服务不碰 DB（交接文档 §1 的边界），
 * 而这些内容属于平台契约而非运行时数据 —— 它们变更时本就该走代码评审和发版，
 * 而不是被谁在某张表里悄悄改掉。
 */
@Component
public class AtpProfile implements PlatformProfile {

    private static final String SCHEMA_RESOURCE = "schema/tc_case.schema.json";

    /** 共享契约 §1.4，固定 8 个。模块名故意做成日中双语，贴合中日英混杂的真实文档处境。 */
    private static final List<ModuleEntry> MODULES = List.of(
            new ModuleEntry("M001", "LOGIN",   "ログイン / 登录认证"),
            new ModuleEntry("M002", "SEARCH",  "検索 / 商品搜索"),
            new ModuleEntry("M003", "CART",    "カート / 购物车"),
            new ModuleEntry("M004", "ORDER",   "注文 / 订单管理"),
            new ModuleEntry("M005", "USER",    "ユーザー管理 / 用户中心"),
            new ModuleEntry("M006", "PAYMENT", "決済 / 支付"),
            new ModuleEntry("M007", "REPORT",  "レポート / 报表导出"),
            new ModuleEntry("M008", "ADMIN",   "管理画面 / 后台管理"));

    /** 共享契约 §1.5。enforcement 列体现了"能用规则做的绝不给模型"。 */
    private static final List<StandardRule> STANDARDS = List.of(
            new StandardRule("STD-001",
                    "XPath 禁止使用绝对路径（如 /html/body/div[3]/...）—— 页面结构一变全线失效",
                    Enforcement.ERROR),
            new StandardRule("STD-002",
                    "XPath 禁止依赖自动生成的动态 id（如 id=\"ext-gen1234\"）—— 每次渲染都可能不同",
                    Enforcement.WARN),
            new StandardRule("STD-003",
                    "XPath 优先使用稳定属性，优先级：data-testid > name > class > 文本",
                    Enforcement.INFO),
            new StandardRule("STD-004",
                    "禁止 SLEEP 硬等，必须用 wait_strategy 声明等待条件",
                    Enforcement.ERROR),
            new StandardRule("STD-005",
                    "CLICK 的 wait_strategy 必须是 CLICKABLE",
                    Enforcement.AUTO_FILL),
            new StandardRule("STD-006",
                    "ASSERT_* 的 wait_strategy 必须是 VISIBLE"
                  + "（ASSERT_NOT_EXIST 因语义冲突取 NONE，偏离会在诊断中显式标注）",
                    Enforcement.AUTO_FILL),
            new StandardRule("STD-007",
                    "case_code 必须符合 ATP-{MODULE}-{4位序号}；序号需全局唯一，由平台分配",
                    Enforcement.AUTO_FILL),
            new StandardRule("STD-008",
                    "每条案例至少包含 1 个断言步骤（ASSERT_*）—— 没有断言的用例不构成测试",
                    Enforcement.ERROR));

    private final JsonNode targetSchema;
    private final Map<String, ModuleEntry> modulesById;
    private final Map<String, ModuleEntry> modulesByCode;
    private final AliasDictionary aliases = new AtpAliasDictionary();
    private final EnumNormalizer enumNormalizer = new AtpEnumNormalizer();

    public AtpProfile(ObjectMapper objectMapper) {
        this.targetSchema = loadSchema(objectMapper);
        this.modulesById = MODULES.stream()
                .collect(Collectors.toMap(ModuleEntry::moduleId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        this.modulesByCode = MODULES.stream()
                .collect(Collectors.toMap(m -> m.moduleCode().toUpperCase(), Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 启动期加载目标 schema。
     * <p>
     * 加载失败直接让应用起不来，<b>而不是留一个空 schema 继续跑</b> ——
     * 空 schema 会让 L4 校验形同虚设：每条案例都"通过"，
     * 于是本服务最重要的那条安全不变式在无人察觉的情况下失效。
     * 这正是本项目反复强调的静默失败形态，宁可启动就炸。
     */
    private static JsonNode loadSchema(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "目标 schema 加载失败，classpath 资源缺失或不是合法 JSON: " + SCHEMA_RESOURCE, e);
        }
    }

    @Override
    public String id() {
        return "atp";
    }

    @Override
    public String displayName() {
        return "ATP (Automation Test Platform)";
    }

    @Override
    public JsonNode targetSchema() {
        return targetSchema;
    }

    @Override
    public List<ModuleEntry> modules() {
        return MODULES;
    }

    @Override
    public boolean isKnownModuleId(String moduleId) {
        return moduleId != null && modulesById.containsKey(moduleId);
    }

    @Override
    public Optional<ModuleEntry> resolveModule(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        String trimmed = idOrCode.trim();
        ModuleEntry byId = modulesById.get(trimmed);
        if (byId != null) {
            return Optional.of(byId);
        }
        // module_code 在案例里常被大小写混写（cart / Cart / CART），统一后再查
        return Optional.ofNullable(modulesByCode.get(trimmed.toUpperCase()));
    }

    @Override
    public AliasDictionary aliases() {
        return aliases;
    }

    @Override
    public EnumNormalizer enumNormalizer() {
        return enumNormalizer;
    }

    @Override
    public List<StandardRule> standards() {
        return STANDARDS;
    }

    /**
     * 枚举字典由 Java 枚举反射生成，不手写。
     * <p>
     * 手写一份的话，改了枚举忘了改字典，调用方就会按字典生成一个服务其实不接受的值 ——
     * 而且这种不一致在测试里很难被发现，因为两边各自都"自洽"。
     * key 用 snake_case，与 DB 列名及 tc_case.schema.json 保持一致。
     */
    @Override
    public Map<String, List<String>> enumDictionary() {
        Map<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.put("priority", constantsOf(Priority.class));
        dictionary.put("status", constantsOf(CaseStatus.class));
        dictionary.put("browser", constantsOf(Browser.class));
        dictionary.put("action", constantsOf(Action.class));
        dictionary.put("locator_type", constantsOf(LocatorType.class));
        dictionary.put("wait_strategy", constantsOf(WaitStrategy.class));
        dictionary.put("on_failure", constantsOf(OnFailure.class));
        return dictionary;
    }

    private static List<String> constantsOf(Class<? extends Enum<?>> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();
    }
}
