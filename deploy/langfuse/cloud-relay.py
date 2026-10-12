"""TCP relay for TLS pass through to Langfuse Japan; contains no credentials."""
import logging
import select
import socket
import threading

LISTEN = ("172.25.90.183", 443)
UPSTREAM = ("jp.cloud.langfuse.com", 443)


def connect():
    # A failed load balancer address must not consume the API's whole timeout.
    last_error = None
    addresses = dict.fromkeys(info[4] for info in socket.getaddrinfo(
        *UPSTREAM, family=socket.AF_INET, type=socket.SOCK_STREAM))
    for address in addresses:
        try:
            upstream = socket.create_connection(address, timeout=2)
            upstream.settimeout(15)
            return upstream
        except OSError as exc:
            last_error = exc
    raise last_error or OSError("Langfuse DNS returned no IPv4 address")


def handle(client):
    upstream = None
    try:
        client.settimeout(15)
        upstream = connect()
        while True:
            readable, _, _ = select.select([client, upstream], [], [], 120)
            if not readable:
                return
            for src in readable:
                data = src.recv(65536)
                if not data:
                    return
                (upstream if src is client else client).sendall(data)
    except OSError as exc:
        logging.warning("Langfuse relay connection failed: %s", type(exc).__name__)
    finally:
        client.close()
        if upstream is not None:
            upstream.close()


def main():
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(LISTEN)
        server.listen(128)
        while True:
            client, _ = server.accept()
            threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
