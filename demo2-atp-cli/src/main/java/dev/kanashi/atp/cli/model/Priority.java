package dev.kanashi.atp.cli.model;

/** 案例优先级。DB 里存 SMALLINT，码值与 P 后面的数字一致。 */
public enum Priority {

    P0(0),
    P1(1),
    P2(2),
    P3(3);

    private final int code;

    Priority(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Priority fromCode(int code) {
        for (Priority p : values()) {
            if (p.code == code) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知的 priority 码: " + code);
    }
}
