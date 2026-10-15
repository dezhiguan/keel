import pytest

from keel.lite.memory import LiteMemory
from keel.lite.store import LiteStore
from keel.protocol.errors import ErrorCode, KeelError


def test_session_memory_isolated_by_agent_session_and_ttl(tmp_path):
    store = LiteStore(tmp_path)
    now = [1000.0]
    clock = lambda: now[0]
    first = LiteMemory(store, "agent-a", "session-a", ttl_seconds=10, clock=clock)
    first.append("user", "first")
    first.append("assistant", "reply")
    assert first.history() == [{"role": "user", "content": "first"},
                               {"role": "assistant", "content": "reply"}]
    assert LiteMemory(store, "agent-a", "session-b", clock=clock).history() == []
    assert LiteMemory(store, "agent-b", "session-a", clock=clock).history() == []
    now[0] += 10
    assert first.history() == []


def test_eval_and_missing_session_reject_writes(tmp_path):
    store = LiteStore(tmp_path)
    memory = LiteMemory(store, "agent-a", "session-a", eval_run_id="eval-1")
    with pytest.raises(KeelError) as error:
        memory.append("user", "private")
    assert error.value.code is ErrorCode.SERVER_INVALID_PARAM
    assert memory.history() == []

    without_session = LiteMemory(None, "agent-a", None)
    assert without_session.history() == []
    with pytest.raises(KeelError) as error:
        without_session.append("user", "private")
    assert error.value.code is ErrorCode.SERVER_INVALID_PARAM
