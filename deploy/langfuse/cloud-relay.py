"""TLS pass through for the two allowlisted cloud services; no decryption."""
import logging
import select
import socket
import threading

LISTEN = ("172.25.90.183", 443)
HOSTS = {"jp.cloud.langfuse.com", "api.github.com"}


def connect(host):
    # A failed load balancer address must not consume the API's whole timeout.
    last_error = None
    addresses = dict.fromkeys(info[4] for info in socket.getaddrinfo(
        host, 443, family=socket.AF_INET, type=socket.SOCK_STREAM))
    for address in addresses:
        try:
            upstream = socket.create_connection(address, timeout=2)
            upstream.settimeout(15)
            return upstream
        except OSError as exc:
            last_error = exc
    raise last_error or OSError("Upstream DNS returned no IPv4 address")


def read_exact(client, size):
    data = b""
    while len(data) < size:
        chunk = client.recv(size - len(data))
        if not chunk:
            raise OSError("Incomplete TLS record")
        data += chunk
    return data


def client_hello(client):
    header = read_exact(client, 5)
    size = int.from_bytes(header[3:5], "big")
    if header[0] != 22 or size > 16384:
        raise OSError("Expected TLS handshake")
    body = read_exact(client, size)
    if not body or body[0] != 1:
        raise OSError("Expected ClientHello")
    try:
        pos = 38  # handshake header, version, random
        pos += 1 + body[pos]  # session id
        pos += 2 + int.from_bytes(body[pos:pos + 2], "big")  # ciphers
        pos += 1 + body[pos]  # compression
        end = pos + 2 + int.from_bytes(body[pos:pos + 2], "big")
        pos += 2
        while pos + 4 <= min(end, len(body)):
            kind = int.from_bytes(body[pos:pos + 2], "big")
            length = int.from_bytes(body[pos + 2:pos + 4], "big")
            extension = body[pos + 4:pos + 4 + length]
            if kind == 0 and len(extension) >= 5 and extension[2] == 0:
                name_size = int.from_bytes(extension[3:5], "big")
                host = extension[5:5 + name_size].decode("ascii").lower()
                if host in HOSTS:
                    return host, header + body
                break
            pos += 4 + length
    except (IndexError, UnicodeError):
        pass
    raise OSError("Unrecognized TLS server name")


def handle(client):
    upstream = None
    try:
        client.settimeout(15)
        host, hello = client_hello(client)
        upstream = connect(host)
        upstream.sendall(hello)
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
