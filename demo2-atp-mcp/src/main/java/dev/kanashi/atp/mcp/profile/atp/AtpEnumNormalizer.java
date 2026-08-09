package dev.kanashi.atp.mcp.profile.atp;

import dev.kanashi.atp.mcp.domain.Action;
import dev.kanashi.atp.mcp.profile.EnumNormalizer;
import dev.kanashi.atp.mcp.profile.LenientNames;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ATP 的枚举归一化。
 */
class AtpEnumNormalizer implements EnumNormalizer {

    private static final Map<String, Action> ACTIONS = actionSynonyms();

    @Override
    public Optional<Action> action(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ACTIONS.get(LenientNames.key(raw)));
    }

    @Override
    public <E extends Enum<E>> Optional<E> byName(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = LenientNames.key(raw);
        return Arrays.stream(type.getEnumConstants())
                .filter(c -> LenientNames.key(c.name()).equals(key))
                .findFirst();
    }

    /**
     * action 同义词表，中日英。
     * <p>
     * <b>{@code SLEEP} 必须收录，尽管 STD-004 禁止它</b> —— 识别得出来，才能给出
     * "使用了被禁止的 SLEEP，请改用 wait_strategy" 这种能指导修改的诊断；
     * 识别不出来就只能报"无法识别的 action"，把一个明确的规范问题
     * 降级成一句无用的错误信息。<b>能诊断的前提是能识别。</b>
     */
    private static Map<String, Action> actionSynonyms() {
        Map<String, Action> m = new HashMap<>();

        put(m, Action.OPEN_URL,      "openUrl", "open", "navigate", "goto", "visit", "load",
                                     "打开", "打开页面", "访问", "跳转",
                                     "開く", "ページを開く", "遷移", "アクセス");
        put(m, Action.CLICK,         "click", "tap", "press", "clickOn",
                                     "点击", "单击", "点选",
                                     "クリック", "押下", "タップ", "押す");
        put(m, Action.INPUT,         "input", "type", "fill", "enter", "sendKeys", "setValue",
                                     "输入", "填写", "录入",
                                     "入力", "入力する", "記入");
        put(m, Action.SELECT,        "select", "choose", "dropdown", "selectOption",
                                     "选择", "下拉选择", "选取",
                                     "選択", "プルダウン", "選ぶ");
        put(m, Action.ASSERT_TEXT,   "assertText", "verifyText", "checkText", "shouldHaveText",
                                     "断言文本", "验证文本", "校验文本",
                                     "テキスト検証", "文言確認", "文字列検証");
        put(m, Action.ASSERT_VISIBLE, "assertVisible", "verifyVisible", "checkVisible",
                                     "shouldBeVisible", "assertDisplayed",
                                     "断言可见", "验证显示", "校验可见",
                                     "表示確認", "表示検証");
        put(m, Action.ASSERT_NOT_EXIST, "assertNotExist", "verifyNotExist", "assertAbsent",
                                     "shouldNotExist", "assertNotPresent",
                                     "断言不存在", "验证不存在", "校验不存在",
                                     "非表示確認", "存在しないこと", "非存在検証");
        put(m, Action.WAIT_FOR,      "waitFor", "wait", "waitUntil", "waitElement",
                                     "等待", "显式等待", "等待元素",
                                     "待機", "要素待機", "待つ");
        put(m, Action.SCROLL_TO,     "scrollTo", "scroll", "scrollIntoView",
                                     "滚动", "滚动到", "滚动至",
                                     "スクロール", "スクロールする");
        put(m, Action.SWITCH_FRAME,  "switchFrame", "switchToFrame", "frame", "iframe",
                                     "切换frame", "切换iframe", "进入frame",
                                     "フレーム切替", "フレーム切り替え");
        put(m, Action.SWITCH_WINDOW, "switchWindow", "switchToWindow", "window", "switchTab", "tab",
                                     "切换窗口", "切换标签", "切换标签页",
                                     "ウィンドウ切替", "タブ切替");
        put(m, Action.UPLOAD,        "upload", "uploadFile", "attachFile",
                                     "上传", "上传文件",
                                     "アップロード", "ファイル添付");
        // ⛔ STD-004 禁止，但仍需识别 —— 见方法注释
        put(m, Action.SLEEP,         "sleep", "pause", "delay", "hardWait", "staticWait",
                                     "硬等待", "固定等待", "强制等待",
                                     "スリープ", "固定待機");

        return Map.copyOf(m);
    }

    private static void put(Map<String, Action> target, Action action, String... synonyms) {
        target.put(LenientNames.key(action.name()), action);
        for (String synonym : synonyms) {
            String key = LenientNames.key(synonym);
            Action previous = target.put(key, action);
            if (previous != null && previous != action) {
                // 同义词表自相矛盾时必须启动期就炸：否则归一化结果取决于字典构造顺序，
                // 表现为"同样的输入偶尔被识别成另一个 action"，且没有任何报错。
                throw new IllegalStateException(
                        "action 同义词冲突：'" + synonym + "' 同时映射到 " + previous + " 与 " + action);
            }
        }
    }
}
