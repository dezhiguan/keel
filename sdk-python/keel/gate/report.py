"""Write a gate result back to keel-server. promptVersions is omitted on purpose."""

from keel.cli.server import post_json


def record(agent: str, gate_run_id: str, passed: bool) -> dict:
    return post_json("/api/v1/gate-results", {
        "agent": agent,
        "gateRunId": gate_run_id,
        "passed": passed,
    })
