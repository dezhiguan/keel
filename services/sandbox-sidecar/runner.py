"""Confirm the in-pod fakes answer. Do not clone a repo or call a real model."""

import os
import time
import urllib.error
import urllib.request


def check(llm_url, langfuse_url, attempts=20, pause=0.25):
    llm = _post(llm_url + "/v1/chat/completions", attempts, pause)
    langfuse = _post(langfuse_url + "/api/public/otel/v1/traces", attempts, pause)
    repo = os.environ.get("KEEL_SANDBOX_REPO", "")
    ref = os.environ.get("KEEL_SANDBOX_REF", "")
    report = "\n".join([
        "checkout=skipped",
        f"repo={repo}",
        f"ref={ref}",
        f"llm={'ok' if llm else 'down'}",
        f"langfuse={'ok' if langfuse else 'down'}",
    ])
    return report, llm and langfuse


def _post(url, attempts, pause):
    for _ in range(attempts):
        try:
            request = urllib.request.Request(url, data=b"{}", method="POST")
            with urllib.request.urlopen(request, timeout=2) as response:
                response.read()
                if response.status == 200:
                    return True
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            time.sleep(pause)
    return False


if __name__ == "__main__":
    text, ok = check(
        os.environ.get("KEEL_LLM_BASE_URL", "http://127.0.0.1:8088"),
        os.environ.get("LANGFUSE_HOST", "http://127.0.0.1:3000"),
    )
    print(text)
    raise SystemExit(0 if ok else 1)
