"""CI scores hidden cases and posts tag aggregates. Case text is not printed."""

import os

import httpx

from keel.auth.client_assertion import ASSERTION_TYPE, sign_client_assertion
from keel.cli.server import ServerRejected, server_url
from keel.eval.run import invoke_text
from keel.holdout import aggregates


def run_holdout(*, job_id: str, scorers, endpoint: str, env: str) -> None:
    headers = ci_headers()
    url = server_url()
    with httpx.Client(timeout=60, headers=headers) as client:
        response = client.get(f"{url}/api/v1/devflow/holdout/{job_id}")
        cases = _data(response).get("cases") or []
        answers = [invoke_text(endpoint, _text(case), eval_run_id=job_id, env=env) for case in cases]
        posted = client.post(f"{url}/api/v1/devflow/holdout-results",
                             json={"jobId": job_id, "byTag": aggregates(cases, scorers, answers)})
        _data(posted)


def ci_headers() -> dict[str, str]:
    client_id = os.environ.get("KEEL_CI_CLIENT_ID", "").strip()
    key = os.environ.get("KEEL_OAUTH_PRIVATE_KEY", "").strip()
    audience = os.environ.get("KEEL_SERVICE_AUDIENCE", "").strip()
    if not client_id or not key or not audience:
        raise ValueError("隐藏考题需要 KEEL_CI_CLIENT_ID、KEEL_OAUTH_PRIVATE_KEY、KEEL_SERVICE_AUDIENCE")
    assertion = sign_client_assertion(client_id, audience, key)
    return {
        "X-Client-Id": client_id,
        "X-Client-Assertion-Type": ASSERTION_TYPE,
        "X-Client-Assertion": assertion,
    }


def _text(case: dict) -> str:
    raw = case.get("input")
    if isinstance(raw, dict):
        return str(raw.get("text", ""))
    return str(raw or "")


def _data(response: httpx.Response) -> dict:
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
