package com.atp.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 凭据的哈希与比较。**人的密码和机器的 secret 共用这一份** ——
 * 两处各写一份的话，将来换算法必然漏掉一边。
 */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /** SHA-256(salt + 明文) */
    public static String hash(String salt, String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest((salt + plain).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * 定长比较。
     *
     * <p>{@code equals} 会在第一个不同的字符处提前返回，耗时随「猜对了几位」变化 ——
     * 这是可测量的信息泄露。这里的字符串很短、调用不频繁，实际难以利用，
     * 但没有理由不做对。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
