"""Internal bridge authentication for platform and evaluation calls."""

from keel.protocol.errors import ErrorCode
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse
import hmac
import os

class BridgeAuth(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        token = os.environ.get("KEEL_META_AGENT_TOKEN")
        if request.url.path != "/v1/health" and token and not hmac.compare_digest(request.headers.get("X-Keel-Bridge", ""), token):
            return JSONResponse({"code": ErrorCode.AUTH_UNAUTHENTICATED.value,
                "message": "服务身份无效", "trace_id": "", "retryable": False}, status_code=401)
        return await call_next(request)
