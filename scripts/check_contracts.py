#!/usr/bin/env python3
"""契约校验。由 scripts/validate-contracts.sh 调用，也可以单独跑。

用法：
    python3 scripts/check_contracts.py schema     # 只查 schema 自身语法
    python3 scripts/check_contracts.py examples   # 只查正例/反例
    python3 scripts/check_contracts.py            # 全部

反例那部分是重点：schema 写得过宽时正例照样全绿，只有反例能发现。
"""
import json
import pathlib
import sys

import yaml
from jsonschema import Draft202012Validator

ROOT = pathlib.Path(__file__).resolve().parent.parent
C = ROOT / "contracts"
EX = C / "examples"

SCHEMAS = ["manifest.schema.json", "sse-events.schema.json", "audit-event.schema.json"]
YAMLS = ["error-codes.yaml", "invoke.openapi.yaml"]

failures: list[str] = []


def load(p: pathlib.Path):
    text = p.read_text(encoding="utf-8")
    return yaml.safe_load(text) if p.suffix in (".yaml", ".yml") else json.loads(text)


def check_schemas() -> None:
    for name in SCHEMAS:
        try:
            Draft202012Validator.check_schema(load(C / name))
            print(f"  ok   {name}")
        except Exception as e:  # noqa: BLE001
            failures.append(f"{name}: {e}")
            print(f"  FAIL {name}: {e}")
    for name in YAMLS:
        try:
            load(C / name)
            print(f"  ok   {name}")
        except Exception as e:  # noqa: BLE001
            failures.append(f"{name}: {e}")
            print(f"  FAIL {name}: {e}")

    # 错误码格式与 retryable 必填
    codes = load(C / "error-codes.yaml")["codes"]
    for code, body in codes.items():
        if not code.replace("_", "").isupper() or "_" not in code:
            failures.append(f"error-codes: {code} 不符合 {{模块}}_{{原因}} 格式")
        if "retryable" not in body:
            failures.append(f"error-codes: {code} 缺 retryable")
    print(f"  ok   error-codes.yaml 共 {len(codes)} 个错误码，格式与 retryable 齐全")


def expect(validator, instance, should_pass: bool, label: str) -> None:
    errs = sorted(validator.iter_errors(instance), key=lambda e: e.path)
    passed = not errs
    if passed == should_pass:
        detail = "" if should_pass else f"（被拒于 {'/'.join(map(str, errs[0].path)) or '根'}）"
        print(f"  ok   {label} {detail}")
    else:
        why = "本该被拒却通过了" if should_pass is False else f"本该通过却被拒：{errs[0].message}"
        failures.append(f"{label}: {why}")
        print(f"  FAIL {label}: {why}")


def check_examples() -> None:
    manifest_v = Draft202012Validator(load(C / "manifest.schema.json"))
    events_v = Draft202012Validator(load(C / "sse-events.schema.json"))

    print("  manifest 正例")
    for p in sorted(EX.glob("*.yaml")):
        expect(manifest_v, load(p), True, p.name)

    print("  manifest 反例（必须全部被拒）")
    for p in sorted((EX / "invalid").glob("*.yaml")):
        expect(manifest_v, load(p), False, p.name)

    print("  SSE 事件正例")
    for case in load(EX / "events" / "valid.json"):
        expect(events_v, case["frame"], True, case["why"])

    print("  SSE 事件反例（必须全部被拒）")
    for case in load(EX / "events" / "invalid.json"):
        expect(events_v, case["frame"], False, case["why"])


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("schema", "all"):
        check_schemas()
    if what in ("examples", "all"):
        check_examples()
    if failures:
        print(f"\n{len(failures)} 项失败")
        sys.exit(1)
    print("\n全部通过")
