"""Platform protocol probes must not create requirements, model calls or suspended work."""
import asyncio
from types import SimpleNamespace
from unittest.mock import Mock
import pytest
import intake


def test_marked_exact_probe_returns_without_entering_business(monkeypatch):
    business = Mock(side_effect=AssertionError("self-check entered requirement processing"))
    monkeypatch.setattr(intake, "_requirement", business)
    ctx = SimpleNamespace(eval_run_id="self-check", step=Mock(), final=Mock(return_value="probe-result"))
    result = asyncio.run(intake.run(SimpleNamespace(input={"text": "probe"}), ctx))
    assert result == "probe-result"
    ctx.step.assert_called_once_with("self-check")
    ctx.final.assert_called_once_with("probe ok")
    business.assert_not_called()


@pytest.mark.parametrize("marker,payload", [
    (None, {"text": "probe"}), ("ev_real", {"text": "probe"}),
    ("self-check", {"text": "ordinary requirement"}),
    ("self-check", {"text": "probe", "extra": True}),
])
def test_marker_and_exact_payload_are_both_required(monkeypatch, marker, payload):
    monkeypatch.setattr(intake, "_requirement", Mock(side_effect=ValueError("business path")))
    ctx = SimpleNamespace(eval_run_id=marker, final=Mock())
    with pytest.raises(ValueError, match="business path"):
        asyncio.run(intake.run(SimpleNamespace(input=payload), ctx))
    ctx.final.assert_not_called()
