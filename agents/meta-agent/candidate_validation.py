"""Static checks for generated candidate files; no model or delivery side effects."""

import ast
import json
import re
from tool_contracts import TOOL_CONTRACTS

ALLOWED_IMPORTS = {"json", "re", "typing", "collections", "datetime"}
FORBIDDEN_CALLS = {"eval", "exec", "compile", "open", "__import__", "globals", "locals"}

def _async_test_errors(tree):
    marks = {"pytest.mark.asyncio", "pytest.mark.anyio"}
    for node in tree.body:
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name == "pytest":
                    marks.update(f"{alias.asname or alias.name}.mark.{kind}" for kind in ("asyncio", "anyio"))
        if isinstance(node, ast.ImportFrom) and node.module == "pytest":
            for alias in node.names:
                if alias.name == "mark":
                    marks.update(f"{alias.asname or alias.name}.{kind}" for kind in ("asyncio", "anyio"))

    def marked(expressions):
        return any(isinstance(node, ast.Attribute) and ast.unparse(node) in marks
                   for expression in expressions for node in ast.walk(expression))

    module_marks = [node.value for node in tree.body if isinstance(node, ast.Assign)
                    and any(isinstance(target, ast.Name) and target.id == "pytestmark" for target in node.targets)]
    if marked(module_marks):
        return []
    return [f"tests/test_role.py:{node.lineno} {node.name} 是未标记的异步测试；"
            "添加@pytest.mark.asyncio或模块pytestmark，或改为同步测试实际调用asyncio.run"
            for node in tree.body if isinstance(node, ast.AsyncFunctionDef)
            and node.name.startswith("test_") and not marked(node.decorator_list)]


def _shared_mock_errors(tree):
    """Reject finite side_effect lists shared by repeated execute calls, without running code."""
    errors = []
    for function in tree.body:
        if not isinstance(function, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        finite = {}
        for statement in function.body:
            if isinstance(statement, ast.Assign):
                for target in statement.targets:
                    if (isinstance(target, ast.Attribute) and target.attr == "side_effect"
                            and isinstance(statement.value, (ast.List, ast.Tuple))):
                        finite[ast.unparse(target)] = target
            if not isinstance(statement, (ast.For, ast.AsyncFor, ast.While)):
                continue
            calls = [node for node in ast.walk(statement) if isinstance(node, ast.Call)
                     and ((isinstance(node.func, ast.Name) and node.func.id == "execute")
                          or (isinstance(node.func, ast.Attribute) and node.func.attr == "execute"))]
            contexts = {ast.unparse(call.args[1]) for call in calls if len(call.args) > 1
                        and isinstance(call.args[1], ast.Name)}
            resets = {ast.unparse(target) for node in statement.body if isinstance(node, ast.Assign)
                      for target in node.targets}
            for name in finite:
                if any(name.startswith(ctx + ".") for ctx in contexts) and not any(
                        name == reset or name.startswith(reset + ".") for reset in resets):
                    errors.append(f"tests/test_role.py:{statement.lineno} {function.name} 循环共享有限{name}；"
                                  "改为pytest.mark.parametrize或在循环内重建ctx/side_effect，避免第二次execute耗尽Mock")
    return errors


def _prompt_call_errors(tree, prompt_names):
    assignments = {target.id: node.value for node in ast.walk(tree) if isinstance(node, ast.Assign)
                   for target in node.targets if isinstance(target, ast.Name)}

    def resolve(node):
        seen = set()
        while isinstance(node, ast.Name) and node.id in assignments and node.id not in seen:
            seen.add(node.id)
            node = assignments[node.id]
        return node

    errors = []
    for node in ast.walk(tree):
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr == "chat" and isinstance(node.func.value, ast.Attribute)
                and node.func.value.attr == "llm" and isinstance(node.func.value.value, ast.Name)
                and node.func.value.value.id == "ctx"):
            for keyword in node.keywords:
                value = resolve(keyword.value)
                if (keyword.arg == "prompt" and isinstance(value, ast.Call)
                        and isinstance(value.func, ast.Attribute) and value.func.attr == "compile"
                        and isinstance(value.func.value, ast.Name) and value.func.value.id in prompt_names):
                    errors.append("ctx.llm.chat的prompt参数必须保留ctx.prompt返回的Prompt对象，不能传compile后的字符串")
        if not isinstance(node, ast.Dict):
            continue
        values = {key.value: value for key, value in zip(node.keys, node.values)
                  if isinstance(key, ast.Constant) and isinstance(key.value, str)}
        if not (isinstance(values.get("role"), ast.Constant) and values["role"].value == "system"):
            continue
        content = resolve(values.get("content"))
        if not (isinstance(content, ast.Call) and isinstance(content.func, ast.Attribute)
                and content.func.attr == "compile" and isinstance(content.func.value, ast.Name)
                and content.func.value.id in prompt_names):
            continue
        dynamic = [keyword.arg for keyword in content.keywords if keyword.arg in ("input", "evidence")
                   and not (isinstance(keyword.value, ast.Constant) and isinstance(keyword.value.value, str))]
        if dynamic:
            errors.append("system提示词不能compile动态原文参数 " + ",".join(dynamic)
                          + "；改为编译user字段说明字符串，将真实input/evidence放在user消息JSON中")
    return errors


