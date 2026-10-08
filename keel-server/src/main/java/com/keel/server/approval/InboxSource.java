package com.keel.server.approval;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

/** source=devflow keeps only rows that belong to a research job. Anything else is rejected. */
public final class InboxSource {
    private InboxSource() {}

    /** Empty string means no filter. "1" means devflow_job_id must be present. */
    public static String flag(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        if ("devflow".equals(source)) {
            return "1";
        }
        throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
    }
}
