import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from keel.eval.run import invoke_text


class Handler(BaseHTTPRequestHandler):
    bridge = None

    def do_POST(self):
        Handler.bridge = self.headers.get("X-Keel-Bridge")
        body = b'event: final\ndata: {"answer":"ok"}\n\n'
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        return


def test_eval_invoke_forwards_the_meta_agent_bridge_token(monkeypatch):
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        monkeypatch.setenv("KEEL_META_AGENT_TOKEN", "bridge-secret")
        assert invoke_text(f"http://127.0.0.1:{server.server_port}", "hi", eval_run_id="ev", env="dev") == "ok"
        assert Handler.bridge == "bridge-secret"
        monkeypatch.setenv("KEEL_BRIDGE_TOKEN", "askdb-private")
        assert invoke_text(f"http://127.0.0.1:{server.server_port}", "hi", eval_run_id="ev", env="staging") == "ok"
        assert Handler.bridge == "askdb-private"
        monkeypatch.delenv("KEEL_BRIDGE_TOKEN")
        monkeypatch.delenv("KEEL_META_AGENT_TOKEN")
        Handler.bridge = "unset"
        assert invoke_text(f"http://127.0.0.1:{server.server_port}", "hi", eval_run_id="ev", env="dev") == "ok"
        assert Handler.bridge is None
    finally:
        server.shutdown()
