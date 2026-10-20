"""A bounded Kubernetes experiment job. Stdout contains aggregates, never case text."""
import asyncio
import json
import math
import os
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from keel.eval.run import invoke_text, load_scorers


def summarize(items, *, agent, dataset, run_id, env, gate, url=None, baseline=None):
    buckets = defaultdict(list)
    failures = []
    for item in items:
        values = []
        for score in item.evaluations:
            value = float(score.value)
            if not math.isfinite(value) or not 0 <= value <= 1:
                raise ValueError("评分器必须返回 0 到 1 的有限数值")
            buckets[score.name].append(value)
            values.append(value)
        if not values:
            raise ValueError("用例没有评分结果")
        if min(values) < gate["minScore"]:
            failures.append({"caseId": str(getattr(item.item, "id", "")), "traceId": item.trace_id})
    if not items:
        raise ValueError("数据集没有有效用例")
    dimensions = []
    regression = []
    baseline = baseline or {}
    for tag, values in buckets.items():
        candidate = sum(values) / len(values)
        prod = baseline.get(tag)
        delta = None if prod is None else (candidate - prod) * 100
        exceeded = delta is not None and delta < -gate["maxRegression"]
        if exceeded:
            regression.append(tag)
        dimensions.append({"tag": tag, "cases": len(values), "candidateScore": candidate,
                           "prodScore": prod, "deltaPt": delta,
                           "verdict": "EXCEEDED" if exceeded else "IMPROVED" if delta is not None and delta >= 0 else "TOLERATED"})
    score = sum(d["candidateScore"] for d in dimensions) / len(dimensions)
    return {"agent": agent, "env": env, "dataset": dataset, "caseCount": len(items),
            "candidateVersion": run_id, "prodVersion": "production" if baseline else "",
            "scoreTotal": score, "passed": score >= gate["minScore"] and not regression,
            "gate": {**gate, "byTag": True}, "reason": "回归超限：" + ", ".join(regression) if regression else None,
            "ranAt": datetime.now(timezone.utc).isoformat(), "dimensions": dimensions,
            "newFailures": failures, "langfuseUrl": url}


class IncompleteExperiment(ValueError):
    """An experiment omitted or duplicated active cases; no partial gate is valid."""


def require_complete(expected_ids, items):
    expected = set(expected_ids)
    actual = [str(getattr(item.item, "id", "")) for item in items]
    if len(actual) != len(expected) or len(set(actual)) != len(actual) or set(actual) != expected:
        raise IncompleteExperiment("实验未完整覆盖全部有效用例")


def prompt_evidence(items):
    versions = {}
    for item in items:
        output = item.output
        if not isinstance(output, dict) or not isinstance(output.get("meta"), dict):
            raise ValueError("缺少运行提示词证据")
        meta = output["meta"]
        if meta.get("prompt_fallback"):
            raise ValueError("提示词兜底或单次调用版本冲突")
        reported = meta.get("prompt_versions", {})
        if not isinstance(reported, dict):
            raise ValueError("提示词版本证据无效")
        for name, version in reported.items():
            if not isinstance(name, str) or type(version) is not int or version < 1:
                raise ValueError("提示词版本证据无效")
            if name in versions and versions[name] != version:
                raise ValueError("同一评测使用了多个提示词版本")
            versions[name] = version
    return versions


async def invoke_case(endpoint, text, **options):
    # Langfuse invokes sync tasks on its event loop; preserve trace context while
    # moving the blocking HTTP stream into its bounded concurrency slot.
    return await asyncio.to_thread(invoke_text, endpoint, text, **options)


def case_text(payload):
    """Preserve text envelopes and Langfuse-decoded structured case inputs."""
    if isinstance(payload, dict):
        if "text" in payload:
            return payload["text"]
        return json.dumps(payload, ensure_ascii=False)
    return str(payload)


def evaluation_concurrency():
    value = int(os.environ.get("KEEL_EVAL_CONCURRENCY", "4"))
    if not 1 <= value <= 4:
        raise ValueError("KEEL_EVAL_CONCURRENCY 必须是 1 到 4 的整数")
    return value


def run_experiment():
    from langfuse import Langfuse, Evaluation
    from opentelemetry import trace
    import yaml
    manifest = yaml.safe_load(Path("agent.yaml").read_text())
    agent = manifest["metadata"]["name"]
    spec = manifest["spec"]
    env = os.environ["KEEL_EVAL_ENV"]
    dataset_name = os.environ["KEEL_EVAL_DATASET"]
    run_id = os.environ["KEEL_EVAL_RUN_ID"]
    endpoint = os.environ["KEEL_EVAL_ENDPOINT"]
    concurrency = evaluation_concurrency()
    scorers = load_scorers("evals/scorers.py")
    config = spec.get("eval", {}).get("gate", {})
    gate = {"minScore": float(config.get("minScore", .85)),
            "maxRegression": float(config.get("maxRegression", .02)) * 100}
    client = Langfuse(environment=env, additional_headers={"x-langfuse-ingestion-version": "4"}, timeout=30)
    dataset = client.get_dataset(dataset_name)
    active_ids = {str(item.id) for item in dataset.items if str(item.status) == "ACTIVE"}
    if not active_ids or len(active_ids) > 200:
        raise ValueError("数据集必须包含 1 到 200 条有效用例")
    baseline = json.loads(os.environ.get("KEEL_EVAL_BASELINE", "{}"))

    async def task(*, item, **kwargs):
        span = trace.get_current_span()
        span.set_attributes({"keel.agent": agent, "keel.env": env, "keel.eval_run": run_id})
        payload = item.input
        text = case_text(payload)
        expected = item.expected_output
        options = {"expected_error": expected["errorCode"]} if isinstance(expected, dict) and expected.get("errorCode") else {}
        return await invoke_case(endpoint, text, eval_run_id=run_id, env=env, with_meta=True, **options)

    def evaluate(*, output, expected_output=None, metadata=None, **kwargs):
        tags = (metadata or {}).get("tags") or ["untagged"]
        if not isinstance(tags, list) or not all(isinstance(tag, str) for tag in tags):
            raise ValueError("用例 tags 必须是字符串列表")
        values = [float(scorer(expected_output, output["answer"], tags)) for scorer in scorers]
        if not all(math.isfinite(v) and 0 <= v <= 1 for v in values):
            raise ValueError("评分器结果无效")
        return [Evaluation(name=tag, value=sum(values) / len(values)) for tag in tags]

    try:
        result = dataset.run_experiment(name=agent, run_name=run_id, task=task,
                                        evaluators=[evaluate], max_concurrency=concurrency,
                                        metadata={"agent": agent, "env": env, "keel.eval_run": run_id})
        require_complete(active_ids, result.item_results)
        versions = prompt_evidence(result.item_results)
        measured = summarize(result.item_results, agent=agent, dataset=dataset_name, run_id=run_id,
                             env=env, gate=gate, url=result.dataset_run_url, baseline=baseline)
        return {**measured, "complete": True, "promptVersions": versions}
    finally:
        client.flush()
        client.shutdown()


if __name__ == "__main__":
    try:
        print("KEEL_EVAL_RESULT " + json.dumps(run_experiment(), ensure_ascii=False), flush=True)
    except Exception as exc:
        # Upstream exceptions may contain inputs or credentials. Report only the type.
        print("KEEL_EVAL_ERROR " + type(exc).__name__, flush=True)
        raise SystemExit(1) from None
