package com.keel.server.release;

/** A devflow-produced agent may ship only after a person approved git.pr.merge. */
public final class ReleaseGuard {
    private ReleaseGuard() {}

    /** Null when the release may continue. */
    public static String rejection(String devflowJobId, boolean approvedMerge) {
        if (devflowJobId == null || devflowJobId.isBlank() || approvedMerge) {
            return null;
        }
        return "由智能体生产的员工发布必须有已批准的 git.pr.merge 审批单";
    }
}
