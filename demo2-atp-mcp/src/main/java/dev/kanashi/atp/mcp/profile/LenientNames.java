package dev.kanashi.atp.mcp.profile;

/**
 * 名称的宽松匹配键。
 * <p>
 * {@code caseName} / {@code case_name} / {@code Case-Name} / {@code CASE NAME}
 * 归一到同一个键，避免为同一个概念在字典里堆四份条目。
 * 中日文字符不受影响（它们没有大小写与分隔符问题）。
 */
public final class LenientNames {

    private LenientNames() {
    }

    /** 转小写并去掉下划线、连字符、点与所有空白。{@code null} 返回空串。 */
    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '_' || c == '-' || c == '.' || Character.isWhitespace(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
