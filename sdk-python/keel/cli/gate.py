"""keel gate：跑门禁并把结论回写 keel-server。"""

from keel.gate.runner import run_gate


def gate(*, env: str, seed_file: str, scorers_file: str, endpoint: str | None, baseline_file: str | None,
        holdout_job: str | None = None) -> str:
    return run_gate(env=env, seed_file=seed_file, scorers_file=scorers_file, endpoint=endpoint,
                    baseline_file=baseline_file, holdout_job=holdout_job)
