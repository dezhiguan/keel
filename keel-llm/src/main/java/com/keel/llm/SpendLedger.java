package com.keel.llm;

import java.util.List;

/** Full bill history. The recent Redis log stays for the pending-row lookup. */
public interface SpendLedger {
    void append(Gateway.Spend row);

    SpendCursor.Page page(String alias, String cursor, int limit);

    List<Gateway.Spend> latestSettledByModel();
}
