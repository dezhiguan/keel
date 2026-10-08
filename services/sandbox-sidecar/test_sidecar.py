import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import fake_langfuse
import fake_llm
import runner


def test_fakes_stay_inside_the_pod_and_do_not_name_a_vendor():
    root = Path(__file__).resolve().parent
    source = "\n".join((root / name).read_text() for name in ("fake_llm.py", "fake_langfuse.py", "runner.py"))
    for banned in ("dashscope", "openai", "anthropic", "langfuse.com", "api.github.com"):
        assert banned not in source

    llm = ThreadingHTTPServer(("127.0.0.1", 0), fake_llm.Handler)
    langfuse = ThreadingHTTPServer(("127.0.0.1", 0), fake_langfuse.Handler)
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (llm, langfuse)]
    for thread in threads:
        thread.start()
    try:
        report, ok = runner.check(
            f"http://127.0.0.1:{llm.server_address[1]}",
            f"http://127.0.0.1:{langfuse.server_address[1]}",
            attempts=3,
            pause=0.05,
        )
    finally:
        llm.shutdown()
        langfuse.shutdown()
    assert ok
    assert "checkout=skipped" in report
    assert "llm=ok" in report
    assert "langfuse=ok" in report
