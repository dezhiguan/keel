"""keel eval import / keel eval run."""

import json
from pathlib import Path

import yaml

from keel.eval.dataset import import_dataset, load_seed, seed_lines
from keel.eval.run import invoke_text, load_scorers, score_by_tag
from keel.manifest import load_manifest


def import_seed(*, dataset: str | None, seed_file: str, manifest_file: str = "agent.yaml") -> int:
    name = dataset or _dataset_name(manifest_file)
    rows = load_seed(seed_file)
    added = import_dataset(name, rows, lines=seed_lines(seed_file))
    print(f"imported {added} cases into {name}")
    return added


def run_eval(*, dataset: str | None, seed_file: str, scorers_file: str, endpoint: str | None, env: str = "dev", manifest_file: str = "agent.yaml") -> dict[str, float]:
    manifest = load_manifest(manifest_file)
    rows = load_seed(seed_file)
    scorers = load_scorers(scorers_file)
    target = endpoint or manifest.spec.runtime.endpoint
    run_id = dataset or _dataset_name(manifest_file)
    answers = []
    for row in rows:
        text = row.get("input")
        if isinstance(text, dict):
            text = text.get("text", "")
        answers.append(invoke_text(target, str(text), eval_run_id=run_id, env=env))
    by_tag = score_by_tag(rows, scorers, answers)
    print(json.dumps({"dataset": run_id, "byTag": by_tag}, ensure_ascii=False))
    return by_tag


def _dataset_name(manifest_file: str) -> str:
    raw = yaml.safe_load(Path(manifest_file).read_text(encoding="utf-8"))
    dataset = ((raw.get("spec") or {}).get("eval") or {}).get("dataset")
    if not dataset:
        raise ValueError("manifest 没有 spec.eval.dataset，请传 --dataset")
    return str(dataset)
