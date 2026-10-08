"""Pod-local stand-in for the thin gateway. It never calls a vendor."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BODY = b'{"id":"sandbox","object":"chat.completion","choices":[{"message":{"role":"assistant","content":"sandbox"}}]}'


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length:
            self.rfile.read(length)
        if self.path.split("?", 1)[0] != "/v1/chat/completions":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def log_message(self, fmt, *args):
        return


def serve(port=8088):
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    serve()
