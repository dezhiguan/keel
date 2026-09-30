#!/usr/bin/env python3
"""stop 钩子：本轮结束时，只跑受影响模块的测试，不跑全量。

失败时用 followup_message 把结果交回给 agent 自己修。
全量测试、集成测试、覆盖率门禁留给 CI。
"""
import json
import os
import shutil
import subprocess
import sys

ROOT = os.getcwd()
TOUCHED = os.path.join(ROOT, ".cursor", ".touched-files")
TIMEOUT = 300


def touched_files():
    if not os.path.exists(TOUCHED):
        return []
    with open(TOUCHED, encoding="utf-8") as handle:
        files = [line.strip() for line in handle if line.strip()]
    os.remove(TOUCHED)
    return files


def maven_modules(files):
    """改了 {module}/src/main/java/... 就跑该模块的单测。"""
    modules = set()
    for path in files:
        if "/src/main/java/" not in path:
            continue
        module = path.split("/src/main/java/", 1)[0]
        if os.path.isfile(os.path.join(ROOT, module, "pom.xml")):
            modules.add(module)
    return sorted(modules)


def python_targets(files):
    """sdk-python/keel/<pkg>/... → sdk-python/tests/<pkg>，存在才跑。"""
    targets = set()
    for path in files:
        if not path.startswith("sdk-python/") or not path.endswith(".py"):
            continue
        parts = path.split("/")
        if len(parts) >= 3 and parts[1] == "keel":
            candidate = os.path.join("sdk-python", "tests", parts[2])
            if os.path.isdir(os.path.join(ROOT, candidate)):
                targets.add(candidate)
    return sorted(targets)


def console_files(files):
    return sorted(p for p in files if p.startswith("console/") and p.endswith((".ts", ".vue")))


def build_commands(files):
    commands = []

    modules = maven_modules(files)
    if modules and shutil.which("mvn"):
        selector = ",".join(":" + os.path.basename(m) for m in modules)
        commands.append(["mvn", "-o", "-q", "-pl", selector, "test"])

    targets = python_targets(files)
    if targets and shutil.which("pytest"):
        commands.append(["pytest", "-q", "-x", *targets])

    vue_files = console_files(files)
    if vue_files and os.path.isfile(os.path.join(ROOT, "console", "package.json")) and shutil.which("npx"):
        commands.append(["npx", "vitest", "related", "--run", *[os.path.relpath(f, "console") for f in vue_files]])

    return commands


def run(command):
    cwd = os.path.join(ROOT, "console") if command[:2] == ["npx", "vitest"] else ROOT
    try:
        result = subprocess.run(
            command, cwd=cwd, capture_output=True, text=True, timeout=TIMEOUT
        )
    except (subprocess.TimeoutExpired, OSError) as exc:
        return None, f"{' '.join(command)} 未能完成：{exc}"
    if result.returncode == 0:
        return True, ""
    tail = (result.stdout + result.stderr).strip().splitlines()[-40:]
    return False, f"$ {' '.join(command)}\n" + "\n".join(tail)


def main():
    sys.stdin.read()
    files = touched_files()
    if not files:
        return 0

    failures = []
    for command in build_commands(files):
        ok, output = run(command)
        if ok is False:
            failures.append(output)

    if failures:
        message = (
            "本轮改动的模块测试没过，请先修复再结束：\n\n"
            + "\n\n".join(failures)
            + "\n\n只修让测试失败的原因，不要改测试来迁就实现。"
        )
        print(json.dumps({"followup_message": message}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
