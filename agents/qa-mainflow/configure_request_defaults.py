"""Run on the application node; merge only the QA scopes, never print secrets."""
import base64
import json
from pathlib import Path
import subprocess


def merge(existing, configured):
    allowed = {f"qa-{name}-1019-{env}" for name in ("tools", "guard", "memory", "routing")
               for env in ("dev", "prod")}
    if set(configured) != allowed:
        raise ValueError("only the lifecycle QA dev/prod keys may be configured")
    result = dict(existing)
    for alias, models in configured.items():
        if set(models) != {"qwen3.8-flash"} or models["qwen3.8-flash"] != {
                "enable_thinking": False, "preserve_thinking": False}:
            raise ValueError("unexpected QA model defaults")
        result[alias] = {**existing.get(alias, {}), **models}
    return result


def main():
    configured = json.loads(Path(__file__).with_name("request-defaults.json").read_text())
    result = subprocess.run(["kubectl", "-n", "keel-system", "get", "secret", "keel-llm", "-o", "json"],
                            check=True, capture_output=True)
    secret = json.loads(result.stdout)
    prior = secret.get("data", {}).get("request-defaults")
    existing = json.loads(base64.b64decode(prior)) if prior else {}
    updated = merge(existing, configured)
    patch = {"metadata": {"resourceVersion": secret["metadata"]["resourceVersion"]}, "data": {
        "request-defaults": base64.b64encode(json.dumps(updated).encode()).decode()}}
    subprocess.run(["kubectl", "-n", "keel-system", "patch", "secret", "keel-llm", "--type=merge",
                    "--patch-file=/dev/stdin"], input=json.dumps(patch).encode(), check=True, capture_output=True)
    print(f"configured {len(configured)} lifecycle QA model scopes; gateway rollout required")


if __name__ == "__main__":
    main()
