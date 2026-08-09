package dev.kanashi.atp.mcp.profile;

import dev.kanashi.atp.mcp.domain.Action;

import java.util.Optional;

/**
 * 枚举值归一化，服务于 L1（确定性映射）。
 * <p>
 * 刻意区分了两类枚举，因为它们的难度完全不同：
 * <ul>
 *   <li>{@link #action(String)} —— action 有大量中日英同义词（点击 / click / tap / クリック），
 *       靠规则匹配不出来，必须有字典</li>
 *   <li>{@link #byName(Class, String)} —— 其余枚举（P0 / CHROME / ABORT …）本身就是符号，
 *       只需对大小写和分隔符宽容即可，不需要维护同义词表</li>
 * </ul>
 * 不给后者也配一份字典，是因为<b>没必要的字典就是没必要的维护负担</b>：
 * 它迟早和枚举对不上，而对不上的后果是调用方给的合法值被拒。
 */
public interface EnumNormalizer {

    /** 把任意语言/大小写的动作名归一为 {@link Action}；无法识别返回空（交给诊断，不猜）。 */
    Optional<Action> action(String raw);

    /**
     * 按名称宽松匹配枚举常量：忽略大小写、下划线、连字符与空白。
     * 例如 {@code "link text"} / {@code "LINK_TEXT"} / {@code "linkText"} 都能匹配到 {@code LINK_TEXT}。
     */
    <E extends Enum<E>> Optional<E> byName(Class<E> type, String raw);
}
