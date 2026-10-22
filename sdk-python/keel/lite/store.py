"""SQLite persistence for local audit and approvals."""

import json
import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from keel._generated.audit import AuditEvent

from .chain import chain_hash


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class LiteStore:
    def __init__(self, project_root: str | Path = "."):
        self.path = Path(project_root).resolve() / ".keel" / "dev.db"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS audit_event (
                    event_id TEXT PRIMARY KEY, ts TEXT NOT NULL, agent TEXT NOT NULL,
                    env TEXT NOT NULL, action TEXT NOT NULL, resource TEXT, risk TEXT NOT NULL,
                    decision TEXT NOT NULL, payload_json TEXT NOT NULL, input_digest TEXT,
                    prev_hash TEXT NOT NULL, hash TEXT NOT NULL, event_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS audit_chain_head (
                    agent TEXT PRIMARY KEY, last_event_id TEXT NOT NULL,
                    last_hash TEXT NOT NULL, count INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS approval_request (
                    id TEXT PRIMARY KEY, subject_type TEXT NOT NULL, subject_ref TEXT NOT NULL,
                    agent_name TEXT NOT NULL, run_id TEXT, checkpoint_ref TEXT,
                    resume_token TEXT, summary TEXT NOT NULL, status TEXT NOT NULL,
                    decided_by TEXT, decided_at TEXT, created_at TEXT NOT NULL,
                    expires_at TEXT, trace_id TEXT, actor_user TEXT, risk TEXT NOT NULL,
                    payload_digest TEXT, resumed_at TEXT
                );
                CREATE INDEX IF NOT EXISTS ix_approval_status ON approval_request(status, created_at);
                CREATE TABLE IF NOT EXISTS session_message (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    agent TEXT NOT NULL, session_id TEXT NOT NULL,
                    role TEXT NOT NULL, content TEXT NOT NULL,
                    created_at REAL NOT NULL, expires_at REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS ix_session_message
                    ON session_message(agent, session_id, id);
                CREATE TABLE IF NOT EXISTS session_summary (
                    agent TEXT NOT NULL, session_id TEXT NOT NULL, version INTEGER NOT NULL,
                    through_id INTEGER NOT NULL, content TEXT NOT NULL, expires_at REAL NOT NULL,
                    PRIMARY KEY(agent,session_id)
                );
            """)

    @contextmanager
    def _connect(self):
        connection = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout=10000")
        try:
            yield connection
        finally:
            connection.close()

    def append_event(self, data: dict) -> AuditEvent:
        """Serialize the chain head and append one validated event atomically."""
        return self.append_events([data])[0]

    def append_events(self, events: list[dict]) -> list[AuditEvent]:
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                written = [self._append_event(connection, data) for data in events]
                connection.commit()
                return written
            except Exception:
                connection.rollback()
                raise

    @staticmethod
    def _append_event(connection: sqlite3.Connection, data: dict) -> AuditEvent:
        head = connection.execute(
            "SELECT last_hash FROM audit_chain_head WHERE agent=?", (data["agent"],)
        ).fetchone()
        prev_hash = head["last_hash"] if head else ""
        values = dict(data)
        values.setdefault("event_id", "ev_" + uuid4().hex)
        values.setdefault("ts", utc_now())
        values.setdefault("env", "dev")
        values["prev_hash"] = prev_hash
        values["hash"] = "0" * 64
        draft = AuditEvent.model_validate(values)
        values["hash"] = chain_hash(draft)
        event = AuditEvent.model_validate(values)
        snapshot = event.model_dump(mode="json")
        connection.execute("""
            INSERT INTO audit_event
            (event_id, ts, agent, env, action, resource, risk, decision,
             payload_json, input_digest, prev_hash, hash, event_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            event.event_id, snapshot["ts"], event.agent, event.env.value,
            event.action.value, event.resource, event.risk.value,
            event.decision.value, json.dumps(snapshot.get("payload") or {}, ensure_ascii=False),
            event.input_digest, prev_hash, event.hash,
            json.dumps(snapshot, ensure_ascii=False),
        ))
        connection.execute("""
            INSERT INTO audit_chain_head(agent, last_event_id, last_hash, count)
            VALUES (?, ?, ?, 1)
            ON CONFLICT(agent) DO UPDATE SET
                last_event_id=excluded.last_event_id,
                last_hash=excluded.last_hash,
                count=audit_chain_head.count+1
        """, (event.agent, event.event_id, event.hash))
        return event

    def events(self, agent: str):
        with self._connect() as connection:
            return [dict(row) for row in connection.execute(
                "SELECT * FROM audit_event WHERE agent=? ORDER BY rowid", (agent,)
            )]

    def chain_head(self, agent: str):
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM audit_chain_head WHERE agent=?", (agent,)).fetchone()
            return dict(row) if row else None

    def append_session_message(self, agent: str, session_id: str, role: str,
                               content: str, now: float, ttl_seconds: int) -> None:
        with self._lock, self._connect() as connection:
            connection.execute("DELETE FROM session_message WHERE expires_at <= ?", (now,))
            connection.execute("""
                INSERT INTO session_message(agent, session_id, role, content, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (agent, session_id, role, content, now, now + ttl_seconds))

    def session_messages(self, agent: str, session_id: str, now: float, limit: int = 128,
                         include_ids: bool = False) -> list[dict]:
        with self._connect() as connection:
            rows = connection.execute("""
                SELECT id, role, content FROM session_message
                WHERE agent=? AND session_id=? AND expires_at>?
                ORDER BY id DESC LIMIT ?
            """, (agent, session_id, now, limit)).fetchall()
        return [{"role": row["role"], "content": row["content"], **({"id": str(row["id"])} if include_ids else {})} for row in reversed(rows)]

    def session_summary(self, agent, session, now):
        with self._connect() as connection:
            row = connection.execute("SELECT version,through_id,content FROM session_summary WHERE agent=? AND session_id=? AND expires_at>?",
                                     (agent, session, now)).fetchone()
        return {"version": row["version"], "throughId": str(row["through_id"]), "content": row["content"]} if row else {"version": 0, "throughId": "0", "content": ""}

    def save_session_summary(self, agent, session, state, now, ttl):
        with self._lock, self._connect() as connection:
            connection.execute("DELETE FROM session_summary WHERE expires_at<=?", (now,))
            if state["version"] == 0:
                connection.execute("INSERT OR IGNORE INTO session_summary VALUES (?,?,1,?,?,?)",
                                   (agent, session, int(state["throughId"]), state["content"], now+ttl))
            else:
                connection.execute("UPDATE session_summary SET version=version+1,through_id=?,content=? WHERE agent=? AND session_id=? AND version=? AND through_id<?",
                                   (int(state["throughId"]), state["content"], agent, session, state["version"], int(state["throughId"])))

    def search_session(self, agent, session, query, before, now):
        from keel.session_history import terms, excerpt
        words = terms(query)
        if not words:
            return {"items": []}
        score = "+".join("CASE WHEN instr(lower(content),?)>0 THEN 1 ELSE 0 END" for _ in words)
        with self._connect() as connection:
            rows = connection.execute("SELECT id,role,content,"+score+" AS score FROM session_message "
                "WHERE agent=? AND session_id=? AND expires_at>? AND id<? ORDER BY score DESC,id DESC LIMIT 4",
                (*words, agent, session, now, int(before))).fetchall()
        items = []
        for row in rows:
            if row['score']:
                lower = row['content'].lower()
                start = min(lower.index(w) for w in words if w in lower)
                items.append(excerpt(row, max(0, start-160)))
        return {"items": items}

    def read_session(self, agent, session, message_id, now, offset=0, limit=1200):
        from keel.session_history import excerpt
        if offset < 0 or not 1 <= limit <= 4000:
            raise ValueError("invalid excerpt range")
        with self._connect() as connection:
            row = connection.execute("SELECT id,role,content FROM session_message "
                "WHERE agent=? AND session_id=? AND id=? AND expires_at>?", (agent, session, int(message_id), now)).fetchone()
        return {"items": [excerpt(row, offset, limit)] if row else []}

    def insert_approval(self, data: dict, audit_events: list[dict]) -> dict:
        if not audit_events:
            raise ValueError("approval requires a synchronous audit event")
        values = dict(data)
        values.setdefault("id", "ap_" + uuid4().hex)
        values.setdefault("created_at", utc_now())
        values.setdefault("status", "PENDING")
        keys = ("id", "subject_type", "subject_ref", "agent_name", "run_id", "checkpoint_ref",
                "resume_token", "summary", "status", "created_at", "expires_at", "trace_id",
                "actor_user", "risk", "payload_digest")
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                for event in audit_events:
                    self._append_event(connection, event)
                connection.execute("""
                    INSERT INTO approval_request
                    (id, subject_type, subject_ref, agent_name, run_id, checkpoint_ref,
                     resume_token, summary, status, created_at, expires_at, trace_id,
                     actor_user, risk, payload_digest)
                    VALUES (:id, :subject_type, :subject_ref, :agent_name, :run_id,
                            :checkpoint_ref, :resume_token, :summary, :status, :created_at,
                            :expires_at, :trace_id, :actor_user, :risk, :payload_digest)
                """, {key: values.get(key) for key in keys})
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return self.approval(values["id"])

    def approval(self, approval_id: str) -> dict | None:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM approval_request WHERE id=?", (approval_id,)).fetchone()
            return dict(row) if row else None

    def list_approvals(self, status: str | None = None, agent: str | None = None,
                       page: int = 1, size: int = 20) -> tuple[list[dict], int]:
        if page < 1 or not 1 <= size <= 100:
            raise ValueError("page must be >= 1 and size must be 1..100")
        where = " WHERE 1=1"
        params = []
        if status:
            where += " AND status=?"
            params.append(status)
        if agent:
            where += " AND agent_name=?"
            params.append(agent)
        with self._connect() as connection:
            total = connection.execute("SELECT count(*) FROM approval_request" + where, params).fetchone()[0]
            rows = connection.execute("SELECT * FROM approval_request" + where +
                                      " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                                      (*params, size, (page - 1) * size))
            return [dict(row) for row in rows], total

    def decide_approval(self, approval_id: str, status: str, decided_by: str,
                        audit_event: dict) -> dict:
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute("SELECT * FROM approval_request WHERE id=?", (approval_id,)).fetchone()
                if row is None:
                    raise KeyError(approval_id)
                if row["status"] != "PENDING":
                    raise ValueError("approval is no longer pending")
                if row["expires_at"] and datetime.fromisoformat(row["expires_at"].replace("Z", "+00:00")) <= datetime.now(timezone.utc):
                    connection.execute("UPDATE approval_request SET status='EXPIRED' WHERE id=?", (approval_id,))
                    connection.commit()
                    raise ValueError("approval expired")
                self._append_event(connection, audit_event)
                connection.execute("""
                    UPDATE approval_request SET status=?, decided_by=?, decided_at=? WHERE id=?
                """, (status, decided_by, utc_now(), approval_id))
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return self.approval(approval_id)

    def mark_resumed(self, approval_id: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute("UPDATE approval_request SET resumed_at=? WHERE id=? AND resumed_at IS NULL",
                               (utc_now(), approval_id))
