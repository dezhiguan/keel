from pathlib import Path

from keel.cli.new import template_dir


def test_missing_templates_dir_falls_back_to_the_checkout(monkeypatch, tmp_path):
    monkeypatch.setenv("KEEL_TEMPLATES_DIR", str(tmp_path / "absent"))
    assert (template_dir("graph-agent") / "agent.yaml").is_file()


def test_configured_templates_dir_wins_when_present(monkeypatch, tmp_path):
    root = tmp_path / "graph-agent"
    root.mkdir()
    (root / "agent.yaml").write_text("kind: Agent\n", encoding="utf-8")
    monkeypatch.setenv("KEEL_TEMPLATES_DIR", str(tmp_path))
    assert template_dir("graph-agent") == root
