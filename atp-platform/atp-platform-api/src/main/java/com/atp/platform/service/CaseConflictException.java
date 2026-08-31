package com.atp.platform.service;

/**
 * 编辑期的并发冲突。
 *
 * <p>⚠️ 这是 **409**，不是 400：请求本身没错，是状态在你拿到版本之后变了。
 * 报错必须说清「库里是哪个版本、你手上是哪个」—— 只说「操作失败」的话，
 * 用户只会再点一次，而再点一次仍然会失败。
 *
 * <h3>⭐ 两种冲突的处置完全相反，所以必须机器可分辨</h3>
 *
 * <ul>
 *   <li>{@link Kind#VERSION} 版本不对 → **重来一遍**：重新拉取、让用户再确认一次</li>
 *   <li>{@link Kind#STATE} 状态不对（已提交过）→ **别再试了**：重试多少次都一样，停下问人</li>
 * </ul>
 *
 * <p>早先两种都只是 {@code CaseConflictException(String)}，出了 HTTP 全是 409，
 * **区别只剩中文文案**。调用方要区分就只能匹配「版本不一致」这四个字 ——
 * 那等于把一个机器契约挂在人类文案上，哪天改个措辞对方就静默错判，而且测试全绿。
 *
 * <p>现在 {@code kind} 会被 handler 映射成 RFC 7807 的 {@code type} URI。
 * **`type` 存在的理由正是「机器可读的问题分类」**，这不是本项目发明的约定。
 * 这样对方按 type 分派，我改文案不影响它。
 */
public class CaseConflictException extends RuntimeException {

    public enum Kind {
        /** 版本对不上 —— 内容在你确认之后被改过 */
        VERSION("version-conflict"),
        /** 状态不允许 —— 比如已经提交过了 */
        STATE("state-conflict");

        private final String slug;

        Kind(String slug) {
            this.slug = slug;
        }

        public String slug() {
            return slug;
        }
    }

    private final Kind kind;

    public CaseConflictException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