def expand_repair_edits(raw: str, previous: dict) -> str:
    start, end = raw.find("{"), raw.rfind("}")
    try:
        value = json.loads(raw[start:end + 1])
    except (ValueError, TypeError):
        return raw
    if not isinstance(value, dict) or "edits" not in value:
        return raw
    edits = value["edits"]
    if not isinstance(edits, list) or not 1 <= len(edits) <= 30:
        raise ValueError("edits 必须包含 1 到 30 个精确补丁")
    files = {item["path"]: item["content"] for item in previous["files"]}
    for index, edit in enumerate(edits, 1):
        if not isinstance(edit, dict) or edit.get("path") not in {"role.py", "prompts/role.md", "tests/test_role.py"}:
            raise ValueError("补丁只允许修改三个岗位文件")
        path, old, new = edit["path"], edit.get("old"), edit.get("new")
        if not isinstance(old, str) or not old or not isinstance(new, str) or len(new) > 50000:
            raise ValueError("补丁 old/new 必须为有界文本，old 不能为空")
        matches = files[path].count(old)
        if matches != 1:
            raise ValueError(f"补丁 #{index} ({path}) old 锚点匹配 {matches} 次；必须恰好1次。"
                             "请逐字引用上轮快照，并加上函数名等上下文区分重复断言")
        files[path] = files[path].replace(old, new, 1)
    return json.dumps({"files": [{"path": path, "content": content} for path, content in files.items()]}, ensure_ascii=False)


def _task_id_case_errors(tree):
    """A DF-id regex must uppercase the text or each match in the same statement."""
    errors = []
    seen = set()
    for func in ast.walk(tree):
        if not isinstance(func, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for stmt in func.body:
            for call in ast.walk(stmt):
                if not (isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute)
                        and isinstance(call.func.value, ast.Name) and call.func.value.id == "re"
                        and call.func.attr in {"compile", "search", "match", "fullmatch", "findall", "finditer"}):
                    continue
                pattern = call.args[0] if call.args else next(
                    (keyword.value for keyword in call.keywords if keyword.arg == "pattern"), None)
                if not (isinstance(pattern, ast.Constant) and isinstance(pattern.value, str)
                        and re.search(r"df-\\d", pattern.value, re.IGNORECASE)):
                    continue
                uses_upper = any(isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                                 and node.func.attr == "upper" for node in ast.walk(stmt))
                if uses_upper or func.name in seen:
                    continue
                seen.add(func.name)
                errors.append(
                    f"role.py:{func.lineno} {func.name} 提取任务编号后必须在同一表达式规范为大写，"
                    "再调用工具和比对记录；只加 re.IGNORECASE 仍会传入小写编号")
    return errors


def snapshot_errors(candidate: object) -> list[str]:
    """Check the shape of a previous Git snapshot without applying today's authoring rules."""
    if not isinstance(candidate, dict) or not isinstance(candidate.get("files"), list):
        return ["缺少文件列表"]
    files = candidate["files"]
    if len(files) != 3 or any(not isinstance(item, dict)
                              or not isinstance(item.get("path"), str)
                              or not isinstance(item.get("content"), str)
                              or len(item["content"].encode("utf-8")) > 40_000
                              for item in files):
        return ["文件结构无效"]
    if {item["path"] for item in files} != {"role.py", "prompts/role.md", "tests/test_role.py"}:
        return ["文件路径无效"]
    return []


