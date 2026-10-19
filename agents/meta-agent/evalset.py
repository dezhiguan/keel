"""Expand human-authored role exams without changing the original cases."""

import json
import re

from keel.protocol.errors import ErrorCode, KeelError
from model_steps import load_prompt


async def expand(work: dict, ctx) -> dict:
    seeds = work.get("humanSeeds") or []
    if not isinstance(seeds, list) or not seeds:
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "S2 缺少人工种子用例")
    name = work["requirement"]["target_agent"]
    prompt_ref = await load_prompt(ctx, "expand")
    prompt = prompt_ref.compile(
        target_agent=name,
        confirmed_spec=json.dumps(work.get('draft', {}).get('spec') or work['requirement'], ensure_ascii=False),
        human_seeds=json.dumps(seeds, ensure_ascii=False))
    errors = []
    for _ in range(3):
        reply = await ctx.llm.chat([{"role": "user", "content": prompt}], prompt=prompt_ref)
        result, errors = parse(reply, seeds)
        if not errors:
            return result
        prompt += "\n上一版格式不合规，请完整重写：" + "；".join(errors[:5])
    raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "评测扩充失败：" + "；".join(errors[:5]))


def parse(reply: str | None, human: list[dict]) -> tuple[dict, list[str]]:
    raw = str(reply or "").strip()
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end < start:
        return {}, ["缺少 JSON 对象"]
    try:
        value = json.loads(raw[start:end + 1])
    except json.JSONDecodeError as error:
        return {}, [f"JSON 解析失败：{error.msg}，第{error.lineno}行第{error.colno}列"]
    cases = value.get("cases") if isinstance(value, dict) else None
    if not isinstance(cases, list) or not 1 <= len(cases) <= 20:
        return {}, ["扩充用例应为 1～20 条"]
    seen = {item.get("caseId") for item in human if isinstance(item, dict)}
    inputs = {json.dumps(item.get("input"), sort_keys=True, ensure_ascii=False) for item in human if isinstance(item, dict)}
    clean = []
    for index, item in enumerate(cases, 1):
        if not isinstance(item, dict):
            return {}, ["用例必须为对象"]
        case_id, data, expected = item.get("caseId"), item.get("input"), item.get("expected")
        tags, reason = item.get("tags"), item.get("reason")
        prefix = f"第{index}条扩充用例"
        if not isinstance(case_id, str) or not re.fullmatch(r"a-[A-Za-z0-9_-]{1,45}", case_id):
            return {}, [prefix + "caseId必须以a-开头并匹配a-[A-Za-z0-9_-]{1,45}，不能包含中文或空格"]
        if case_id in seen:
            return {}, [prefix + "caseId重复人工原例或本批其他用例编号"]
        if not isinstance(data, dict) or not isinstance(expected, dict):
            return {}, [prefix + "input和expected都必须是JSON对象，不能是字符串、数组或null"]
        if not isinstance(tags, list) or not tags or any(not isinstance(tag, str) or not tag for tag in tags):
            return {}, [prefix + "tags必须是非空字符串数组"]
        if not isinstance(reason, str) or not reason.strip():
            return {}, [prefix + "reason必须是对应已确认H1的非空字符串"]
        encoded = json.dumps(data, sort_keys=True, ensure_ascii=False)
        if len(json.dumps(item, ensure_ascii=False).encode("utf-8")) > 8_000:
            return {}, ["单条扩充用例超过 8KB"]
        if encoded in inputs:
            return {}, ["扩充用例重复人工输入或其他扩充输入"]
        seen.add(case_id)
        inputs.add(encoded)
        clean.append({"caseId": case_id, "input": data, "expected": expected, "tags": tags, "reason": reason})
    return {"cases": clean}, []
