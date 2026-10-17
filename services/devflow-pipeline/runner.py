"""Trusted workflow's application-node runner. No candidate Python is executed here."""
import argparse
import base64
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile


def kube(*args, body=None):
    return subprocess.check_output(["kubectl", *args], input=body)


def credentials():
    secret = json.loads(kube("-n", "agents", "get", "secret", "keel-keel-devflow-ci", "-o", "json"))
    return {"KEEL_CI_CLIENT_ID": "keel-devflow-ci", "KEEL_SERVICE_AUDIENCE": "keel-server",
            "KEEL_OAUTH_PRIVATE_KEY": base64.b64decode(secret["data"]["KEEL_CLIENT_PRIVATE_KEY"]).decode(),
            "KEEL_SERVER_URL": "http://keel-server.keel-system.svc.cluster.local:8080"}


def inside(source, payload):
    result = subprocess.run(["kubectl", "-n", "keel-system", "exec", "-i", "deployment/meta-agent", "--",
                             "python3", "-c", source], input=json.dumps(payload).encode(), capture_output=True)
    if result.returncode:
        # Do not relay tracebacks or request payloads that may include credentials or hidden cases.
        raise RuntimeError("Trusted CI step failed; inspect the task and aggregate gate report")
    return json.loads(result.stdout)


API_SOURCE = '''import json,sys,os,httpx
from keel.holdout_run import ci_headers
p=json.load(sys.stdin);os.environ.update(p["credentials"])
r=httpx.post(os.environ["KEEL_SERVER_URL"]+p["path"],json=p["body"],headers=ci_headers(),timeout=180)
x=r.json()
if r.status_code>=400 or x.get("code")!="OK":
 print(json.dumps({"error":str(x.get("code"))+": "+str(x.get("message"))}))
else: print(json.dumps(x.get("data") or {}))
'''


def api(job, env, action, **values):
    result = inside(API_SOURCE, {"credentials": credentials(), "path": f"/api/v1/devflow/ci/jobs/{job}/{action}",
                                 "body": {"env": env, **values}})
    if "error" in result:
        raise RuntimeError(result["error"])
    return result


def write_snapshot(root, files):
    root.mkdir(parents=True, exist_ok=True)
    for file in files:
        path = PurePosixPath(file["path"])
        if path.is_absolute() or ".." in path.parts or not path.parts:
            raise ValueError("Unsafe snapshot path")
        output = root.joinpath(*path.parts)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(file["content"], encoding="utf-8")


def status(context, run_url, state, message="", evaluation_failed=False):
    return api(context["jobId"], context["env"], "status", sha=context["sha"], runUrl=run_url,
               status=state, message=message, evaluationFailed=evaluation_failed)


