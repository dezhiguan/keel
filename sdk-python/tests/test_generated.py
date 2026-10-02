import json

import pytest
from pydantic import ValidationError

from keel.manifest import AgentManifest
from keel.protocol.errors import ErrorCode
from keel.protocol.events import SuspendEvent
from keel._generated.audit import AuditEvent


AUDIT = {
    "event_id": "e1", "ts": "2026-09-29T12:49:30Z", "agent": "agent-a",
    "env": "prod", "action": "tool.call", "risk": "low", "decision": "allowed",
    "hash": "0" * 64, "future": 123,
}


def test_unknown_fields_and_missing_optional_values():
    manifest = AgentManifest.model_validate({
        "apiVersion": "keel/v1", "metadata": {"name": "demo-agent", "future": 7},
        "spec": {"runtime": {"endpoint": "https://example.test"}, "future": True},
        "future": 1,
    })
    assert manifest.spec.audit is None
    assert manifest.spec.runtime.liveness is None
    assert "future" not in manifest.model_dump_json()


def test_dotted_enum_and_error_metadata():
    event = AuditEvent.model_validate(AUDIT)
    assert event.action.value == "tool.call"
    assert ErrorCode.GW_QUOTA_EXCEEDED.retryable is True
    assert ErrorCode.GW_QUOTA_EXCEEDED.http == 429


def test_audit_json_matches_java_fixture():
    event = AuditEvent.model_validate(AUDIT)
    canonical = json.dumps(event.model_dump(mode="json"), ensure_ascii=False,
                           separators=(",", ":"), sort_keys=True)
    assert canonical == ('{"action":"tool.call","agent":"agent-a",'
                         '"decision":"allowed","env":"prod","event_id":"e1",'
                         '"hash":"' + "0" * 64 + '","risk":"low",'
                         '"ts":"2026-09-29T12:49:30Z"}')
    assert '"future"' not in canonical


def test_timestamp_requires_timezone_and_serializes_z():
    with pytest.raises(ValidationError):
        SuspendEvent.model_validate({
            "run_id": "r1", "reason": "approval", "trace_id": "t1",
            "resume_token": "x", "deadline": "2026-09-29T12:49:30",
        })
    event = SuspendEvent.model_validate({
        "run_id": "r1", "reason": "approval", "trace_id": "t1",
        "resume_token": "x", "deadline": "2026-09-29T12:49:30+00:00",
    })
    assert '"deadline":"2026-09-29T12:49:30Z"' in event.model_dump_json()
