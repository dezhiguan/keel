"""Score meta-agent contract answers. Every scorer receives expected, answer, tags."""

import json
import re

_CASE_ID = re.compile(r"a-[A-Za-z0-9_-]{1,45}")
_SECRET = re.compile(r"sk-|api[_-]?key|password|BEGIN PRIVATE", re.IGNORECASE)


def baseline(expected, answer, tags) -> float:
    if isinstance(expected, str):
        try:
            expected = json.loads(expected)
        except (TypeError, json.JSONDecodeError):
            return 0.0
    if not isinstance(expected, dict):
        return 0.0
    code = expected.get("errorCode")
    if code:
        return 1.0 if answer == code else 0.0
    try:
        body = json.loads(answer)
    except (json.JSONDecodeError, TypeError):
        return 0.0
    kind = expected.get("kind")
    if kind == "draft":
        return _draft(body, expected)
    if kind == "eval":
        return _expand(body, expected)
    if kind == "review":
        return float(isinstance(body, dict) and body.get("sha") == expected.get("sha")
                     and body.get("approved") is True and body.get("findings") == []
                     and isinstance(body.get("checks"), dict)
                     and all(body["checks"].get(name) is True for name in expected["checks"]))
    if kind == "generation":
        from candidate_validation import parse
        if not isinstance(body, dict) or body.get("skillVersion") != expected.get("skillVersion"):
            return 0.0
        _, errors = parse(json.dumps(body.get("candidate")), template=expected["template"],
                          selected_tools=expected.get("tools", []))
        return float(not errors)
    return 0.0


def _draft(body: dict, expected: dict) -> float:
    if not isinstance(body, dict):
        return 0.0
    manifest, spec = body.get("manifest"), body.get("spec")
    if not isinstance(manifest, dict) or not isinstance(spec, dict):
        return 0.0
    meta = manifest.get("metadata") if isinstance(manifest.get("metadata"), dict) else {}
    agent_spec = manifest.get("spec") if isinstance(manifest.get("spec"), dict) else {}
    runtime = agent_spec.get("runtime") if isinstance(agent_spec.get("runtime"), dict) else {}
    models = agent_spec.get("models") if isinstance(agent_spec.get("models"), dict) else {}
    budget = models.get("budget") if isinstance(models.get("budget"), dict) else {}
    prompts = agent_spec.get("prompts") if isinstance(agent_spec.get("prompts"), dict) else {}
    guard = agent_spec.get("guardrails") if isinstance(agent_spec.get("guardrails"), dict) else {}
    name = expected.get("name")
    checks = [
        manifest.get("apiVersion") == "keel/v1",
        manifest.get("kind") == "Agent",
        meta.get("name") == name,
        meta.get("displayName") == expected.get("title"),
        meta.get("owner") == expected.get("owner"),
        runtime.get("endpoint") == f"http://{name}.agents.svc.cluster.local:8000",
        runtime.get("type") == "code",
        runtime.get("language") == "python",
        agent_spec.get("kind") == "workflow",
        (agent_spec.get("auth") or {}).get("audience") == name,
        models.get("default") == expected.get("model", "qwen3.8-flash"),
        budget.get("dailyCny") == expected.get("dailyCny"),
        agent_spec.get("knowledge") == [],
        agent_spec.get("delegates") == expected.get("delegates", []),
        guard.get("inputInjection") == "enforce",
        guard.get("grounding") == "off",
        guard.get("pii") == "mask",
        prompts.get("source") == "langfuse",
        prompts.get("items") == [{"name": "role", "type": "text"}],
        (agent_spec.get("audit") or {}).get("captureFields") == ["request_id"],
        (agent_spec.get("eval") or {}).get("dataset") == f"{name}/seed",
        agent_spec.get("tools") == expected.get("tools"),
        all(spec.get(key) == expected.get(key) for key in ("title", "goal", "users", "io", "success")),
        isinstance(spec.get("risks"), list),
        isinstance(spec.get("pending"), list),
        _SECRET.search(json.dumps(body, ensure_ascii=False)) is None,
    ]
    return 1.0 if all(checks) else 0.0


def _expand(body: dict, expected: dict) -> float:
    cases = body.get("cases") if isinstance(body, dict) else None
    if not isinstance(cases, list) or not 1 <= len(cases) <= 20:
        return 0.0
    forbidden_ids = set(expected.get("forbiddenIds") or [])
    forbidden_inputs = {_canon(item) for item in expected.get("forbiddenInputs") or []}
    seen, inputs = set(), set()
    for item in cases:
        if not isinstance(item, dict):
            return 0.0
        case_id, data, wanted = item.get("caseId"), item.get("input"), item.get("expected")
        tags, reason = item.get("tags"), item.get("reason")
        if (not isinstance(case_id, str) or not _CASE_ID.fullmatch(case_id) or case_id in forbidden_ids
                or case_id in seen or not isinstance(data, dict) or not isinstance(wanted, dict)):
            return 0.0
        encoded = _canon(data)
        if encoded in forbidden_inputs or encoded in inputs:
            return 0.0
        if (not isinstance(tags, list) or not tags or any(not isinstance(tag, str) or not tag for tag in tags)
                or not isinstance(reason, str) or not reason.strip()):
            return 0.0
        seen.add(case_id)
        inputs.add(encoded)
    return 1.0


def _canon(value) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False)
