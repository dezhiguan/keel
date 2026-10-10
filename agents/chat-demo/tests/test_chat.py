import json
from pathlib import Path

from app import messages_for, prompt_sha256
from evals.scorers import contains_expected

ROOT = Path(__file__).resolve().parents[1]


def test_chat_prompt_compiles_to_system_and_user():
    body = json.loads((ROOT / "prompts" / "answer.json").read_text(encoding="utf-8"))
    compiled = []
    for message in body:
        compiled.append({**message, "content": message["content"].replace("{{question}}", "你好")})
    messages = messages_for(compiled)
    assert messages[0]["role"] == "system"
    assert "对话测试" in messages[0]["content"]
    assert messages[1] == {"role": "user", "content": "你好"}


def test_text_prompt_becomes_a_user_message():
    assert messages_for("只回答数字") == [{"role": "user", "content": "只回答数字"}]


def test_prompt_hash_matches_the_canonical_chat_body():
    digest = prompt_sha256()
    assert digest.startswith("sha256:")
    assert len(digest) == len("sha256:") + 64


def test_scorer_requires_the_expected_phrase():
    assert contains_expected({"contains": "对话测试"}, "我是对话测试。", ["identity"]) == 1.0
    assert contains_expected({"contains": "7"}, "等于 8", ["arithmetic"]) == 0.0


def test_seed_cases_cover_identity_and_arithmetic():
    rows = [json.loads(line) for line in (ROOT / "evals" / "seed.jsonl").read_text(encoding="utf-8").splitlines() if line]
    tags = {tag for row in rows for tag in row["tags"]}
    assert tags == {"identity", "arithmetic"}
