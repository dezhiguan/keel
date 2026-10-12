"""Granted shared tools use a fresh private_key_jwt for every request."""
import os
import httpx
from keel.auth.client_assertion import ASSERTION_TYPE, sign_client_assertion
from keel.protocol.errors import ErrorCode, KeelError

async def invoke(owner, name, kwargs):
    base = os.environ["KEEL_SERVER_URL"].rstrip("/")
    audience = os.environ["KEEL_SERVICE_AUDIENCE"]
    key = os.environ["KEEL_CLIENT_PRIVATE_KEY"]
    headers = {"X-Client-Id": owner.agent, "X-Client-Assertion-Type": ASSERTION_TYPE,
               "X-Client-Assertion": sign_client_assertion(owner.agent, audience, key),
               "X-Keel-Env": owner.env, "X-Keel-Trace-Id": owner.trace_id}
    async with httpx.AsyncClient(timeout=45) as client:
        response = await client.post(f"{base}/api/v1/tools/{name}/invoke", json=kwargs, headers=headers)
        body = response.json()
        if response.status_code >= 400:
            try:
                code = ErrorCode(body.get("code"))
            except (ValueError, TypeError):
                code = ErrorCode.SERVER_INTERNAL_ERROR
            raise KeelError(code, body.get("message") or code.message)
        return body.get("data")
