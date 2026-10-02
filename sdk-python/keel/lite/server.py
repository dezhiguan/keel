"""In-process ASGI adapter for the local audit and approval API."""

import json
import sqlite3
from urllib.parse import parse_qs
from uuid import uuid4

from keel._generated.errors import ErrorCode

from .approval import LiteApproval
from .audit import LiteAudit
from .store import LiteStore


class LiteServer:
    def __init__(self, store: LiteStore, capture_fields=()):
        self.store = store
        self.audit = LiteAudit(store, capture_fields)
        self.approvals = LiteApproval(store, self.audit)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            raise ValueError("keel-lite only handles HTTP scopes")
        trace_id = "tr_" + uuid4().hex
        headers = {key.lower(): value.decode("utf-8") for key, value in scope.get("headers", [])}
        decided_by = headers.get(b"x-keel-user", "local")
        path = scope["path"]
        method = scope["method"]
        try:
            if method == "GET" and path == "/api/v1/approvals":
                query = parse_qs(scope.get("query_string", b"").decode("utf-8"))
                data = self.approvals.list(
                    status=query.get("status", [None])[0], agent=query.get("agent", [None])[0],
                    page=int(query.get("page", [1])[0]), size=int(query.get("size", [20])[0]),
                )
            elif method == "POST" and path == "/api/v1/audit/events":
                body = await self._body(receive)
                self._require(body, "agent", "action", "risk", "decision")
                event = self.audit.record(
                    agent=body["agent"], action=body["action"], risk=body["risk"],
                    decision=body["decision"], resource=body.get("resource"),
                    payload=body.get("payload"), run_id=body.get("run_id"),
                    trace_id=body.get("trace_id"), approver=body.get("approver"),
                    input_digest=body.get("input_digest"), event_id=body.get("event_id"),
                    ts=body.get("ts"),
                )
                data = event.model_dump(mode="json")
            elif method == "POST" and path == "/api/v1/approvals":
                body = await self._body(receive)
                self._require(body, "subjectType", "subjectRef", "agent", "summary")
                data = self.approvals.create(
                    subject_type=body["subjectType"], subject_ref=body["subjectRef"],
                    agent=body["agent"], summary=body["summary"],
                    risk=body.get("risk", "HIGH").lower(), run_id=body.get("runId"),
                    checkpoint_ref=body.get("checkpointRef"), trace_id=body.get("traceId"),
                    actor_user=body.get("actorUser"), payload_digest=body.get("payloadDigest"),
                    expires_at=body.get("expiresAt"),
                )
            elif method == "POST" and path.startswith("/api/v1/approvals/") and path.endswith("/decision"):
                approval_id = path[len("/api/v1/approvals/"):-len("/decision")]
                if not approval_id or "/" in approval_id:
                    raise KeyError(path)
                body = await self._body(receive)
                self._require(body, "decision")
                data = self.approvals.decide(approval_id, body["decision"], decided_by)
            else:
                raise KeyError(path)
            await self._respond(send, 200, {"code": "OK", "message": None,
                                            "traceId": trace_id, "data": data})
        except KeyError as exc:
            await self._error(send, 404, ErrorCode.SERVER_NOT_FOUND, trace_id, str(exc))
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            code = ErrorCode.RUN_NOT_RESUMABLE if "no longer pending" in str(exc) else ErrorCode.SERVER_INVALID_PARAM
            await self._error(send, code.http, code, trace_id, str(exc))
        except sqlite3.Error as exc:
            await self._error(send, 503, ErrorCode.AUDIT_WRITE_FAILED, trace_id, str(exc))

    @staticmethod
    def _require(body: dict, *names: str):
        missing = [name for name in names if name not in body]
        if missing:
            raise ValueError("missing required fields: " + ", ".join(missing))

    @staticmethod
    async def _body(receive) -> dict:
        chunks = []
        size = 0
        while True:
            message = await receive()
            if message["type"] != "http.request":
                raise ValueError("request disconnected")
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > 1024 * 1024:
                raise ValueError("request body too large")
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        data = json.loads(b"".join(chunks))
        if not isinstance(data, dict):
            raise ValueError("JSON object required")
        return data

    @staticmethod
    async def _respond(send, status: int, body: dict):
        encoded = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        await send({"type": "http.response.start", "status": status,
                    "headers": [(b"content-type", b"application/json; charset=utf-8")]})
        await send({"type": "http.response.body", "body": encoded})

    async def _error(self, send, status: int, code: ErrorCode, trace_id: str, message: str):
        await self._respond(send, status, {"code": code.value, "message": message or code.message,
                                           "traceId": trace_id, "retryable": code.retryable})
