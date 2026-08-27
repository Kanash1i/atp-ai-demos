package dev.kanashi.atp.cli.model;

/** 执行平台。老平台本来就按 iOS / Android / Web 区分案例。DB 里存 SMALLINT。 */
public enum CaseType {

    IOS(1),
    ANDROID(2),
    PC_WEB(3);

    private final int code;

    CaseType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static CaseType fromCode(int code) {
        for (CaseType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的 case_type 码: " + code);
    }
}
