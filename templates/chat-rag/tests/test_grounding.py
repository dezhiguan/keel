"""Exercise the template entry point without network or model credentials."""

import asyncio
import runpy
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from keel import Agent
from keel.knowledge import KnowledgeResult


@pytest.fixture
def entry(monkeypatch):
    agent = SimpleNamespace(
        manifest=SimpleNamespace(spec=SimpleNamespace(knowledge=[SimpleNamespace(kb="17")])),
        entry=lambda callback: callback,
        asgi=lambda: None,
    )
    monkeypatch.setattr(Agent, "from_manifest", lambda _: agent)
    from pathlib import Path
    return runpy.run_path(str(Path(__file__).resolve().parents[1] / "app.py"))["run"]


def context(found):
    from pathlib import Path
    from keel.prompts import Prompt
    body = (Path(__file__).resolve().parents[1] / "prompts/answer.md").read_text()
    prompt = Prompt("rag/answer", None, "text", True, {}, body)
    return SimpleNamespace(
        knowledge=SimpleNamespace(search=AsyncMock(return_value=found)),
        llm=SimpleNamespace(chat=AsyncMock(return_value="期限为 7 天，来源 policy.md")),
        prompt=AsyncMock(return_value=prompt), step=Mock(),
        final=Mock(side_effect=lambda text, **kwargs: {"text": text, **kwargs}),
    )


def test_model_receives_retrieved_evidence_and_citations(entry):
    ctx = context(KnowledgeResult(
        [{"filename": "policy.md", "content": "申请期限为 7 天。"}],
        [{"doc_id": 11, "chunk_id": 42, "filename": "policy.md", "score": 0.9}],
    ))
    result = asyncio.run(entry(SimpleNamespace(input={"text": "申请期限？"}), ctx))
    ctx.knowledge.search.assert_awaited_once_with("17", "申请期限？")
    model_input = ctx.llm.chat.call_args.args[0][0]["content"]
    assert "申请期限为 7 天。" in model_input
    assert "policy.md" in model_input
    assert "申请期限？" in model_input
    assert result["citations"][0]["chunk_id"] == 42


def test_no_hits_abstains_without_model_call(entry):
    ctx = context(KnowledgeResult([], []))
    result = asyncio.run(entry(SimpleNamespace(input={"text": "不存在的规定"}), ctx))
    assert "没有检索到" in result["text"]
    assert result["citations"] == []
    ctx.llm.chat.assert_not_awaited()


def test_retrieval_failure_does_not_generate_ungrounded_answer(entry):
    ctx = context(KnowledgeResult([], []))
    ctx.knowledge.search.side_effect = RuntimeError("permission denied")
    with pytest.raises(RuntimeError, match="permission denied"):
        asyncio.run(entry(SimpleNamespace(input={"text": "申请期限？"}), ctx))
    ctx.llm.chat.assert_not_awaited()
