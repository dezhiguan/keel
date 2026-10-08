"""长流程用委托 token：POST /oauth/delegation-token（表单，不是 JSON）。"""

import os

import httpx

from keel.auth.client_assertion import ASSERTION_TYPE, sign_client_assertion

_REQUIRED = (
    "KEEL_AUTH_GATEWAY_URL",
    "KEEL_OAUTH_CLIENT_ID",
    "KEEL_OAUTH_PRIVATE_KEY",
    "KEEL_OAUTH_ASSERTION_AUDIENCE",
    "KEEL_DELEGATION_AUDIENCE",
    "KEEL_DELEGATION_SCOPES",
)


class DelegationDenied(Exception):
    """换票失败或配置不全。调用方应返回 RUN_RESUME_DENIED。"""


def issue_delegation_token(base_url: str, consent_id: str, requested_audience: str,
                           requested_scopes: str, client_id: str, client_assertion: str) -> str:
    if not all((base_url, consent_id, requested_audience, requested_scopes, client_id, client_assertion)):
        raise DelegationDenied("委托换票参数不完整")
    try:
        response = httpx.post(
            base_url.rstrip("/") + "/oauth/delegation-token",
            data={
                "consent_id": consent_id,
                "requested_audience": requested_audience,
                "requested_scopes": requested_scopes,
                "client_id": client_id,
                "client_assertion_type": ASSERTION_TYPE,
                "client_assertion": client_assertion,
            },
            timeout=10,
        )
        body = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise DelegationDenied("委托换票失败") from exc
    token = body.get("access_token") if isinstance(body, dict) else None
    if response.status_code >= 400 or not isinstance(token, str) or not token:
        raise DelegationDenied("委托换票被拒绝")
    return token


def issue_from_environment(consent_id: str) -> str:
    missing = [name for name in _REQUIRED if not os.environ.get(name, "").strip()]
    if missing or not consent_id.strip():
        raise DelegationDenied("委托换票缺少配置")
    assertion = sign_client_assertion(
        os.environ["KEEL_OAUTH_CLIENT_ID"].strip(),
        os.environ["KEEL_OAUTH_ASSERTION_AUDIENCE"].strip(),
        os.environ["KEEL_OAUTH_PRIVATE_KEY"],
    )
    return issue_delegation_token(
        os.environ["KEEL_AUTH_GATEWAY_URL"].strip(),
        consent_id.strip(),
        os.environ["KEEL_DELEGATION_AUDIENCE"].strip(),
        os.environ["KEEL_DELEGATION_SCOPES"].strip(),
        os.environ["KEEL_OAUTH_CLIENT_ID"].strip(),
        assertion,
    )
