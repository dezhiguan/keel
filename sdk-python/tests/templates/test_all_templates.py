import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import jsonschema
import pytest
import yaml

from keel.cli.new import create_project

ROOT = Path(__file__).resolve().parents[3]
SDK = ROOT / "sdk-python"
SCHEMA = json.loads((ROOT / "contracts/manifest.schema.json").read_text())
PYTHON_TEMPLATES = ("loop-agent", "chat-rag", "tool-agent", "graph-agent", "supervisor")


def _assert_private_python_install(project: Path):
    dockerfile = (project / "Dockerfile").read_text(encoding="utf-8")
    workflow = (project / ".github/workflows/keel.yml").read_text(encoding="utf-8")
    installer = (project / "scripts/install-keel-sdk.py").read_text(encoding="utf-8")
    assert "pip install keel-sdk" not in dockerfile
    assert "pip install keel-sdk" not in workflow
    assert "vendor/" not in dockerfile
    assert not (project / "vendor").exists()
    assert "scripts/install-keel-sdk.py" in dockerfile
    assert "KEEL_GITHUB_TOKEN" in dockerfile
    assert "pypi.org" not in dockerfile
    assert "dezhiguan/keel" in installer


def test_templates_install_sdk_from_the_github_release(tmp_path):
    for template in PYTHON_TEMPLATES:
        project = create_project("demo-agent", template=template, root=tmp_path / template)
        manifest = yaml.safe_load((project / "agent.yaml").read_text(encoding="utf-8"))
        assert manifest["spec"]["kind"] == ("agent" if template == "loop-agent" else "workflow")
        _assert_private_python_install(project)
    java_project = create_project("demo-agent", template="java-spring", root=tmp_path / "java")
    assert yaml.safe_load((java_project / "agent.yaml").read_text())["spec"]["kind"] == "workflow"
    assert not (java_project / "vendor").exists()
    assert "keel-sdk" not in (java_project / "Dockerfile").read_text(encoding="utf-8")
    pom = (java_project / "pom.xml").read_text(encoding="utf-8")
    assert "https://maven.pkg.github.com/dezhiguan/keel" in pom
    assert "<version>1.0.0</version>" in pom


def _rules():
    path = ROOT / ".cursor/hooks/forbidden-patterns.py"
    spec = importlib.util.spec_from_file_location("forbidden_patterns", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.RULES, module.SOURCE_EXT


@pytest.mark.parametrize("template", PYTHON_TEMPLATES)
def test_each_template_generates_and_its_smoke_tests_pass(tmp_path, template):
    rules, source_ext = _rules()
    env = os.environ.copy()
    env["PYTHONPATH"] = str(SDK)
    project = create_project("demo-agent", template=template, root=tmp_path / template)
    _assert_project(project, rules, source_ext)
    _assert_private_python_install(project)
    if template == "graph-agent":
        assert "-r /app/requirements.txt" in (project / "Dockerfile").read_text(encoding="utf-8")
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
    assert not (project / "vendor").exists()
    assert "keel-sdk" not in (project / "Dockerfile").read_text(encoding="utf-8")
    pom_path = project / "pom.xml"
    pom = pom_path.read_text(encoding="utf-8")
    assert "https://maven.pkg.github.com/dezhiguan/keel" in pom
    settings = (project / "ci/settings.xml").read_text(encoding="utf-8")
    assert "${env.KEEL_GITHUB_TOKEN}" in settings
    assert "ghp_" not in settings
    pom_path.write_text(pom.replace(
        "<artifactId>keel-spring-boot-starter</artifactId>\n      <version>1.0.0</version>",
        "<artifactId>keel-spring-boot-starter</artifactId>\n      <version>0.1.0-SNAPSHOT</version>",
        1,
    ), encoding="utf-8")
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
