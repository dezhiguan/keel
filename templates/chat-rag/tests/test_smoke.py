import json
from pathlib import Path

from tools.citations import answer_lists_sources, citations_from
from evals.scorers import citation_present

ROOT = Path(__file__).resolve().parents[1]


def test_manifest_names_litellm_alias_not_a_vendor_model():
    text = (ROOT / "agent.yaml").read_text()
    assert "qwen-plus" in text
    assert "sk-" not in text


def test_gitignore_covers_local_audit():
    assert ".keel/" in (ROOT / ".gitignore").read_text().splitlines()


def test_seed_rows_are_tagged_examples_not_a_full_set():
    rows = [json.loads(line) for line in (ROOT / "evals/seed.jsonl").read_text().splitlines() if line.strip() and not line.startswith("#")]
    assert 3 <= len(rows) <= 5
    assert all(row["tags"] for row in rows)


def test_scorer_accepts_citation_and_abstain():
    assert citation_present("7 天", "依据显示 7 天", ["citation"]) == 1.0
    assert citation_present("不知道", "猜一个", ["abstain"]) == 0.0


def test_citations_keep_identifiers_not_passage_text():
    hits = [{"docId": "d1", "chunkId": "c1", "filename": "policy.md", "finalScore": 0.9, "text": "SECRET PASSAGE"}]
    cited = citations_from(hits)
    assert cited == [{"doc_id": "d1", "chunk_id": "c1", "filename": "policy.md", "score": 0.9}]
    assert "SECRET PASSAGE" not in str(cited)


def test_answer_must_name_every_source_file():
    cites = [{"filename": "policy.md"}]
    assert answer_lists_sources("见 policy.md", cites)
    assert not answer_lists_sources("没有出处", cites)


def test_prompt_forbids_invented_sources():
    assert "Do not invent" in (ROOT / "prompts/answer.md").read_text()


def test_workflow_runs_pytest_until_gate_exists():
    text = (ROOT / ".github/workflows/keel.yml").read_text()
    assert "pytest -q" in text
    assert "keel release" not in text.split("run:")[-1]


def test_dockerfile_uses_public_python_until_runtime_image_exists():
    text = (ROOT / "Dockerfile").read_text()
    assert "python:3.11-slim" in text
    assert "keel/python-runtime" in text


def test_app_searches_then_asks_the_model():
    source = (ROOT / "app.py").read_text()
    assert "ctx.knowledge.search" in source
    assert "ctx.llm.chat" in source
    assert "ctx.final" in source
