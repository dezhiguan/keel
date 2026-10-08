"""Open a research job after a requirement is confirmed. Case text stays on disk."""

import os

import httpx

from keel.auth.client_assertion import ASSERTION_TYPE, sign_client_assertion
from keel.cli.server import ServerRejected, server_url


def open_job(*, title: str, target_agent: str, goal: str) -> dict | None:
    if not os.environ.get("KEEL_SERVER_URL", "").strip():
        return None
    headers = service_headers()
    created = _post("/api/v1/devflow/jobs", {
        "title": title.strip() or target_agent,
        "targetAgent": target_agent,
        "layer": "dev",
        "kind": "CREATE",
        "goal": goal.strip() or title.strip() or target_agent,
        "template": "tool-agent",
    }, headers)
    job_id = str(created.get("jobId") or "").strip()
    if not job_id:
        raise ValueError("研发任务没有编号")
    reported = _post(f"/api/v1/devflow/jobs/{job_id}/stages/SPEC/report", {
        "status": "OK",
        "summary": "需求已确认",
        "artifact": {"kind": "SPEC", "ref": "spec.json", "origin": "AGENT"},
    }, headers)
    return {"jobId": job_id, "stage": reported.get("stage"), "status": reported.get("status")}


def service_headers() -> dict[str, str]:
    client_id = os.environ.get("KEEL_OAUTH_CLIENT_ID", "").strip()
    key = os.environ.get("KEEL_OAUTH_PRIVATE_KEY", "").strip()
    audience = os.environ.get("KEEL_SERVICE_AUDIENCE", "").strip()
    if not client_id or not key or not audience:
        raise ValueError("写入任务账本需要 KEEL_OAUTH_CLIENT_ID、KEEL_OAUTH_PRIVATE_KEY、KEEL_SERVICE_AUDIENCE")
    assertion = sign_client_assertion(client_id, audience, key)
    return {
        "X-Client-Id": client_id,
        "X-Client-Assertion-Type": ASSERTION_TYPE,
        "X-Client-Assertion": assertion,
    }


def _post(path: str, body: dict, headers: dict[str, str]) -> dict:
    response = httpx.post(server_url() + path, json=body, headers=headers, timeout=30)
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    if response.status_code >= 400 or payload.get("code") not in (None, "OK"):
        code = str(payload.get("code") or response.status_code)
        message = str(payload.get("message") or response.text)
        raise ServerRejected(code, message, response.status_code)
    data = payload.get("data")
    return data if isinstance(data, dict) else {}
