package com.xiaomi.education.ai.model;

import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long cjk = text.codePoints().filter(this::isCjk).count();
        long other = text.codePoints().filter(codePoint -> !isCjk(codePoint) && !Character.isWhitespace(codePoint)).count();
        return Math.max(1, Math.toIntExact(cjk + Math.round(other / 4.0)));
    }

    private boolean isCjk(int codePoint) {
        var block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}
