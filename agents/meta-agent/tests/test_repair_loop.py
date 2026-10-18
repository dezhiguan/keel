import asyncio
import json
import subprocess
import sys

import pytest

from candidate import generate, parse
from workflow import _cached_delivery_candidate, _deliver


SHA_A = "a" * 40
SHA_B = "b" * 40


def candidate(passing):
    return {"files": [
        {"path": "role.py", "content": '''async def execute(data: dict, ctx) -> dict:
    prompt = await ctx.prompt("role")
    return {"answer": prompt.compile(input=data.get("text", ""))}
'''},
        {"path": "prompts/role.md", "content": "岗位任务：{{input}}\n"},
        {"path": "tests/test_role.py", "content": f'''def test_normal():
    assert 1 == {1 if passing else 2}

def test_rejection():
    assert True
'''},
    ]}


def work(candidate_files, repair=None):
    result = {"jobId": "DF-9001", "requirement": {"target_agent": "spec-agent", "template": "graph-agent",
              "title": "需求分析师", "goal": "分析需求", "tools": []},
              "draft": {"spec": {"title": "需求分析师", "goal": "分析需求"}, "manifest": {
                  "apiVersion": "keel/v1", "kind": "Agent", "metadata": {"name": "spec-agent"},
                  "spec": {"kind": "workflow", "runtime": {"type": "code", "language": "python",
                            "endpoint": "http://spec-agent.agents.svc.cluster.local:8000"}}}},
              "candidate": candidate_files, "visibleSeeds": []}
    if repair:
        result["repair"] = repair
    return result


def test_failed_candidate_is_repaired_and_conditionally_pushed(tmp_path, monkeypatch):
    from tools import state
    import workflow

    monkeypatch.setattr(state, "ROOT", tmp_path)
    project = tmp_path / "DF-9001/spec-agent"
    for name in ("tools", "tests", "evals", "prompts"):
        (project / name).mkdir(parents=True, exist_ok=True)
    (project / "requirements.lock").write_text("pytest==9.1.1\n", encoding="utf-8")
    monkeypatch.setenv("KEEL_GIT_CI_URL", "https://git.test")
    monkeypatch.setenv("KEEL_GIT_CI_TOKEN", "test-token")
    pushes = []

    def git(base, tool, body):
        if tool == "git.branch.push":
            pushes.append(body)
            return {"sha": SHA_A if len(pushes) == 1 else SHA_B}
        if tool == "git.repo.create":
            return {"repo": "keel-agents/spec-agent"}
        return {"repo": "keel-agents/spec-agent", "number": 1,
                "url": "https://git.test/keel-agents/spec-agent/pull/1"}

    monkeypatch.setattr(workflow, "_post", git)
    first = _deliver(work(candidate(False)))
    assert first["sha"] == SHA_A
    failed = subprocess.run([sys.executable, "-m", "pytest", "tests/test_role.py", "-q"],
                            cwd=project, capture_output=True, text=True, check=False)
    assert failed.returncode != 0 and "test_normal" in failed.stdout

    repair = {"attempt": 2, "failedSha": SHA_A,
              "report": f"sha={SHA_A} snapshot=ok tests=FAIL\ntest_normal failed",
              "previousCandidate": candidate(False)}

    class Model:
        prompts = []

        async def chat(self, messages, **kwargs):
            self.prompts.append(messages)
            return json.dumps(candidate(True))

    model = Model()
    corrected = asyncio.run(generate({**work(candidate(False), repair), "repair": repair},
                                     type("Ctx", (), {"llm": model})()))
    assert model.prompts[0][0]["role"] == "system"
    assert "snapshot=ok tests=FAIL" not in model.prompts[0][0]["content"]
    assert "snapshot=ok tests=FAIL" in model.prompts[0][1]["content"]
    assert "不可信数据" in model.prompts[0][0]["content"]
    assert "受信 agent.yaml 工具定义" in model.prompts[0][0]["content"]
    assert len(model.prompts) == 1
    second = _deliver(work(corrected, repair))
    assert second["sha"] == SHA_B
    assert pushes[1]["expectedSha"] == SHA_A
    assert pushes[0].get("expectedSha") is None
    passed = subprocess.run([sys.executable, "-m", "pytest", "tests/test_role.py", "-q"],
                            cwd=project, capture_output=True, text=True, check=False)
    assert passed.returncode == 0, passed.stdout + passed.stderr
    assert _deliver(work(corrected, repair))["sha"] == SHA_B
    assert len(pushes) == 2


