package com.keel.audit.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archives a partition by recording its sha256 and the object-store bytes.
 * Verification reads the file back. This class does not delete audit_event rows.
 */
public class ArchiveJob {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> digests = new ConcurrentHashMap<>();

    public String detach(String agent, String month, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String key = agent + "/" + month;
        objects.put(key, bytes);
        String digest = sha256(bytes);
        digests.put(key, digest);
        return digest;
    }

    public void replaceObject(String agent, String month, String content) {
        objects.put(agent + "/" + month, content.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<String> brokenMonth(String agent, String month) {
        String key = agent + "/" + month;
        byte[] file = objects.get(key);
        if (file == null) {
            return Optional.of(month);
        }
        if (!sha256(file).equals(digests.get(key))) {
            return Optional.of(month);
        }
        return Optional.empty();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
