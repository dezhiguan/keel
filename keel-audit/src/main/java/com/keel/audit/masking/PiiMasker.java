package com.keel.audit.masking;

import java.util.regex.Pattern;

/** Masks phone, Chinese ID, and bank card numbers that remain inside allow-listed fields. */
public final class PiiMasker {
    private static final Pattern BANK = Pattern.compile("(?<!\\d)(\\d{16,19})(?!\\d)");
    private static final Pattern ID = Pattern.compile("(?<!\\d)(\\d{17}[\\dXx])(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    private PiiMasker() {}

    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        String masked = ID.matcher(value).replaceAll(match -> keep(match.group(), 3, 4));
        masked = BANK.matcher(masked).replaceAll(match -> keep(match.group(), 4, 4));
        return PHONE.matcher(masked).replaceAll(match -> keep(match.group(), 3, 4));
    }

    private static String keep(String raw, int head, int tail) {
        if (raw.length() <= head + tail) {
            return raw;
        }
        return raw.substring(0, head) + "*".repeat(raw.length() - head - tail) + raw.substring(raw.length() - tail);
    }
}
