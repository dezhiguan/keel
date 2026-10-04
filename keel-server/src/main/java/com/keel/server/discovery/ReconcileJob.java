package com.keel.server.discovery;

import java.util.ArrayList;
import java.util.List;

/** Classifies reconcile inputs. Persistence and the five-minute schedule land with the finding store. */
public class ReconcileJob {
    public List<ReconcileRules.Kind> scan(List<ReconcileRules.Input> inputs) {
        var kinds = new ArrayList<ReconcileRules.Kind>();
        for (var input : inputs) {
            ReconcileRules.classify(input).ifPresent(kinds::add);
        }
        return kinds;
    }
}
