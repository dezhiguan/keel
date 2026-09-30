#!/usr/bin/env python3
"""afterFileEdit 钩子：拦截 keel-gate 会在 CI 里拦的那些写法，提前到编辑当下。

命中任何一条返回退出码 2（阻断）。同时记录本轮改过的文件，供 stop 钩子跑定向测试。
"""
import json
import os
import re
import sys

ROOT = os.getcwd()
TOUCHED = os.path.join(ROOT, ".cursor", ".touched-files")

SOURCE_EXT = {".py", ".java", ".ts", ".tsx", ".vue", ".yaml", ".yml", ".sql", ".json"}

# (正则, 说明, 只在哪些路径下检查 or None 表示全部, 哪些路径豁免)
RULES = [
    (r"sk-[A-Za-z0-9_\-]{20,}", "疑似厂商 API Key 明文。密钥只能放 LiteLLM 的 Secret", None, ()),
    (r"(?i)(api[_-]?key|secret[_-]?key)\s*[:=]\s*[\"'][^\"'$#{]{16,}[\"']",
     "疑似硬编码密钥。改为从环境变量读取", None, ()),
    (r"/api/public/(traces|observations|scores|metrics|sessions|dataset-run-items)\b",
     "Langfuse v3 接口，在 v4 返回 404。改用 /v2/observations、/v2/metrics、/v3/scores、/experiments", None, ()),
    (r"/spend/logs(?!/v2)",
     "LiteLLM 旧花费接口最多返回 1 万行。改用 /spend/logs/v2 或 /global/spend/report", None, ()),
    (r"OtlpGrpcSpanExporter",
     "Langfuse 不收 gRPC，只支持 OTLP/HTTP。改用 OtlpHttpSpanExporter", None, ()),
    (r"(?i)\b(update|delete)\s+(from\s+)?audit_event\b",
     "audit_event 只追加，应用账号没有 UPDATE/DELETE 权限", None, ()),
    (r"^\s*(?:from|import)\s+(openai|dashscope|anthropic|zhipuai)\b",
     "禁止直连厂商 SDK，模型调用统一走 ctx.llm。openai 包只允许在 sdk-python/keel/llm/ 下用于访问 LiteLLM",
     (".py",), ("sdk-python/keel/llm/",)),
    (r"\b(com\.alibaba\.dashscope|com\.openai\.client)\b",
     "禁止直连厂商 SDK，模型调用统一走 ctx.llm()", (".java",), ()),
    (r"^\s*(?:from|import)\s+langfuse\b",
     "运行时只依赖标准 OTel。langfuse 包只允许在 keel/eval/ 和 keel/gate/ 下使用",
     (".py",), ("sdk-python/keel/eval/", "sdk-python/keel/gate/")),
]


def read_input():
    try:
        return json.loads(sys.stdin.read() or "{}")
    except json.JSONDecodeError:
        return {}


def file_path_of(payload):
    for key in ("file_path", "filePath", "path"):
        value = payload.get(key)
        if isinstance(value, str) and value:
            return value
    edits = payload.get("edits") or payload.get("file_edits")
    if isinstance(edits, list) and edits:
        first = edits[0]
        if isinstance(first, dict):
            return first.get("file_path") or first.get("path")
    return None


def record(rel_path):
    try:
        os.makedirs(os.path.dirname(TOUCHED), exist_ok=True)
        seen = set()
        if os.path.exists(TOUCHED):
            with open(TOUCHED, encoding="utf-8") as handle:
                seen = {line.strip() for line in handle if line.strip()}
        if rel_path not in seen:
            with open(TOUCHED, "a", encoding="utf-8") as handle:
                handle.write(rel_path + "\n")
    except OSError:
        pass


def main():
    payload = read_input()
    path = file_path_of(payload)
    if not path:
        return 0

    abs_path = path if os.path.isabs(path) else os.path.join(ROOT, path)
    rel_path = os.path.relpath(abs_path, ROOT).replace(os.sep, "/")
    ext = os.path.splitext(abs_path)[1]

    if ext not in SOURCE_EXT or not os.path.isfile(abs_path):
        return 0

    record(rel_path)

    try:
        with open(abs_path, encoding="utf-8", errors="ignore") as handle:
            content = handle.read()
    except OSError:
        return 0

    hits = []
    for pattern, reason, only_ext, exempt in RULES:
        if only_ext and ext not in only_ext:
            continue
        if any(rel_path.startswith(prefix) for prefix in exempt):
            continue
        match = re.search(pattern, content, re.MULTILINE)
        if match:
            line_no = content[: match.start()].count("\n") + 1
            hits.append(f"  {rel_path}:{line_no}  {reason}")

    if not hits:
        return 0

    print(f"Keel 约束检查未通过（{rel_path}）：", file=sys.stderr)
    print("\n".join(hits), file=sys.stderr)
    print("详见 .cursor/rules/external-apis.mdc 和 AGENTS.md。", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
