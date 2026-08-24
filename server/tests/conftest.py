from __future__ import annotations

from pathlib import Path
import base64
import hashlib
import json
import time
from typing import Any

import pytest
from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from rfidhunter_server.app import create_app
from rfidhunter_server.config import Settings


class SignedClient:
    def __init__(self, client: TestClient, key: ec.EllipticCurvePrivateKey, device_id: str, token: str):
        self.raw = client
        self.key = key
        self.device_id = device_id
        self.token = token
        self.last_auth_headers: dict[str, str] = {}

    def _request(self, method: str, url: str, **kwargs: Any):
        json_body = kwargs.pop("json", None)
        if json_body is not None:
            body = json.dumps(json_body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        else:
            content = kwargs.pop("content", b"")
            body = content.encode() if isinstance(content, str) else content
        headers = dict(kwargs.pop("headers", {}) or {})
        if url.startswith(f"/{self.token}/"):
            target = url[len(self.token) + 1 :]
            path, _, query = target.partition("?")
            timestamp = str(int(time.time()))
            nonce = hashlib.sha256(f"{time.time_ns()}".encode()).hexdigest()
            canonical = "\n".join((method.upper(), path, query, self.device_id, timestamp, nonce, hashlib.sha256(body).hexdigest())).encode()
            signature = self.key.sign(canonical, ec.ECDSA(hashes.SHA256()))
            headers.update({
                "X-RFID-Device-Id": self.device_id,
                "X-RFID-Key-Id": "key-phone-1",
                "X-RFID-Timestamp": timestamp,
                "X-RFID-Nonce": nonce,
                "X-RFID-Signature": base64.b64encode(signature).decode("ascii"),
            })
            self.last_auth_headers = {name: value for name, value in headers.items() if name.startswith("X-RFID-")}
        if json_body is not None:
            headers.setdefault("Content-Type", "application/json")
        return self.raw.request(method, url, content=body, headers=headers, **kwargs)

    def get(self, url: str, **kwargs: Any):
        return self._request("GET", url, **kwargs)

    def put(self, url: str, **kwargs: Any):
        return self._request("PUT", url, **kwargs)

    def post(self, url: str, **kwargs: Any):
        return self._request("POST", url, **kwargs)


@pytest.fixture
def client(tmp_path: Path) -> SignedClient:
    settings = Settings(
        database_path=tmp_path / "backup.sqlite3",
        files_dir=tmp_path / "files",
        access_token="test-token",
        max_file_bytes=1024,
    )
    with TestClient(create_app(settings)) as test_client:
        key = ec.generate_private_key(ec.SECP256R1())
        public_key = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        enrollment = test_client.post(
            "/test-token/v1/accounts/register",
            json={
                "password": "test-password-123",
                "deviceId": "phone-1",
                "keyId": "key-phone-1",
                "publicKey": base64.b64encode(public_key).decode("ascii"),
                "displayName": "Test phone",
            },
        )
        assert enrollment.status_code == 200, enrollment.text
        yield SignedClient(test_client, key, "phone-1", "test-token")
