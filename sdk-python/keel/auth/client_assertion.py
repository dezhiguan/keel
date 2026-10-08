"""private_key_jwt（RS256）。iss 与 sub 都是 client_id，exp 距 iat 不超过 10 分钟。"""

import base64
import json
import time
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
MAX_TTL_SECONDS = 600


def sign_client_assertion(client_id: str, audience: str, private_key_pem: str, *,
                          ttl_seconds: int = 300, now: int | None = None) -> str:
    if not client_id or not audience or not private_key_pem:
        raise ValueError("客户端断言缺少 client_id、audience 或私钥")
    if ttl_seconds <= 0 or ttl_seconds > MAX_TTL_SECONDS:
        raise ValueError("客户端断言有效期必须在 10 分钟以内")
    issued = int(time.time() if now is None else now)
    header = _b64({"alg": "RS256", "typ": "JWT"})
    payload = _b64({
        "iss": client_id,
        "sub": client_id,
        "aud": audience,
        "jti": "jti_" + uuid.uuid4().hex,
        "iat": issued,
        "exp": issued + ttl_seconds,
    })
    signing_input = f"{header}.{payload}".encode()
    key = serialization.load_pem_private_key(private_key_pem.encode(), password=None)
    signature = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    return f"{header}.{payload}.{_b64_bytes(signature)}"


def _b64(value: dict) -> str:
    return _b64_bytes(json.dumps(value, separators=(",", ":")).encode())


def _b64_bytes(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