def deploy(context, image):
    name, env, sha = context["agent"], context["env"], context["sha"]
    workload = name + "-" + env
    source = json.loads(kube("-n", "agents", "get", "secret", "keel-" + name, "-o", "json"))
    secret = {"apiVersion": "v1", "kind": "Secret", "metadata": {"name": "keel-" + workload, "namespace": "agents"},
              "type": "Opaque", "data": {**source["data"], "KEEL_ENV": base64.b64encode(env.encode()).decode()}}
    kube("apply", "-f", "-", body=json.dumps(secret).encode())
    labels = {"app": workload, "keel.io/agent": name, "keel.io/env": env, "keel.io/commit": sha}
    config = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": workload + "-manifest", "namespace": "agents"},
              "data": {"agent.yaml": json.dumps(context["manifest"], ensure_ascii=False)}}
    pod = {"automountServiceAccountToken": False, "imagePullSecrets": [{"name": "acr-cred"}],
           "hostAliases": [{"ip": "172.25.90.183", "hostnames": ["jp.cloud.langfuse.com"]}],
           "containers": [{"name": "agent", "image": image, "envFrom": [{"secretRef": {"name": "keel-" + workload}}],
               "env": [{"name": "KEEL_TRACE_BUFFER_PATH", "value": "/tmp/keel-traces"}],
               "ports": [{"containerPort": 8000}], "readinessProbe": {"httpGet": {"path": "/v1/health", "port": 8000},
                   "initialDelaySeconds": 3, "periodSeconds": 5},
               "volumeMounts": [{"name": "manifest", "mountPath": "/app/agent.yaml", "subPath": "agent.yaml", "readOnly": True},
                                {"name": "data", "mountPath": "/app/.keel"}],
               "resources": {"requests": {"cpu": "100m", "memory": "256Mi"}, "limits": {"cpu": "1000m", "memory": "768Mi"}}}],
           "volumes": [{"name": "manifest", "configMap": {"name": workload + "-manifest"}}, {"name": "data", "emptyDir": {}}]}
    deployment = {"apiVersion": "apps/v1", "kind": "Deployment",
        "metadata": {"name": workload, "namespace": "agents", "labels": labels},
        "spec": {"replicas": 2, "selector": {"matchLabels": {"app": workload}},
                 "template": {"metadata": {"labels": labels}, "spec": pod}}}
    service = {"apiVersion": "v1", "kind": "Service", "metadata": {"name": workload, "namespace": "agents"},
               "spec": {"selector": {"app": workload}, "ports": [{"port": 8000, "targetPort": 8000}]}}
    for resource in (config, deployment, service):
        kube("apply", "-f", "-", body=json.dumps(resource).encode())
    kube("-n", "agents", "rollout", "restart", "deployment/" + workload)
    kube("-n", "agents", "rollout", "status", "deployment/" + workload, "--timeout=180s")
    if env == "prod":
        service["metadata"]["name"] = name
        kube("apply", "-f", "-", body=json.dumps(service).encode())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["export", "deploy", "fail"])
    parser.add_argument("job")
    parser.add_argument("env", choices=["staging", "prod"])
    parser.add_argument("directory")
    parser.add_argument("run_url")
    parser.add_argument("--image")
    args = parser.parse_args()
    if not re.fullmatch(r"DF-[0-9]+", args.job) or not re.fullmatch(r"https://github.com/[\w.-]+/keel/actions/runs/[0-9]+", args.run_url):
        raise ValueError("Invalid pipeline identity")
    directory = Path(args.directory)
    if not re.fullmatch(r"/tmp/keel-pipeline-[0-9]+", str(directory)):
        raise ValueError("Invalid work directory")
    context_path = directory / "context.json"
    if args.action == "export":
        context = api(args.job, args.env, "context")
        status(context, args.run_url, "RUNNING", "构建精确提交的候选镜像")
        write_snapshot(directory / "project", context["snapshot"]["files"])
        context_path.write_text(json.dumps(context, ensure_ascii=False), encoding="utf-8")
        with tarfile.open(directory / "project.tar.gz", "w:gz") as archive:
            archive.add(directory / "project", arcname=".")
        print("Exported immutable candidate", context["sha"])
        return
    context = json.loads(context_path.read_text(encoding="utf-8"))
    if args.action == "fail":
        failure = directory / "evaluation-failure.json"
        message = failure.read_text(encoding="utf-8") if failure.exists() else "流水线失败，请打开 CI 查看失败步骤并重试"
        status(context, args.run_url, "FAILED", message, failure.exists())
        return
    image = args.image or ""
    if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", image):
        raise ValueError("Immutable image digest is required")
    api(args.job, args.env, "prepare", sha=context["sha"])
    deploy(context, image)
    ready = api(args.job, args.env, "ready", sha=context["sha"])
    if not ready.get("passed"):
        print(json.dumps({"selfCheck": ready}, ensure_ascii=False))
        raise RuntimeError("Runtime self-check failed")
    gate_source = Path(__file__).with_name("gate.py").read_text(encoding="utf-8")
    result = inside(gate_source, {"credentials": credentials(), "context": context, "image": image})
    print(json.dumps({"gate": result}, ensure_ascii=False))
    if not result.get("passed"):
        (directory / "evaluation-failure.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        raise RuntimeError("Runtime evaluation failed; release remains blocked")
    if args.env == "prod":
        api(args.job, args.env, "prepare", sha=context["sha"])
        deploy(context, image)
        if not api(args.job, args.env, "ready", sha=context["sha"]).get("passed"):
            raise RuntimeError("Provisioned prod runtime self-check failed")
    released = api(args.job, args.env, "complete", sha=context["sha"], image=image, gateRunId=result["gateRunId"])
    print(json.dumps({"release": released}, ensure_ascii=False))
    status(context, args.run_url, "SUCCEEDED", "部署、运行自检、可见与隐藏门禁及发布完成")


if __name__ == "__main__":
    main()
