from __future__ import annotations

import argparse

import uvicorn
from uvicorn.protocols.http.h11_impl import H11Protocol

from .app import app


def token_guard_protocol(token: str) -> type[H11Protocol]:
    """Create an HTTP protocol that drops requests without the secret path.

    ASGI cannot close a socket without producing an error response in Uvicorn.
    Inspecting the request line before passing bytes to H11 lets the server
    close the transport silently: no status line, headers, or body are sent.
    """

    token_prefix = f"/{token}".encode("ascii")

    class TokenGuardH11Protocol(H11Protocol):
        def __init__(self, *args, **kwargs) -> None:
            super().__init__(*args, **kwargs)
            self._guard_buffer = bytearray()
            self._guard_checked = False

        def data_received(self, data: bytes) -> None:
            if self._guard_checked:
                super().data_received(data)
                return

            self._guard_buffer.extend(data)
            if len(self._guard_buffer) > 8192:
                self.transport.close()
                return
            end_of_request_line = self._guard_buffer.find(b"\r\n")
            if end_of_request_line < 0:
                return

            request_line = bytes(self._guard_buffer[:end_of_request_line])
            parts = request_line.split(b" ", 2)
            raw_target = parts[1].split(b"?", 1)[0] if len(parts) == 3 else b""
            if raw_target != token_prefix and not raw_target.startswith(token_prefix + b"/"):
                self.transport.close()
                return

            self._guard_checked = True
            buffered_data = bytes(self._guard_buffer)
            self._guard_buffer.clear()
            super().data_received(buffered_data)

    return TokenGuardH11Protocol


def main() -> None:
    parser = argparse.ArgumentParser(description="RFIDHunter incremental backup server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()

    settings = app.state.settings
    config = uvicorn.Config(
        app,
        host=args.host,
        port=args.port,
        http=token_guard_protocol(settings.access_token),
    )
    uvicorn.Server(config).run()


if __name__ == "__main__":
    main()
