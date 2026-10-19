import sys
import json
from pathlib import Path

from keel.manifest import AgentManifest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from manifest import PROFILES, build


def test_profiles_have_distinct_governed_runtime_and_real_dependencies():
    assert len(PROFILES) == 9
    for name in PROFILES:
        manifest = AgentManifest.model_validate(build(name))
        assert manifest.metadata.name == name
        assert manifest.spec.kind.value == "agent"
        assert manifest.spec.runtime.endpoint.endswith(f"{name}.agents.svc.cluster.local:8000")
        assert manifest.spec.tools
        assert manifest.spec.capabilities.loop is True
    assert set(build("qa-workflow-coordinator-1017")["spec"]["delegates"]) == {
        "qa-devops-investigator-1017", "qa-incident-planner-1017"}
    assert build("qa-policy-advisor-1017")["spec"]["skills"] == [
        {"name": "qa-evidence-grounded-1017", "version": 1}]


def test_lifecycle_profiles_use_the_registered_logical_models_and_scoped_knowledge():
    for name in ("qa-tools-1019", "qa-guard-1019", "qa-memory-1019", "qa-routing-1019"):
        spec = build(name)["spec"]
        assert PROFILES[name].get("env", "dev") in {"dev", "test", "staging", "prod"}
        assert spec["models"]["default"] == ("chat-cheap" if name == "qa-routing-1019" else "chat-default")
        assert spec["knowledge"] == [{"kb": "平台基准库·v1"}]
        assert spec["eval"]["gate"] == {"minScore": 0.95, "maxRegression": 0.02}
        assert spec["models"]["fallback"] == ["qwen3.8-flash", "deepseek-flash"]
        assert {tool["name"] for tool in spec["tools"]} == {"shift.policy", "devflow.catalog.search"}


def test_scoped_request_defaults_preserve_other_agents_and_unrelated_parameters():
    from configure_request_defaults import merge
    configured = json.loads((Path(__file__).resolve().parents[1] / "request-defaults.json").read_text())
    existing = {"meta-agent-dev": {"qwen3.8-flash": {"thinking_budget": 2048}},
                "qa-tools-1019-dev": {"deepseek-flash": {"thinking_budget": 4096}}}
    updated = merge(existing, configured)
    assert updated["meta-agent-dev"] == existing["meta-agent-dev"]
    assert updated["qa-tools-1019-dev"]["deepseek-flash"] == {"thinking_budget": 4096, "thinking": {"type": "disabled"}}
    assert existing["qa-tools-1019-dev"]["deepseek-flash"] == {"thinking_budget": 4096}
    assert updated["qa-tools-1019-dev"]["qwen3.8-flash"] == {"enable_thinking": False, "preserve_thinking": False}
    assert "qwen3.8-flash" not in existing["qa-tools-1019-dev"]