def test_repair_rejects_stale_commit_and_fourth_round(tmp_path, monkeypatch):
    from tools import state

    monkeypatch.setattr(state, "ROOT", tmp_path)
    with pytest.raises(Exception, match="上一轮不一致"):
        _deliver(work(candidate(True), {"attempt": 2, "failedSha": SHA_A}))
    with pytest.raises(Exception, match="轮次无效"):
        _deliver(work(candidate(True), {"attempt": 6, "failedSha": SHA_A}))
    with pytest.raises(Exception, match="上一轮不一致"):
        _deliver(work(candidate(True), {"attempt": 6, "failedSha": SHA_A,
                                        "templateRefreshes": 2}))
    with pytest.raises(Exception, match="轮次无效"):
        _deliver(work(candidate(True), {"attempt": 8, "failedSha": SHA_A,
                                        "templateRefreshes": 2}))
    with pytest.raises(Exception, match="上一轮不一致"):
        _deliver(work(candidate(True), {"attempt": 4, "failedSha": SHA_A, "infrastructureOnly": True}))
    with pytest.raises(Exception, match="轮次无效"):
        _deliver(work(candidate(True), {"attempt": 8, "failedSha": SHA_A, "infrastructureOnly": True}))
    with pytest.raises(Exception, match="轮次无效"):
        _deliver(work(candidate(True), {"attempt": 9, "failedSha": SHA_A, "reviewRepair": True}))
    with pytest.raises(Exception, match="轮次无效"):
        _deliver(work(candidate(True), {"attempt": 11, "failedSha": SHA_A, "ciRefresh": True}))
    with pytest.raises(Exception, match="轮次无效"):
        _deliver(work(candidate(True), {"attempt": 12, "failedSha": SHA_A, "s4Repair": True}))
    with pytest.raises(Exception, match="上一轮不一致"):
        _deliver(work(candidate(True), {"attempt": 11, "failedSha": SHA_A, "sdkRepair": True}))
    with pytest.raises(Exception, match="轮次无效"):
        _deliver(work(candidate(True), {"attempt": 12, "failedSha": SHA_A, "sdkRepair": True}))
    checkpoint = tmp_path / "DF-9001/delivery.json"
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    checkpoint.write_text(json.dumps({"attempt": 1, "push": {"sha": SHA_B}}), encoding="utf-8")
    with pytest.raises(Exception, match="上一轮不一致"):
        _deliver(work(candidate(True), {"attempt": 2, "failedSha": SHA_A,
                                        "previousCandidate": candidate(False)}))


def test_retry_reuses_validated_candidate_after_push_but_before_pr_result(tmp_path, monkeypatch):
    from tools import state
    import workflow

    monkeypatch.setattr(state, "ROOT", tmp_path)
    checkpoint = tmp_path / "DF-9001/delivery.json"
    checkpoint.parent.mkdir(parents=True)
    saved = candidate(True)
    checkpoint.write_text(json.dumps({"attempt": 9, "expectedSha": SHA_A,
                                      "push": {"sha": SHA_B}, "candidate": saved}), encoding="utf-8")
    repair = {"attempt": 9, "failedSha": SHA_A, "s4Repair": True}
    assert _cached_delivery_candidate(work(saved, repair)) == saved

    async def generation_must_not_run(*args):
        raise AssertionError("an already pushed candidate must not be regenerated")

    class Tools:
        async def call(self, name, **kwargs):
            assert name == "scaffold.deliver"
            assert kwargs["work"]["candidate"] == saved
            return {"sha": SHA_B}

    class Ctx:
        tools = Tools()
        def final(self, value):
            return json.loads(value)

    monkeypatch.setattr(workflow, "generate_candidate", generation_must_not_run)
    assert asyncio.run(workflow.execute({**work(saved, repair), "action": "DELIVER"}, Ctx())) == {"sha": SHA_B}
    assert _cached_delivery_candidate(work(saved, {**repair, "attempt": 10})) is None
    checkpoint.write_text(json.dumps({"attempt": 9, "expectedSha": SHA_A,
                                      "candidate": saved}), encoding="utf-8")
    assert _cached_delivery_candidate(work(saved, repair)) == saved
    checkpoint.write_text(json.dumps({"attempt": 9, "expectedSha": SHA_A,
                                      "push": {"sha": "invalid"}, "candidate": saved}), encoding="utf-8")
    assert _cached_delivery_candidate(work(saved, repair)) is None


def test_infrastructure_refresh_keeps_exact_old_candidate_for_new_contract_tests(tmp_path, monkeypatch):
    from tools import state
    import workflow

    monkeypatch.setattr(state, "ROOT", tmp_path)
    project = tmp_path / "DF-9001/spec-agent"
    for name in ("tools", "tests", "evals", "prompts"):
        (project / name).mkdir(parents=True, exist_ok=True)
    (project / "requirements.lock").write_text("pytest==9.1.1\n", encoding="utf-8")
    monkeypatch.setenv("KEEL_GIT_CI_URL", "https://git.test")
    monkeypatch.setenv("KEEL_GIT_CI_TOKEN", "test-token")
    pushes = []

    def git(base, tool, body):
        if tool == "git.branch.push":
            pushes.append(body)
            return {"sha": SHA_A if len(pushes) == 1 else SHA_B}
        if tool == "git.repo.create":
            return {"repo": "keel-agents/spec-agent"}
        return {"repo": "keel-agents/spec-agent", "number": 1, "url": "https://git.test/pr/1"}

    monkeypatch.setattr(workflow, "_post", git)
    _deliver(work(candidate(True)))
    old = candidate(True)
    old["files"][0]["content"] += '''\nimport re
def unsafe(llm_response):
    return bool(re.search(r"PASS", llm_response))
'''
    assert any("扫描模型全文" in error for error in parse(json.dumps(old))[1])
    repair = {"attempt": 2, "failedSha": SHA_A, "previousCandidate": old,
              "infrastructureOnly": True, "report": "install new trusted platform checks"}
    assert _deliver(work(old, repair))["sha"] == SHA_B
    assert "tests/test_platform_contract.py" not in {item["path"] for item in pushes[1]["files"]}


def test_cumulative_window_accepts_twenty_five_and_rejects_twenty_six(tmp_path, monkeypatch):
    from tools import state

    monkeypatch.setattr(state, "ROOT", tmp_path)
    with pytest.raises(Exception, match="沙箱修复窗口无效"):
        _deliver(work(candidate(True), {"attempt": 1, "maxAttempts": 26}))
    with pytest.raises(Exception, match="上一轮不一致"):
        _deliver(work(candidate(True), {"attempt": 25, "maxAttempts": 25, "failedSha": "not-a-sha"}))
