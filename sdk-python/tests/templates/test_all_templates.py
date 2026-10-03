import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import jsonschema
import yaml

from keel.cli.new import create_project

ROOT = Path(__file__).resolve().parents[3]
SDK = ROOT / "sdk-python"
SCHEMA = json.loads((ROOT / "contracts/manifest.schema.json").read_text())
PYTHON_TEMPLATES = ("chat-rag", "tool-agent", "graph-agent", "supervisor")


def _rules():
    path = ROOT / ".cursor/hooks/forbidden-patterns.py"
    spec = importlib.util.spec_from_file_location("forbidden_patterns", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.RULES, module.SOURCE_EXT


def test_each_template_generates_and_its_smoke_tests_pass(tmp_path):
    rules, source_ext = _rules()
    env = os.environ.copy()
    env["PYTHONPATH"] = str(SDK)
    for template in PYTHON_TEMPLATES:
        project = create_project("demo-agent", template=template, root=tmp_path / template)
        _assert_project(project, rules, source_ext)
        completed = subprocess.run(
            [sys.executable, "-m", "pytest", "tests", "-q", "--tb=short"],
            cwd=project, env=env, text=True, capture_output=True,
        )
        assert completed.returncode == 0, completed.stdout + completed.stderr


def test_java_spring_template_builds_two_agents(tmp_path):
    if shutil.which("mvn") is None:
        raise RuntimeError("mvn is required to smoke-test the java-spring template")
    project = create_project("demo-agent", template="java-spring", root=tmp_path)
    rules, source_ext = _rules()
    _assert_project(project, rules, source_ext)
    install = subprocess.run(
        ["mvn", "-pl", ":keel-spring-boot-starter", "-am", "install", "-DskipTests"],
        cwd=ROOT, text=True, capture_output=True,
    )
    assert install.returncode == 0, install.stdout[-2000:] + install.stderr[-2000:]
    completed = subprocess.run(["mvn", "test"], cwd=project, text=True, capture_output=True)
    assert completed.returncode == 0, completed.stdout[-4000:] + completed.stderr[-4000:]


def _assert_project(project: Path, rules, source_ext):
    assert ".keel/" in (project / ".gitignore").read_text().splitlines()
    manifests = [path for path in project.rglob("*.yaml") if path.read_text(encoding="utf-8").startswith("apiVersion:")]
    assert manifests
    for manifest in manifests:
        jsonschema.Draft202012Validator(SCHEMA).validate(yaml.safe_load(manifest.read_text(encoding="utf-8")))
    for path in project.rglob("*"):
        if not path.is_file() or path.suffix not in source_ext:
            continue
        content = path.read_text(encoding="utf-8", errors="ignore")
        for pattern, reason, only_ext, exempt in rules:
            if only_ext and path.suffix not in only_ext:
                continue
            if any(str(path).startswith(prefix) for prefix in exempt):
                continue
            assert re.search(pattern, content, re.MULTILINE) is None, f"{path}: {reason}"
