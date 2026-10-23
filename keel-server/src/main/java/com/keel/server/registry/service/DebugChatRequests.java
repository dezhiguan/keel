package com.keel.server.registry.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Durable admission for paid chat turns. Unknown outcomes are never automatically re-executed. */
@Service
public class DebugChatRequests {
    private final JdbcTemplate jdbc;

    public DebugChatRequests(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Claim(String agent, String env, String owner, String session, String request, String replay) {}

    public Claim begin(String agent, String env, String owner, String session, String request, String text) {
        if (request == null || request.isBlank()) return null; // Existing clients without a stable ID remain supported.
        if (!request.matches("[A-Za-z0-9_-]{1,128}")) throw invalid("requestId 格式不正确");
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        jdbc.update("DELETE FROM debug_chat_request WHERE expires_at <= now()");
        int inserted = jdbc.update("""
                INSERT INTO debug_chat_request(agent_name,env,owner_user,session_id,request_id,input_digest,state)
                VALUES (?,?,?,?,?,?,'RUNNING') ON CONFLICT DO NOTHING
                """, agent, env, owner, session, request, digest);
        if (inserted == 1) return new Claim(agent, env, owner, session, request, null);
        var rows = jdbc.query("""
                SELECT input_digest,state,terminal_frame FROM debug_chat_request
                WHERE agent_name=? AND env=? AND owner_user=? AND session_id=? AND request_id=?
                """, (rs,n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, agent,env,owner,session,request);
        if (rows.isEmpty()) throw invalid("请求记录已变化，请重新查询原请求状态");
        var row = rows.getFirst();
        if (!digest.equals(row[0])) throw invalid("同一 requestId 的输入内容不同，已拒绝重复请求");
        if ("COMPLETE".equals(row[1]) && row[2] != null)
            return new Claim(agent, env, owner, session, request, row[2]);
        throw invalid("RUNNING".equals(row[1])
                ? "原请求正在执行或结果尚未确认，未重新执行；请稍后用同一 requestId 查询"
                : "原请求结果未知，可能已经产生费用；未重新执行，请先核对原请求");
    }

    public void finish(Claim claim, String frame) {
        if (claim == null || claim.replay() != null) return;
        jdbc.update("""
                UPDATE debug_chat_request SET state=?,terminal_frame=?,updated_at=now()
                WHERE agent_name=? AND env=? AND owner_user=? AND session_id=? AND request_id=? AND state='RUNNING'
                """, frame == null ? "UNKNOWN" : "COMPLETE", frame,
                claim.agent(),claim.env(),claim.owner(),claim.session(),claim.request());
    }

    private static KeelException invalid(String message) { return new KeelException(ErrorCode.SERVER_INVALID_PARAM,message); }
}
