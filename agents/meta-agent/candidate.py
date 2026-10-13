"""Ask the meta-agent model to author a role implementation, then validate it before Git delivery."""

import ast
import json

from keel.protocol.errors import ErrorCode, KeelError

from roles import ROLES


ALLOWED_IMPORTS = {"json", "re", "typing", "collections", "datetime"}
FORBIDDEN_CALLS = {"eval", "exec", "compile", "open", "__import__", "globals", "locals"}


async def generate(work: dict, ctx) -> dict:
    requirement = work["requirement"]
    name = requirement["target_agent"]
    if name not in ROLES or requirement.get("template") != ROLES[name][0]:
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "研发岗位与原型模板不匹配")
    role, purpose = ROLES[name]
    confirmed = work["draft"]["spec"]
    prompt = (
        "你是 Keel 的 meta-agent，正在为研发数字员工编写实际岗位代码。"
        "只返回 JSON 对象，含 role.py 与 tests/test_role.py 两个 UTF-8 文件的源码："
        '{"files":[{"path":"role.py","content":"..."},{"path":"tests/test_role.py","content":"..."}]}。'
        f"岗位 {name}，模板 {role}，职责：{purpose}\n"
        f"人工 H1 已确认的需求：{json.dumps(confirmed, ensure_ascii=False)}\n"
        f"已选工具：{json.dumps(requirement.get('tools') or [], ensure_ascii=False)}。"
        f"H2 可见评测用例：{json.dumps(work.get('visibleSeeds') or [], ensure_ascii=False)}。"
        "role.py 必须有 async def execute(data: dict, ctx) -> dict，处理非平凡输入并返回结构化结果。"
        "模型推理只能 ctx.llm.chat，外部工具只能 ctx.tools.call，委派只能 ctx.delegate。"
        "绝不自行授予工具、访问隐藏考题、自动合并或发布；缺少人工审批或可信门禁时返回 BLOCKED。"
        "不导入网络、文件、进程或厂商 SDK；role.py 只能导入 json/re/typing/collections/datetime。"
        "tests/test_role.py 至少包含一个覆盖岗位正常输入和一个拒绝/失败场景的 test_ 函数，"
        "用假 ctx 测试行为，不能连接真实服务。不要只测试文件存在或字符串常量。"
    )
    errors = []
    for _ in range(3):
        reply = await ctx.llm.chat([{"role": "user", "content": prompt}])
        candidate, errors = parse(reply)
        if not errors:
            return candidate
        prompt += "\n上一版校验失败，请完整重写并修正：" + "；".join(errors[:6])
    raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "岗位代码校验失败：" + "；".join(errors[:6]))


def parse(reply: str | None) -> tuple[dict, list[str]]:
    raw = str(reply or "").strip()
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end < start:
        return {}, ["缺少 JSON 对象"]
    try:
        data = json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        return {}, ["JSON 解析失败"]
    files = data.get("files") if isinstance(data, dict) else None
    if not isinstance(files, list) or len(files) != 2:
        return {}, ["必须且只能有 role.py 和 tests/test_role.py"]
    mapping = {}
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not isinstance(item.get("content"), str):
            return {}, ["文件结构无效"]
        if item["path"] in mapping:
            return {}, ["文件路径重复"]
        mapping[item["path"]] = item["content"]
    if set(mapping) != {"role.py", "tests/test_role.py"}:
        return {}, ["只允许 role.py 与 tests/test_role.py"]
    errors = []
    trees = {}
    for path, source in mapping.items():
        if len(source.encode("utf-8")) > 40_000:
            errors.append(path + " 超过 40KB")
            continue
        try:
            trees[path] = ast.parse(source, filename=path)
        except SyntaxError:
            errors.append(path + " 语法错误")
    if errors:
        return {}, errors
    role = trees["role.py"]
    if not any(isinstance(node, ast.AsyncFunctionDef) and node.name == "execute" for node in role.body):
        errors.append("role.py 缺少 async execute")
    for node in ast.walk(role):
        if isinstance(node, ast.Import):
            if any(alias.name.split(".")[0] not in ALLOWED_IMPORTS for alias in node.names):
                errors.append("role.py 导入了未经允许的模块")
        elif isinstance(node, ast.ImportFrom):
            if node.level or (node.module or "").split(".")[0] not in ALLOWED_IMPORTS:
                errors.append("role.py 导入了未经允许的模块")
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in FORBIDDEN_CALLS:
            errors.append("role.py 使用了未经允许的执行/文件函数")
    tests = trees["tests/test_role.py"]
    if sum(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_")
           for node in tests.body) < 2:
        errors.append("岗位测试必须包含正常和拒绝/失败两个场景")
    return ({"files": files} if not errors else {}), errors
