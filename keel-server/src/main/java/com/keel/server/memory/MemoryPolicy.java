package com.keel.server.memory;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

/** Who may write user memory. Session text stays in keel-lite. */
public final class MemoryPolicy {
    private MemoryPolicy() {}

    public static void requireWritable(String agentLayer, String agentName, String evalRunId) {
        if (evalRunId != null && !evalRunId.isBlank()) {
            throw new KeelException(ErrorCode.MEMORY_WRITE_DENIED, "评测流量禁止写入记忆");
        }
        if ("META".equals(agentLayer) || "meta-agent".equals(agentName)) {
            throw new KeelException(ErrorCode.MEMORY_WRITE_DENIED, ErrorCode.MEMORY_WRITE_DENIED.message());
        }
    }
}
