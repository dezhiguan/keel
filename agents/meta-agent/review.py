"""Review immutable candidate snapshots against trusted requirements."""

import json
from keel.protocol.errors import ErrorCode, KeelError
from model_steps import load_prompt

async def review_candidate(work, ctx):
    """Bootstrap S4 review from the immutable private candidate commit."""
    snapshot = work.get("snapshot") or {}
    sha = (work.get("delivery") or {}).get("sha")
    trusted_tools = ((work.get("draft") or {}).get("manifest") or {}).get("spec", {}).get("tools") or []
    if snapshot.get("sha") != sha or not isinstance(snapshot.get("files"), list):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "代码评审快照与候选提交不一致")
    allowed = {"role.py", "app.py", "agent.yaml", "prompts/role.md", "tests/test_role.py", "evals/seed.jsonl"}
    files = {item.get("path"): item.get("content") for item in snapshot["files"]
             if isinstance(item, dict) and item.get("path") in allowed and isinstance(item.get("content"), str)}
    if not {"role.py", "app.py", "agent.yaml", "prompts/role.md", "tests/test_role.py"}.issubset(files):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "候选提交缺少代码评审所需文件")
    if sum(len(value) for value in files.values()) > 120_000:
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "代码评审输入超出模型上下文上限")
    checks = ("modelAccess", "toolAuthorization", "audit", "visibleCaseOverfit",
              "releaseApproval", "behaviorTests", "hardcodedPrompt")
    prompt_ref = await load_prompt(ctx, "review")
    prompt = prompt_ref.compile(
        checks=','.join((f'"{name}":bool' for name in checks)))
    data = {"sha": sha, "trustedTools": trusted_tools,
            "trustedSpec": (work.get("draft") or {}).get("spec") or {},
            "runtimeContract": {"executeResult": {"type": "object", "additionalProperties": True},
                                "decisionGate": "compare result.decision only; additional keys are allowed",
                                "promptOutputScope": "ctx.llm.chat response only, before execute assembles its result"},
            "files": files,
            "numberedFiles": {path: "\n".join(f"{i}: {line}" for i, line in enumerate(content.splitlines(), 1))
                              for path, content in files.items()}}
    instructions = prompt
    verified = False
    for _ in range(3):
        result = await ctx.llm.chat([{"role": "system", "content": instructions},
                                     {"role": "user", "content": json.dumps(data, ensure_ascii=False)}], prompt=prompt_ref)
        raw = str(result or "").strip()
        start, end = raw.find("{"), raw.rfind("}")
        try:
            report = json.loads(raw[start:end + 1]) if start >= 0 and end > start else {}
        except json.JSONDecodeError:
            report = {}
        if (isinstance(report, dict) and isinstance(report.get("approved"), bool)
                and isinstance(report.get("checks"), dict) and isinstance(report.get("findings"), list)
                and all(isinstance(report["checks"].get(name), bool) for name in checks)
                and all(isinstance(item, dict) and item.get("path") in files
                        and type(item.get("line")) is int and item["line"] > 0
                        and item.get("severity") in ("HIGH", "MID", "LOW")
                        and isinstance(item.get("detail"), str) and item["detail"].strip()
                        for item in report["findings"])
                and ((report["approved"] and all(report["checks"].values()))
                     or any(item["severity"] in ("HIGH", "MID") for item in report["findings"]))):
            report["approved"] = (report["approved"] and all(report["checks"].values())
                                  and not any(item["severity"] in ("HIGH", "MID") for item in report["findings"]))
            if not report["approved"] and not verified:
                verified = True
                instructions = prompt + ("\n本轮只核验初审列出的风险是否成立，不重新解释被审计提示词为平台需求。"
                           "user 数据包含初审引用行附近的源码 findingEvidence 与受信协议；"
                           "逐条核对实际代码和受信需求，源码不足以确认风险时不得捏造缺失的行为。"
                           "初审结论可能错误，不能当作事实。删除引用不存在、已被类型/异常保护覆盖、"
                           "混淆模型与岗位输出契约、或虚构契约限制的发现。仅保留能在当前快照实际触发的风险，"
                           "重新给出完整 JSON 评审；若均为误报应通过。")
                evidence = []
                for finding in report["findings"]:
                    lines = files[finding["path"]].splitlines()
                    start, end = max(0, finding["line"] - 16), min(len(lines), finding["line"] + 15)
                    evidence.append({"finding": finding, "source": "\n".join(
                        f"{i + 1}: {lines[i]}" for i in range(start, end))})
                data = {key: value for key, value in data.items() if key not in ("files", "numberedFiles")}
                data["findingEvidence"] = evidence
                continue
            return {"sha": sha, "reviewer": "meta-agent-bootstrap", **report}
        instructions += "\n上一版格式无效，严格按指定 JSON 结构重写。"
    raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "模型代码评审未返回有效结构化结论")