def parse(reply: str | None, *, allow_legacy_literals: bool = False,
          template: str | None = None, selected_tools: list[str] | None = None) -> tuple[dict, list[str]]:
    raw = str(reply or "").strip()
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end < start:
        return {}, ["缺少 JSON 对象"]
    try:
        data = json.loads(raw[start:end + 1])
    except json.JSONDecodeError as error:
        return {}, [f"JSON 解析失败：{error.msg}（第{error.lineno}行，第{error.colno}列）；"
                    "只返回一个JSON对象，检查字符串转义、逗号及多余的尾部括号"]
    files = data.get("files") if isinstance(data, dict) else None
    if not isinstance(files, list) or len(files) != 3:
        return {}, ["必须且只能有 role.py、prompts/role.md 和 tests/test_role.py"]
    mapping = {}
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not isinstance(item.get("content"), str):
            return {}, ["文件结构无效"]
        if item["path"] in mapping:
            return {}, ["文件路径重复"]
        mapping[item["path"]] = item["content"]
    if set(mapping) != {"role.py", "prompts/role.md", "tests/test_role.py"}:
        return {}, ["只允许 role.py、prompts/role.md 与 tests/test_role.py"]
    if not mapping["prompts/role.md"].strip() or len(mapping["prompts/role.md"].encode("utf-8")) > 40_000:
        return {}, ["岗位提示词不能为空或超过 40KB"]
    errors = []
    role_prompt = mapping["prompts/role.md"]
    if "输出必须包含" in role_prompt and "顶层仅含" in role_prompt and "decision" in role_prompt:
        errors.append("岗位提示词对输出字段的要求互相冲突")
    trees = {}
    for path in ("role.py", "tests/test_role.py"):
        source = mapping[path]
        if len(source.encode("utf-8")) > 40_000:
            errors.append(path + " 超过 40KB")
            continue
        try:
            trees[path] = ast.parse(source, filename=path)
        except SyntaxError as error:
            errors.append(f"{path}:{error.lineno}:{error.offset} 语法错误：{error.msg}")
    if errors:
        return {}, errors
    if not allow_legacy_literals:
        for path, tree in trees.items():
            if any(isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load)
                   and node.id in ("true", "false", "null") for node in ast.walk(tree)):
                errors.append(path + " 使用了 JSON 小写字面量，Python 应用 True/False/None")
    role = trees["role.py"]
    execute_node = next((node for node in role.body
                         if isinstance(node, ast.AsyncFunctionDef) and node.name == "execute"), None)
    if execute_node is None:
        errors.append("role.py 缺少 async execute")
    statuses = []
    for node in ast.walk(execute_node) if execute_node is not None else ():
        if not isinstance(node, ast.Return) or not isinstance(node.value, ast.Dict):
            continue
        statuses.extend(value.value for key, value in zip(node.value.keys, node.value.values)
                        if isinstance(key, ast.Constant) and key.value == "status"
                        and isinstance(value, ast.Constant) and isinstance(value.value, str))
    if statuses and set(statuses) == {"BLOCKED"}:
        errors.append("H1 已确认，岗位正常路径不能全部固定返回 BLOCKED")
    if not any(isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
               and node.func.attr == "prompt" and isinstance(node.func.value, ast.Name)
               and node.func.value.id == "ctx" and len(node.args) == 1
               and isinstance(node.args[0], ast.Constant) and node.args[0].value == "role"
               for node in ast.walk(role)):
        errors.append("role.py 必须调用 ctx.prompt(\"role\")")
    if template == "supervisor" and not any(
            isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and node.func.attr == "delegate" and isinstance(node.func.value, ast.Name)
            and node.func.value.id == "ctx" for node in ast.walk(role)):
        errors.append("supervisor 必须经 ctx.delegate 委派已授权子智能体")
    if template == "supervisor" and not any(
            isinstance(node, ast.Attribute) and node.attr == "delegates"
            and isinstance(node.value, ast.Attribute) and node.value.attr == "spec"
            and isinstance(node.value.value, ast.Attribute) and node.value.value.attr == "manifest"
            and isinstance(node.value.value.value, ast.Name) and node.value.value.value.id == "ctx"
            for node in ast.walk(role)):
        errors.append("supervisor 必须从 ctx.manifest.spec.delegates 读取委派清单")
    called_tools = set()
    prompt_compiles = []
    llm_calls = []
    prompt_names = set()
    llm_result_names = set()
    manifest_tool_names = set()
    manifest_tool_item_names = set()
    def is_manifest_tools(node):
        return (isinstance(node, ast.Attribute) and node.attr == "tools"
                and isinstance(node.value, ast.Attribute) and node.value.attr == "spec"
                and isinstance(node.value.value, ast.Attribute) and node.value.value.attr == "manifest"
                and isinstance(node.value.value.value, ast.Name) and node.value.value.value.id == "ctx")
    for node in ast.walk(role):
        if not isinstance(node, (ast.Assign, ast.AnnAssign)):
            continue
        value = node.value
        targets = node.targets if isinstance(node, ast.Assign) else [node.target]
        if is_manifest_tools(value) or (isinstance(value, ast.BoolOp)
                and any(is_manifest_tools(item) for item in value.values)):
            manifest_tool_names.update(target.id for target in targets if isinstance(target, ast.Name))
        if (isinstance(value, ast.Await) and isinstance(value.value, ast.Call)
                and isinstance(value.value.func, ast.Attribute)
                and value.value.func.attr == "chat"
                and isinstance(value.value.func.value, ast.Attribute)
                and value.value.func.value.attr == "llm"
                and isinstance(value.value.func.value.value, ast.Name)
                and value.value.func.value.value.id == "ctx"
                and not any(keyword.arg == "tools" for keyword in value.value.keywords)):
            llm_result_names.update(target.id for target in targets if isinstance(target, ast.Name))
        if (isinstance(value, ast.Await) and isinstance(value.value, ast.Call)
                and isinstance(value.value.func, ast.Attribute)
                and isinstance(value.value.func.value, ast.Name)
                and value.value.func.value.id == "ctx"
                and value.value.func.attr == "prompt"
                and len(value.value.args) == 1
                and isinstance(value.value.args[0], ast.Constant)
                and value.value.args[0].value == "role"):
            prompt_names.update(target.id for target in targets if isinstance(target, ast.Name))
    for node in ast.walk(role):
        if isinstance(node, (ast.For, ast.AsyncFor)) and isinstance(node.target, ast.Name):
            if is_manifest_tools(node.iter) or (isinstance(node.iter, ast.Name)
                    and node.iter.id in manifest_tool_names):
                manifest_tool_item_names.add(node.target.id)
    regex_literals = set()
    pattern_bindings = {}
    for node in ast.walk(role):
        if isinstance(node, (ast.Assign, ast.AnnAssign)):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            for target in targets:
                if isinstance(target, ast.Name) and node.value is not None:
                    pattern_bindings.setdefault(target.id, []).append(node.value)
        elif isinstance(node, (ast.For, ast.comprehension)) and isinstance(node.target, ast.Name):
            pattern_bindings.setdefault(node.target.id, []).append(node.iter)
    for call in ast.walk(role):
        if (isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute)
                and isinstance(call.func.value, ast.Name) and call.func.value.id == "re"
                and call.func.attr in {"compile", "search", "match", "fullmatch", "findall", "finditer", "split", "sub", "subn"}):
            pattern = call.args[0] if call.args else next(
                (keyword.value for keyword in call.keywords if keyword.arg == "pattern"), None)
            if pattern is not None:
                pending, visited = [pattern], set()
                while pending:
                    item = pending.pop()
                    if id(item) in visited:
                        continue
                    visited.add(id(item))
                    if isinstance(item, ast.Constant):
                        regex_literals.add(id(item))
                    elif isinstance(item, ast.Name):
                        pending.extend(pattern_bindings.get(item.id, []))
                    else:
                        pending.extend(ast.iter_child_nodes(item))
    for node in ast.walk(role):
        if (isinstance(node, ast.Constant) and isinstance(node.value, str) and len(node.value) > 160
                and id(node) not in regex_literals):
            errors.append("role.py 不得硬编码大段提示词")
        if isinstance(node, ast.Import):
            if any(alias.name.split(".")[0] not in ALLOWED_IMPORTS for alias in node.names):
                errors.append("role.py 导入了未经允许的模块")
        elif isinstance(node, ast.ImportFrom):
            if node.level or (node.module or "").split(".")[0] not in ALLOWED_IMPORTS:
                errors.append("role.py 导入了未经允许的模块")
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in FORBIDDEN_CALLS:
            errors.append("role.py 使用了未经允许的执行/文件函数")
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr == "get" and isinstance(node.func.value, ast.Name)):
            if node.func.value.id in llm_result_names:
                errors.append("ctx.llm.chat 未传 tools 时返回字符串，不能调用 .get()")
            if node.func.value.id in manifest_tool_item_names:
                errors.append("ctx.manifest.spec.tools 条目是对象，不能调用 .get()")
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name) and node.func.value.id == "re"
                and node.func.attr in ("search", "match", "fullmatch", "compile")
                and node.args and isinstance(node.args[0], ast.Constant)
                and isinstance(node.args[0].value, str)
                and any("\\\\" + char in node.args[0].value for char in "dswDSWbB")):
            errors.append("正则表达式的原始字符串误用了双反斜杠")
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr == "compile" and isinstance(node.func.value, ast.Name)
                and node.func.value.id in prompt_names):
            prompt_compiles.append({keyword.arg for keyword in node.keywords})
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name) and node.func.value.id == "re"
                and node.func.attr in ("search", "match", "fullmatch")
                and len(node.args) >= 2 and isinstance(node.args[0], ast.Constant)
                and isinstance(node.args[0].value, str)
                and "PASS" in node.args[0].value.upper()
                and "DECISION" not in node.args[0].value.upper()
                and isinstance(node.args[1], ast.Name)
                and node.args[1].id.lower() in ("llm_output", "llm_response", "model_output", "model_response", "reply")):
            errors.append("不能通过扫描模型全文的 PASS 关键词覆盖结构化 BLOCKED 决策")
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Attribute)
                and isinstance(node.func.value.value, ast.Name)
                and node.func.value.value.id == "ctx"):
            if node.func.value.attr == "tools" and node.func.attr == "call":
                if len(node.args) != 1:
                    errors.append("ctx.tools.call 只能传工具名位置参数，其余参数须使用关键字")
                if node.args and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str):
                    tool_name = node.args[0].value
                    called_tools.add(tool_name)
                    if tool_name in TOOL_CONTRACTS:
                        given = {keyword.arg for keyword in node.keywords}
                        missing = set(TOOL_CONTRACTS[tool_name]["kwargs"]) - given
                        if missing:
                            errors.append(tool_name + " 缺少必需关键字参数：" + ", ".join(sorted(missing)))
            if node.func.value.attr == "llm" and node.func.attr == "chat":
                llm_calls.append({keyword.arg for keyword in node.keywords})
                if any(keyword.arg not in ("messages", "model", "prompt", "tools") for keyword in node.keywords):
                    errors.append("ctx.llm.chat 使用了 SDK 不支持的参数")
    if selected_tools and len(selected_tools) >= 2 and len(called_tools.intersection(selected_tools)) < 2:
        errors.append("role.py 必须调用至少两个已选择的工具")
    prompt_text = mapping["prompts/role.md"]
    placeholders = re.findall(r"\{\{([^{}]*)\}\}", prompt_text)
    if (prompt_text.count("{{") != len(placeholders)
            or prompt_text.count("}}") != len(placeholders)
            or any(not re.fullmatch(r"\s*\w+\s*", value) for value in placeholders)):
        errors.append("岗位提示词包含 SDK 无法替换的占位符；只允许简单变量名")
    if selected_tools and len(selected_tools) >= 2:
        errors.extend(_prompt_call_errors(role, prompt_names))
        if not prompt_compiles:
            errors.append("多工具岗位必须通过 prompt.compile() 读取版本化岗位指令，真实input/evidence通过user消息传入")
        if llm_calls and not any("prompt" in keywords for keywords in llm_calls):
            errors.append("多工具岗位调用 ctx.llm.chat 时必须传入 prompt=prompt 记录提示词版本")
    errors.extend(_task_id_case_errors(role))
    tests = trees["tests/test_role.py"]
    errors.extend(_shared_mock_errors(tests))
    errors.extend(_async_test_errors(tests))
    if any(isinstance(node, ast.Assign)
           and any(isinstance(target, ast.Attribute) and target.attr == "chat"
                   and isinstance(target.value, ast.Attribute) and target.value.attr == "llm"
                   for target in node.targets)
           and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name)
           and node.value.func.id == "AsyncMock"
           and any(keyword.arg == "return_value" and isinstance(keyword.value, ast.Dict)
                   for keyword in node.value.keywords) for node in ast.walk(tests)):
        errors.append("ctx.llm.chat 测试替身必须返回字符串，不能返回字典")
    if any(isinstance(node, ast.keyword) and node.arg == "compile"
           and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name)
           and node.value.func.id == "AsyncMock" for node in ast.walk(tests)):
        errors.append("prompt.compile 是同步方法，测试不能用 AsyncMock 模拟")
    if sum(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_")
           for node in tests.body) < 2:
        errors.append("岗位测试必须包含正常和拒绝/失败两个场景")
    return ({"files": files} if not errors else {}), errors
