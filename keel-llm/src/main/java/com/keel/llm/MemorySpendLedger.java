package com.keel.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class MemorySpendLedger implements SpendLedger {
    private final List<Gateway.Spend> rows = new ArrayList<>();

    @Override
    public synchronized void append(Gateway.Spend row) {
        rows.add(row);
    }

    @Override
    public synchronized SpendCursor.Page page(String alias, String cursor, int limit) {
        return SpendCursor.page(List.copyOf(rows), alias, cursor, limit);
    }

    @Override
    public synchronized List<Gateway.Spend> latestSettledByModel() {
        return newestSettled(rows);
    }

    static List<Gateway.Spend> newestSettled(List<Gateway.Spend> rows) {
        var newest = new LinkedHashMap<String, Gateway.Spend>();
        for (var row : rows) {
            if (!"SETTLED".equals(row.status())) {
                continue;
            }
            var current = newest.get(row.model());
            if (current == null || row.ts().isAfter(current.ts())) {
                newest.put(row.model(), row);
            }
        }
        return List.copyOf(newest.values());
    }
}
