import json
import sys
from collections import defaultdict
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "evals"))

from baseline_cases import cases, render_lines
from scorers import baseline
from test_meta_agent import invoke, terminal


def test_seed_file_is_the_generated_contract():
    lines = (ROOT / "evals/seed.jsonl").read_text(encoding="utf-8").splitlines()
    assert lines == render_lines()
    assert len(lines) >= 50


def test_one_miss_in_any_tag_fails_the_gate():
    gate = yaml.safe_load((ROOT / "agent.yaml").read_text(encoding="utf-8"))["spec"]["eval"]["gate"]
    from keel.gate.compare import judge
    from keel.guard.injection import _INJECTION

    buckets = defaultdict(list)
    for row in cases():
        assert row["tags"] and all(isinstance(tag, str) and tag for tag in row["tags"])
        buckets[row["tags"][0]].append(row["id"])
        text = row["input"]["text"]
        if row["tags"] == ["guard"]:
            assert _INJECTION.search(text)
        else:
            assert not _INJECTION.search(text)
    assert len(buckets) >= 8
    assert max(len(ids) for ids in buckets.values()) <= 4
    perfect = {tag: 1.0 for tag in buckets}
    assert judge(perfect, None, min_score=gate["minScore"], max_regression=gate["maxRegression"])[0]
    for tag, ids in buckets.items():
        missed = {**perfect, tag: (len(ids) - 1) / len(ids)}
        assert not judge(missed, None, min_score=gate["minScore"], max_regression=gate["maxRegression"])[0]
        assert not judge(missed, perfect, min_score=gate["minScore"], max_regression=gate["maxRegression"])[0]


def test_scorer_rejects_lowered_risk_copied_seed_and_secret():
    lowered = {"manifest": {"apiVersion": "keel/v1", "kind": "Agent",
                            "metadata": {"name": "release-agent", "displayName": "发布员", "owner": "发布组"},
                            "spec": {"tools": [{"name": "git.pr.merge", "access": "exec", "risk": "low", "approval": "none"}]}},
               "spec": {"title": "发布员", "goal": "证据齐备后才申请合并，批准前不得发布",
                        "users": "发布审批人", "io": "门禁证据到合并申请", "success": "未批准时不会合并",
                        "risks": [], "pending": []}}
    expected = next(row["expected"] for row in cases() if row["id"] == "ma-draft-high")
    assert baseline(expected, json.dumps(lowered, ensure_ascii=False), ["draft-risk"]) == 0.0
    copied = {"cases": [{"caseId": "h-boundary", "input": {"text": "读取 CI 日志并按维度列出失败步骤"},
                         "expected": {"status": "READY"}, "tags": ["boundary"], "reason": "重复了人工原例"}]}
    expand = next(row["expected"] for row in cases() if row["id"] == "ma-expand-boundary")
    assert baseline(expand, json.dumps(copied, ensure_ascii=False), ["expand"]) == 0.0
    assert baseline({"errorCode": "SERVER_INVALID_PARAM"}, "SERVER_INVALID_PARAM", ["input"]) == 1.0
    assert baseline({"errorCode": "SERVER_INVALID_PARAM"}, "DEVFLOW_LINEAGE_FORBIDDEN", ["input"]) == 0.0


def test_local_runtime_meets_every_baseline_case(gateway):
    misses = []
    for row in cases():
        expected = row["expected"]
        if expected.get("kind") == "draft":
            gateway.replies.append(json.dumps({"spec": {"risks": ["待人确认工具范围"], "pending": []}}, ensure_ascii=False))
        elif expected.get("kind") == "eval":
            gateway.replies.append(json.dumps({"cases": [{
                "caseId": "a-baseline", "input": {"text": "baseline-extra-boundary"},
                "expected": {"status": "BLOCKED"}, "tags": ["boundary"], "reason": "缺少编号时不能编造结论",
            }]}, ensure_ascii=False))
        kind, event = terminal(invoke(row["input"]["text"]))
        answer = event.get("code", "") if expected.get("errorCode") else event.get("answer", "")
        if kind not in ("error", "final") or baseline(expected, answer, row["tags"]) != 1.0:
            misses.append(f"{row['id']}:{kind}:{event.get('code', '')}")
    assert misses == []
