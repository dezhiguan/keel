package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import java.util.LinkedHashMap;
import java.util.Map;

final class DevflowJobPages {
    static Map<String,Object> page(DevflowTypes.JobList board, String agent, String cursor, Integer limit) {
        var out = new LinkedHashMap<String,Object>();
        out.put("summary",board.summary());
        if (agent == null) { out.put("items",board.items()); return out; }
        int size = limit == null ? 10 : limit;
        if (agent.isBlank() || size < 1 || size > 50) throw invalid();
        var rows = board.items().stream().filter(job -> agent.equals(job.targetAgent())).toList();
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            while (start < rows.size() && !cursor.equals(rows.get(start).jobId())) start++;
            if (start == rows.size()) throw invalid();
            start++;
        }
        var end = Math.min(rows.size(), start + size);
        var page = rows.subList(start,end);
        out.put("items",page);
        out.put("nextCursor",end < rows.size() ? page.getLast().jobId() : null);
        return out;
    }
    private static KeelException invalid() { return new KeelException(ErrorCode.SERVER_INVALID_PARAM,"研发记录分页参数无效，请重新加载"); }
}
