package com.keel.server.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Cosine recall. The current Postgres image has no pgvector extension, so the vector stays JSON. */
public final class MemoryRecall {
    private MemoryRecall() {}

    public static double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public static List<Integer> nearest(List<Double> query, List<List<Double>> rows) {
        var ranked = new ArrayList<int[]>();
        for (int i = 0; i < rows.size(); i++) {
            ranked.add(new int[]{i, 0});
            ranked.get(i)[1] = (int) Math.round(cosine(query, rows.get(i)) * 1_000_000);
        }
        ranked.sort(Comparator.<int[]>comparingInt(pair -> pair[1]).reversed());
        var order = new ArrayList<Integer>();
        for (var pair : ranked) {
            if (pair[1] <= 0) {
                continue;
            }
            order.add(pair[0]);
        }
        return order;
    }
}
