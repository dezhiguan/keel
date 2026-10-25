"""Lifespan-owned transport pooling; per-invocation identity stays on separate clients."""
import os
from contextlib import contextmanager
from contextvars import ContextVar
import httpx

_transport = ContextVar('keel_http_transport', default=None)


class BorrowedTransport(httpx.AsyncBaseTransport):
    def __init__(self, transport):
        self.transport = transport

    async def handle_async_request(self, request):
        return await self.transport.handle_async_request(request)

    async def aclose(self):
        # Only the ASGI lifespan owner closes the underlying connections.
        pass


@contextmanager
def use_transport(transport):
    token = _transport.set(transport)
    try:
        yield
    finally:
        _transport.reset(token)


def new_async_client(*, pooled_only=False, **kwargs):
    transport = _transport.get()
    # A custom TLS/proxy/transport option must keep HTTPX's normal semantics.
    proxy_env = any(os.environ.get(k) for k in ('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'))
    if transport is not None and not proxy_env and not any(k in kwargs for k in ('transport','proxy','verify','cert','http2')):
        kwargs['transport'] = BorrowedTransport(transport)
    elif pooled_only:
        return None
    return httpx.AsyncClient(**kwargs)


def create_transport():
    return httpx.AsyncHTTPTransport(retries=0, limits=httpx.Limits(max_connections=128, max_keepalive_connections=64, keepalive_expiry=30))
