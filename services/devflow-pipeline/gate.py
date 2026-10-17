"""Trusted CI evaluates runtime responses; hidden cases never enter output or candidate code."""
import concurrent.futures
import json
import os
import sys
import uuid

import httpx

from keel.eval.run import invoke_text
from keel.holdout_run import ci_headers


def request(path, body=None):
    response = httpx.request("GET" if body is None else "POST", os.environ["KEEL_SERVER_URL"] + path,
                            json=body, headers=ci_headers(), timeout=180)
    value = response.json()
    if response.status_code >= 400 or value.get("code") != "OK":
        raise RuntimeError(f"CI API rejected: {response.status_code} {value.get('code')} {value.get('message')}")
    return value.get("data") or {}


def score(expected, answer):
    try:
        output = json.loads(answer)
        if isinstance(output, dict) and isinstance(output.get("output"), dict):
            output = output["output"]
        return float(isinstance(expected, dict) and isinstance(output, dict)
                     and isinstance(expected.get("decision"), str)
                     and output.get("decision") == expected["decision"])
    except (ValueError, TypeError):
        return 0.0


def evaluate(cases, endpoint, env, run_id, failures=None):
    def one(case):
        data = case.get("input")
        text = data.get("text", "") if isinstance(data, dict) else str(data or "")
        try:
            answer = invoke_text(endpoint, text, eval_run_id=run_id, env=env)
            return score(case.get("expected"), answer), answer
        except Exception:
            return 0.0, ""
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        scores = list(executor.map(one, cases))
    buckets = {}
    for case, (value, answer) in zip(cases, scores, strict=True):
        if value < 1 and failures is not None and len(failures) < 5:
            try:
                output = json.loads(answer)
                if isinstance(output, dict) and isinstance(output.get("output"), dict):
                    output = output["output"]
                if not isinstance(output, dict):
                    output = {}
            except (TypeError, ValueError):
                output = {}
            failures.append({"caseId": case.get("caseId"), "expected": case.get("expected"),
                             "actualDecision": str(output.get("decision", "NO_VALID_RESULT"))[:40],
                             "reason": str(output.get("block_reason") or output.get("reason") or "")[:500]})
        for tag in case.get("tags") or ["untagged"]:
            buckets.setdefault(tag, []).append(value)
    return [{"tag": tag, "score": sum(values) / len(values), "count": len(values)}
            for tag, values in sorted(buckets.items())]


def run(config):
    os.environ.update(config["credentials"])
    context = config["context"]
    env, job = context["env"], context["jobId"]
    run_id = uuid.uuid4().hex
    endpoint = context["manifest"]["spec"]["runtime"]["endpoint"]
    failures = []
    visible = evaluate(context["visibleSeeds"], endpoint, env, run_id, failures)
    if not visible or any(item["score"] < 0.85 for item in visible):
        return {"passed": False, "phase": "visible", "byTag": visible, "visibleFailures": failures}
    hidden = request(f"/api/v1/devflow/holdout/{job}")["cases"]
    hidden_scores = evaluate(hidden, endpoint, env, run_id)
    result = {"jobId": job, "env": env, "sha": context["sha"], "image": config["image"],
              "gateRunId": run_id, "visibleByTag": {item["tag"]: item["score"] for item in visible},
              "byTag": hidden_scores}
    try:
        request("/api/v1/devflow/holdout-results", result)
    except RuntimeError as error:
        return {"passed": False, "phase": "hidden", "visibleByTag": result["visibleByTag"],
                "hiddenByTag": hidden_scores, "reason": str(error)}
    return {"passed": True, "gateRunId": run_id, "visibleByTag": result["visibleByTag"],
            "hiddenByTag": hidden_scores}


if __name__ == "__main__":
    print(json.dumps(run(json.load(sys.stdin)), ensure_ascii=False))
