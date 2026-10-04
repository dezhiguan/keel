"""非 K8s 实例的心跳。地址只来自 KEEL_SERVER_URL，缺失则不发送。"""

from __future__ import annotations

import os

INTERVAL_SECONDS = 15


def should_send(liveness: str, server_url: str | None = None) -> bool:
    url = os.environ.get("KEEL_SERVER_URL") if server_url is None else server_url
    return liveness == "heartbeat" and bool(url)


def beat_once(post, agent: str, env: str, instance_id: str, version: str, liveness: str, server_url: str | None) -> bool:
    if not should_send(liveness, server_url):
        return False
    post(server_url.rstrip("/") + "/api/v1/instances/heartbeat", {
        "agent": agent,
        "env": env,
        "instanceId": instance_id,
        "version": version,
    })
    return True
