package com.snowworld.review;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 个人信息脱敏: 手机号、身份证号、邮箱。用于评价文本、作者名、发送给模型的内容与工具参数。 */
public final class PiiMasker {

    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9])([1-9]\\d{5})(\\d{8})(\\d{3}[0-9Xx])(?![0-9])");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(1[3-9]\\d)(\\d{4})(\\d{4})(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = replace(ID_CARD.matcher(text), m -> m.group(1).substring(0, 3) + "*".repeat(13) + m.group(3).substring(2));
        s = replace(PHONE.matcher(s), m -> m.group(1) + "****" + m.group(3));
        s = replace(EMAIL.matcher(s), m -> m.group(1) + "***" + m.group(2));
        return s;
    }

    /** 作者昵称只保留首字。 */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = mask(name.strip());
        int first = n.codePointAt(0);
        return new String(Character.toChars(first)) + "**";
    }

    private static String replace(Matcher m, java.util.function.Function<Matcher, String> fn) {
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
