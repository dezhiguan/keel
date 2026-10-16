import sys
from pathlib import Path

from keel.manifest import AgentManifest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from manifest import PROFILES, build


def test_five_profiles_have_distinct_governed_runtime_and_real_dependencies():
    assert len(PROFILES) == 5
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
