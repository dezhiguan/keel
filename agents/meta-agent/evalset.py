"""Expand human-authored role exams without changing the original cases."""

import json

from keel.protocol.errors import ErrorCode, KeelError


async def expand(work: dict, ctx) -> dict:
    seeds = work.get("humanSeeds") or []
    if not isinstance(seeds, list) or not seeds:
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "S2 缺少人工种子用例")
    name = work["requirement"]["target_agent"]
    prompt = (
        f"你是 Keel meta-agent，为研发岗位 {name} 扩充人工评测用例。"
        "只返回 JSON 对象，格式为 {\"cases\":[{\"caseId\":\"a-1\",\"input\":{},\"expected\":{},"
        "\"tags\":[\"boundary\"],\"reason\":\"...\"}]}。"
        "至少 1 条，最多 20 条；覆盖边界、失败、对抗或输入注入。"
        "不可修改人工原例，不可重复原例，不可读写隐藏考题。"
        f"人工原例：{json.dumps(seeds, ensure_ascii=False)}"
    )
    errors = []
    for _ in range(3):
        reply = await ctx.llm.chat([{"role": "user", "content": prompt}])
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
    except json.JSONDecodeError:
        return {}, ["JSON 解析失败"]
    cases = value.get("cases") if isinstance(value, dict) else None
    if not isinstance(cases, list) or not 1 <= len(cases) <= 20:
        return {}, ["扩充用例应为 1～20 条"]
    seen = {item.get("caseId") for item in human if isinstance(item, dict)}
    inputs = {json.dumps(item.get("input"), sort_keys=True, ensure_ascii=False) for item in human if isinstance(item, dict)}
    clean = []
    for item in cases:
        if not isinstance(item, dict):
            return {}, ["用例必须为对象"]
        case_id, data, expected = item.get("caseId"), item.get("input"), item.get("expected")
        tags, reason = item.get("tags"), item.get("reason")
        if (not isinstance(case_id, str) or not case_id.startswith("a-") or len(case_id) > 50
                or case_id in seen or not isinstance(data, dict) or not isinstance(expected, dict)
                or not isinstance(tags, list) or not tags or any(not isinstance(tag, str) or not tag for tag in tags)
                or not isinstance(reason, str) or not reason.strip()):
            return {}, ["扩充用例编号、输入、期望、标签或理由无效"]
        encoded = json.dumps(data, sort_keys=True, ensure_ascii=False)
        if len(json.dumps(item, ensure_ascii=False).encode("utf-8")) > 8_000:
            return {}, ["单条扩充用例超过 8KB"]
        if encoded in inputs:
            return {}, ["扩充用例重复人工输入或其他扩充输入"]
        seen.add(case_id)
        inputs.add(encoded)
        clean.append({"caseId": case_id, "input": data, "expected": expected, "tags": tags, "reason": reason})
    return {"cases": clean}, []
