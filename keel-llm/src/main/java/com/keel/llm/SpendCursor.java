package com.keel.llm;

import com.keel.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Newest-first pages. A cursor stays put when a newer row is inserted. */
public final class SpendCursor {
    private SpendCursor() {}

    public record Page(List<Gateway.Spend> rows, String nextCursor) {}

    public record Mark(Instant ts, String attemptId) {}

    public static Page page(List<Gateway.Spend> rows, String alias, String cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        var mark = decode(cursor);
        var matched = rows.stream()
                .filter(row -> alias == null || alias.isBlank() || alias.equals(row.alias()))
                .filter(row -> mark == null || older(row, mark))
                .sorted(Comparator.comparing(Gateway.Spend::ts).reversed()
                        .thenComparing(Comparator.comparing(Gateway.Spend::attemptId).reversed()))
                .toList();
        var page = new ArrayList<Gateway.Spend>();
        for (var row : matched) {
            if (page.size() == size) {
                break;
            }
            page.add(row);
        }
        String next = null;
        if (matched.size() > page.size() && !page.isEmpty()) {
            var last = page.getLast();
            next = encode(last.ts(), last.attemptId());
        }
        return new Page(List.copyOf(page), next);
    }

    public static String encode(Instant ts, String attemptId) {
        var raw = ts.toEpochMilli() + "\n" + (attemptId == null ? "" : attemptId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Mark decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var split = raw.indexOf('\n');
            if (split < 1) {
                throw new IllegalArgumentException(cursor);
            }
            return new Mark(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, split))), raw.substring(split + 1));
        } catch (RuntimeException e) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "账单游标无法识别");
        }
    }

    private static boolean older(Gateway.Spend row, Mark mark) {
        int ts = row.ts().compareTo(mark.ts());
        if (ts < 0) {
            return true;
        }
        if (ts > 0) {
            return false;
        }
        return row.attemptId().compareTo(mark.attemptId()) < 0;
    }
}
