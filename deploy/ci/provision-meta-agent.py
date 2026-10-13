"""Run on the application node. Reads cluster Secrets in memory; never prints credentials."""

import base64
import json
import secrets
import subprocess
from pathlib import Path


def kubectl(*args, body=None):
    return subprocess.check_output(["kubectl", "-n", "keel-system", *args], input=body)


def secret(name):
    raw = kubectl("get", "secret", name, "-o", "json")
    return {key: base64.b64decode(value).decode() for key, value in json.loads(raw)["data"].items()}


def exists(name):
    return subprocess.run(["kubectl", "-n", "keel-system", "get", "secret", name],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def save(name, values):
    body = {"apiVersion": "v1", "kind": "Secret", "metadata": {"name": name, "namespace": "keel-system"},
            "type": "Opaque", "stringData": values}
    kubectl("apply", "-f", "-", body=json.dumps(body).encode())
    print("Configured", name)


if not exists("keel-meta-bridge"):
    save("keel-meta-bridge", {"KEEL_META_AGENT_URL": "http://meta-agent.keel-system.svc.cluster.local:8000",
                              "KEEL_META_AGENT_TOKEN": secrets.token_urlsafe(32)})
if not exists("keel-meta-agent"):
    server = secret("keel-server")
    admin = secret("keel-llm")["admin-key"]
    # Execute inside an existing Python service so the cluster-only model gateway resolves locally.
    script = """
import json,sys,httpx
admin=sys.stdin.read()
r=httpx.post('http://keel-llm.keel-system.svc.cluster.local:8088/admin/v1/keys',
 headers={'Authorization':'Bearer '+admin},json={'alias':'meta-agent-dev','models':['qwen-plus'],
 'fallback':[],'dailyBudgetCny':10,'allowFallback':False},timeout=30)
r.raise_for_status()
print(json.dumps({'key':r.json()['key']}))
"""
    issued = json.loads(kubectl("exec", "-i", "deployment/git-ci", "--", "python3", "-c", script, body=admin.encode()))
    save("keel-meta-agent", {
        "KEEL_LLM_BASE_URL": "http://keel-llm.keel-system.svc.cluster.local:8088",
        "KEEL_LLM_KEY": issued["key"], "KEEL_ENV": "dev",
        "LANGFUSE_HOST": "https://jp.cloud.langfuse.com",
        "LANGFUSE_PUBLIC_KEY": server["LANGFUSE_PUBLIC_KEY"], "LANGFUSE_SECRET_KEY": server["LANGFUSE_SECRET_KEY"],
        "KEEL_TRACE_BUFFER_PATH": ".keel/traces", "KEEL_AUDIT_URL": "http://keel-audit.keel-system.svc.cluster.local:8080",
        "KEEL_AUDIT_TOKEN": secrets.token_urlsafe(32), "KEEL_AUDIT_SPOOL_PATH": ".keel/audit-spool.jsonl",
        "KEEL_GIT_CI_URL": "http://git-ci.keel-system.svc.cluster.local:8000",
        "KEEL_TEMPLATES_DIR": "/src/templates",
    })
print("Meta-agent Secrets ready; existing keys were preserved")

# The application's single-node k3s cannot pull the dynamic provisioner's helper image.
# Pre-bind only this application's volume; retain its data independently of claim deletion.
nodes = json.loads(subprocess.check_output(["kubectl", "get", "nodes", "-o", "json"]))["items"]
if len(nodes) != 1:
    raise SystemExit("Specify a storage node before provisioning meta-agent on a multi-node cluster")
directory = Path("/opt/keel/meta-agent-data")
directory.mkdir(parents=True, exist_ok=True)
volume = {"apiVersion": "v1", "kind": "PersistentVolume", "metadata": {"name": "keel-meta-agent-data"},
    "spec": {"capacity": {"storage": "1Gi"}, "volumeMode": "Filesystem", "accessModes": ["ReadWriteOnce"],
        "persistentVolumeReclaimPolicy": "Retain", "storageClassName": "local-path",
        "claimRef": {"namespace": "keel-system", "name": "meta-agent-data"},
        "local": {"path": str(directory)}, "nodeAffinity": {"required": {"nodeSelectorTerms": [
            {"matchExpressions": [{"key": "kubernetes.io/hostname", "operator": "In",
                "values": [nodes[0]["metadata"]["labels"]["kubernetes.io/hostname"]]}]}]}}}}
kubectl("apply", "-f", "-", body=json.dumps(volume).encode())
print("Meta-agent retained local volume ready")
