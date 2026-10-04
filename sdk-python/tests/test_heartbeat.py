from keel.heartbeat import INTERVAL_SECONDS, beat_once


def test_k8s_liveness_does_not_send():
    calls = []
    assert beat_once(lambda url, body: calls.append(body), "askdb", "dev", "1", "v1", "k8s", "http://keel") is False
    assert calls == []


def test_missing_server_url_does_not_send():
    calls = []
    assert beat_once(lambda url, body: calls.append(url), "askdb", "dev", "1", "v1", "heartbeat", "") is False
    assert calls == []


def test_heartbeat_posts_and_interval_is_under_the_offline_threshold():
    calls = []
    assert beat_once(lambda url, body: calls.append((url, body)), "askdb", "dev", "pod-1", "v1", "heartbeat", "http://keel/") is True
    assert calls == [("http://keel/api/v1/instances/heartbeat", {
        "agent": "askdb", "env": "dev", "instanceId": "pod-1", "version": "v1",
    })]
    assert INTERVAL_SECONDS < 45
