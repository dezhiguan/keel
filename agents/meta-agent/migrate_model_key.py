"""Run on the app node: python3 migrate_model_key.py <confirmed model>.

Only migrates meta-agent-dev. Credentials stay in memory and the existing Secret.
"""
import base64
import json
import subprocess
import sys


def rotation_request(current, model, marker):
    if current.get("alias") != "meta-agent-dev" or current.get("blocked") is not False:
        raise ValueError("Only the active meta-agent-dev key may be migrated")
    if current.get("models") == [model] and marker == model:
        return None
    return {"alias": "meta-agent-dev", "models": [model],
            **{name: current[name] for name in ("fallback", "dailyBudgetCny", "allowFallback")},
            **{name: current.get(name) for name in ("rpm", "tpm", "concurrency")}}


def main(model):
    def kube(*args, body=None):
        return subprocess.check_output(["kubectl", "-n", "keel-system", *args], input=body)

    def secret(name):
        return json.loads(kube("get", "secret", name, "-o", "json"))

    admin = base64.b64decode(secret("keel-llm")["data"]["admin-key"]).decode()
    target = secret("keel-meta-agent")
    marker = target["metadata"].get("annotations", {}).get("keel.io/llm-model")
    remote = """
import httpx,json,sys
request=json.load(sys.stdin)
with httpx.Client(base_url='http://keel-llm.keel-system.svc.cluster.local:8088',
                  headers={'Authorization':'Bearer '+request['admin']}, timeout=30) as client:
    response=client.request(request['method'],request['path'],json=request.get('body'))
    response.raise_for_status()
    print(json.dumps(response.json()))
"""

    def api(method, path, body=None):
        request = {"admin": admin, "method": method, "path": path, "body": body}
        return json.loads(kube("exec", "-i", "deployment/git-ci", "--", "python3", "-c", remote,
                               body=json.dumps(request).encode()))

    # Issuance validates the model in the gateway catalog before any key changes.
    current = api("GET", "/admin/v1/keys/meta-agent-dev")
    body = rotation_request(current, model, marker)
    if body is None:
        print("meta-agent model key already aligned:", model)
        return
    issued = api("POST", "/admin/v1/keys", body)
    patch = {"metadata": {"annotations": {"keel.io/llm-model": model}},
             "stringData": {"KEEL_LLM_KEY": issued["key"]}}
    # stdin keeps the new token out of process arguments and command logs.
    kube("patch", "secret", "keel-meta-agent", "--type=merge", "--patch-file=/dev/stdin",
         body=json.dumps(patch).encode())
    print("meta-agent model key migrated:", model, "version", issued["version"],
          "budget preserved", current["dailyBudgetCny"])


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: migrate_model_key.py <confirmed model>")
    main(sys.argv[1])
