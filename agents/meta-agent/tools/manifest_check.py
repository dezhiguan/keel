"""Validate a drafted agent.yaml against the keel/v1 contract plus the meta-agent's own rules."""

import json
import re

import jsonschema

from keel.manifest import _contract_file

NAME = re.compile(r"^[a-z][a-z0-9-]{1,38}[a-z0-9]$")
RISK_ORDER = {"low": 0, "mid": 1, "high": 2}
SELF = "meta-agent"

_validator = jsonschema.Draft202012Validator(json.loads(_contract_file("manifest.schema.json")))


def check(manifest: dict, target_agent: str | None, catalog: dict[str, dict] | None) -> list[str]:
    """Return human-readable errors; an empty list means the draft can be shown to a person."""
    errors = [f"{'/'.join(str(p) for p in e.absolute_path) or '(根)'}: {e.message}"
              for e in sorted(_validator.iter_errors(manifest), key=lambda e: list(e.absolute_path))]
    if manifest.get("kind", "Agent") != "Agent":
        errors.append("kind 必须是 Agent")
    name = (manifest.get("metadata") or {}).get("name")
    if not isinstance(name, str) or not NAME.match(name):
        errors.append("metadata.name 不合规：小写字母开头，3～40 位，字母数字和连字符")
    elif name == SELF:
        errors.append("不能生成或修改 meta-agent 自己")
    elif target_agent and name != target_agent:
        errors.append(f"metadata.name 应为 {target_agent}，实际是 {name}")
    for tool in (manifest.get("spec") or {}).get("tools") or []:
        tool_name = tool.get("name")
        risk = tool.get("risk", "low")
        if risk == "high" and tool.get("approval") != "required":
            errors.append(f"工具 {tool_name} 是 high 风险，必须 approval: required")
        if catalog is None:
            continue
        registered = catalog.get(tool_name)
        if registered is None:
            errors.append(f"工具 {tool_name} 不在工具注册表里")
        elif RISK_ORDER.get(risk, 0) < RISK_ORDER.get(registered.get("risk", "low"), 0):
            errors.append(f"工具 {tool_name} 的风险不能低于登记值 {registered.get('risk')}")
    return errors
