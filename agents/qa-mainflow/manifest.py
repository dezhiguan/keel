"""Materialize one governed manifest per deployed QA agent."""
import json
import os
from pathlib import Path



PROFILES = json.loads((Path(__file__).parent / "profiles.json").read_text())


def build(name: str) -> dict:
    profile = PROFILES[name]
    spec = {
        "kind": "agent",
        "capabilities": {"toolCalling": True, "loop": True, "memory": True},
        "runtime": {"type": "code", "language": "python", "template": profile["template"],
                    "endpoint": f"http://{name}.agents.svc.cluster.local:8000", "liveness": "k8s"},
        "auth": {"audience": name},
        "models": {"default": profile.get("model", "qwen-plus"), "budget": {"dailyCny": profile.get("dailyCny", 5), "maxSteps": 6}},
        "tools": [{"name": tool, "access": "read", "risk": "low"} for tool in profile["tools"]],
        "guardrails": {"inputInjection": "enforce", "grounding": "shadow", "pii": "mask"},
        "eval": {"dataset": f"{name}/smoke", "gate": {"minScore": 0.8, "maxRegression": 0.02}},
        "prompts": {"source": "langfuse", "label": "production", "items": [{"name": "answer", "type": "chat"}]},
        "audit": {"captureFields": []},
    }
    spec["guardrails"].update(profile.get("guardrails", {}))
    if profile["knowledge"]:
        spec["knowledge"] = [{"kb": kb} for kb in profile["knowledge"]]
    if profile["delegates"]:
        spec["delegates"] = profile["delegates"]
    if profile.get("skills"):
        spec["skills"] = profile["skills"]
    return {"apiVersion": "keel/v1", "kind": "Agent",
            "metadata": {"name": name, "displayName": profile["title"],
                         "owner": "平台组 / qa", "category": "dev"}, "spec": spec}


if __name__ == "__main__":
    name = os.environ["KEEL_AGENT_NAME"]
    output = Path(os.environ.get("KEEL_MANIFEST_OUTPUT", "agent.yaml"))
    output.write_text(json.dumps(build(name), ensure_ascii=False, indent=2), encoding="utf-8")
