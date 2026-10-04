package com.keel.audit.query;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Export stays pending until an approval callback writes the file. */
public class ExportService {
    private final ApprovalClient approvals;
    private final Map<String, Export> exports = new ConcurrentHashMap<>();

    public ExportService(ApprovalClient approvals) {
        this.approvals = approvals;
    }

    public Export request(Map<String, Object> filter) {
        String approvalId = approvals.create("data.export", filter);
        var export = new Export(UUID.randomUUID().toString(), approvalId, "pending", null);
        exports.put(export.exportId(), export);
        return export;
    }

    public Export find(String exportId) {
        return exports.get(exportId);
    }

    /** Called by the approval callback. Tests invoke this directly. */
    public Export onApproved(String exportId, String body) {
        var current = exports.get(exportId);
        if (current == null) {
            return null;
        }
        var ready = new Export(current.exportId(), current.approvalId(), "ready", "exports/" + exportId + ".json");
        ready.body = body;
        exports.put(exportId, ready);
        return ready;
    }

    public interface ApprovalClient {
        String create(String subjectType, Map<String, Object> filter);
    }

    public static final class Export {
        private final String exportId;
        private final String approvalId;
        private final String status;
        private final String filePath;
        private String body;

        Export(String exportId, String approvalId, String status, String filePath) {
            this.exportId = exportId;
            this.approvalId = approvalId;
            this.status = status;
            this.filePath = filePath;
        }

        public String exportId() { return exportId; }
        public String approvalId() { return approvalId; }
        public String status() { return status; }
        public String filePath() { return filePath; }
        public String body() { return body; }
    }
}
