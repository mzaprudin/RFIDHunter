from __future__ import annotations

import hashlib
import base64
import time

from fastapi.testclient import TestClient
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


API = "/test-token"


def path(value: str) -> str:
    return API + value


def event(
    *,
    operation_id: str = "op-1",
    sync_id: str = "item-1",
    operation: str = "UPSERT",
    updated_at: int = 100,
    photo_sha256: str | None = None,
    name: str | None = "Очки",
    epc: str | None = "EPC-1",
) -> dict[str, object]:
    return {
        "operationId": operation_id,
        "deviceId": "phone-1",
        "operation": operation,
        "syncId": sync_id,
        "itemId": 1,
        "name": name,
        "epc": epc,
        "photoSha256": photo_sha256,
        "updatedAt": updated_at,
    }


def test_health(client: TestClient) -> None:
    response = client.get(path("/health"))
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_api_is_not_routed_without_the_secret_path_prefix(client: TestClient) -> None:
    # The middleware deliberately sends no response for this request.
    # TestClient reports the incomplete ASGI response as an exception. The
    # production TokenGuardH11Protocol closes that TCP connection before ASGI.
    try:
        client.get("/health")
    except AssertionError as error:
        assert "did not receive any response" in str(error)
    else:
        raise AssertionError("an unprefixed request must not receive an HTTP response")


def test_sensitive_api_rejects_requests_without_device_signature(client: TestClient) -> None:
    response = client.raw.get(path("/v1/sync/items"))
    assert response.status_code == 401


def test_device_cannot_restore_another_device_namespace(client: TestClient) -> None:
    response = client.get(path("/v1/sync/items?device_id=another-phone"))
    assert response.status_code == 403


def test_forged_signed_request_is_rejected(client: TestClient) -> None:
    body = b"photo"
    digest = hashlib.sha256(body).hexdigest()
    first = client.put(path(f"/v1/files/{digest}"), content=body)
    assert first.status_code == 200
    forged = client.raw.put(
        path(f"/v1/files/{digest}"),
        content=body,
        headers={
            "X-RFID-Device-Id": "phone-1",
            "X-RFID-Key-Id": "key-phone-1",
            "X-RFID-Timestamp": "9999999999",
            "X-RFID-Nonce": "forged",
            "X-RFID-Signature": "Zm9yZ2Vk",
        },
    )
    assert forged.status_code == 401


def test_signed_request_nonce_cannot_be_replayed(client: TestClient) -> None:
    body = b"nonce-photo"
    digest = hashlib.sha256(body).hexdigest()
    first = client.put(path(f"/v1/files/{digest}"), content=body)
    assert first.status_code == 200
    replay = client.raw.put(path(f"/v1/files/{digest}"), content=body, headers=client.last_auth_headers)
    assert replay.status_code == 401


def test_file_upload_is_content_addressed_and_idempotent(client: TestClient) -> None:
    body = b"fake jpeg bytes"
    digest = hashlib.sha256(body).hexdigest()

    first = client.put(path(f"/v1/files/{digest}"), content=body, headers={"Content-Type": "application/octet-stream"})
    second = client.put(path(f"/v1/files/{digest}"), content=body)
    wrong = client.put(path(f"/v1/files/{'0' * 64}"), content=body)

    assert first.status_code == 200
    assert first.json()["status"] == "stored"
    assert second.status_code == 200
    assert second.json()["status"] == "already_exists"
    assert wrong.status_code == 422
    assert client.get(path(f"/v1/files/{digest}")).content == body


def test_event_requires_photo_to_be_uploaded_first(client: TestClient) -> None:
    digest = hashlib.sha256(b"photo").hexdigest()
    response = client.post(path("/v1/sync/events"), json=event(photo_sha256=digest))
    assert response.status_code == 409


def test_uploaded_photo_can_be_referenced_by_event(client: TestClient) -> None:
    body = b"photo"
    digest = hashlib.sha256(body).hexdigest()
    assert client.put(path(f"/v1/files/{digest}"), content=body).status_code == 200

    response = client.post(path("/v1/sync/events"), json=event(photo_sha256=digest))

    assert response.status_code == 200
    assert client.get(path("/v1/sync/items")).json()[0]["photo_sha256"] == digest


def test_header_mismatch_and_file_limit_are_rejected(client: TestClient) -> None:
    mismatch = client.post(
        path("/v1/sync/events"),
        json=event(),
        headers={"X-RFID-Operation-Id": "different"},
    )
    oversized = client.put(path(f"/v1/files/{hashlib.sha256(b'x' * 2048).hexdigest()}"), content=b"x" * 2048)

    assert mismatch.status_code == 400
    assert oversized.status_code == 413


def test_event_is_idempotent_and_conflicting_reuse_is_rejected(client: TestClient) -> None:
    first = client.post(path("/v1/sync/events"), json=event())
    repeated = client.post(path("/v1/sync/events"), json=event(), headers={"X-RFID-Operation-Id": "op-1"})
    conflicting = client.post(path("/v1/sync/events"), json=event(name="Ключи"))

    assert first.status_code == 200
    assert first.json()["status"] == "applied"
    assert repeated.status_code == 200
    assert repeated.json()["status"] == "already_applied"
    assert conflicting.status_code == 409

    rows = client.get(path("/v1/sync/items?device_id=phone-1")).json()
    assert len(rows) == 1
    assert rows[0]["name"] == "Очки"


def test_stale_event_does_not_overwrite_newer_snapshot(client: TestClient) -> None:
    assert client.post(path("/v1/sync/events"), json=event(updated_at=200)).json()["status"] == "applied"
    stale = client.post(path("/v1/sync/events"), json=event(operation_id="op-old", updated_at=100, name="Старое"))

    assert stale.status_code == 200
    assert stale.json()["status"] == "stale"
    assert client.get(path("/v1/sync/items")).json()[0]["name"] == "Очки"


def test_delete_is_a_tombstone(client: TestClient) -> None:
    assert client.post(path("/v1/sync/events"), json=event()).status_code == 200
    deleted = client.post(
        path("/v1/sync/events"),
        json=event(operation_id="op-delete", operation="DELETE", updated_at=200, name=None, epc=None),
    )

    assert deleted.status_code == 200
    assert deleted.json()["status"] == "applied"
    row = client.get(path("/v1/sync/items")).json()[0]
    assert row["operation"] == "DELETE"
    assert row["name"] is None


def test_password_account_can_be_created_and_restored_on_another_device(client: TestClient) -> None:
    public_key = client.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    register = client.raw.post(
        path("/v1/accounts/register"),
        json={
            "password": "correct horse battery staple",
            "deviceId": "phone-1",
            "keyId": "key-phone-1",
            "publicKey": base64.b64encode(public_key).decode("ascii"),
        },
    )
    assert register.status_code == 200, register.text
    assert register.json()["created"] is True
    assert client.post(path("/v1/sync/events"), json=event(operation_id="account-op")).status_code == 200

    replacement_key = ec.generate_private_key(ec.SECP256R1())
    replacement_public = replacement_key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    restore = client.raw.post(
        path("/v1/accounts/restore"),
        json={
            "password": "correct horse battery staple",
            "deviceId": "phone-2",
            "keyId": "key-phone-2",
            "publicKey": base64.b64encode(replacement_public).decode("ascii"),
        },
    )
    assert restore.status_code == 200, restore.text
    assert client.get(path("/v1/sync/items")).status_code == 401

    body = b""
    timestamp = str(int(time.time()))
    nonce = "replacement-nonce"
    canonical = "\n".join(("GET", "/v1/sync/items", "", "phone-2", timestamp, nonce, hashlib.sha256(body).hexdigest())).encode()
    signature = replacement_key.sign(canonical, ec.ECDSA(hashes.SHA256()))
    response = client.raw.get(
        path("/v1/sync/items"),
        headers={
            "X-RFID-Device-Id": "phone-2",
            "X-RFID-Key-Id": "key-phone-2",
            "X-RFID-Timestamp": timestamp,
            "X-RFID-Nonce": nonce,
            "X-RFID-Signature": base64.b64encode(signature).decode("ascii"),
        },
    )
    assert response.status_code == 200
    assert response.json()[0]["name"] == event()["name"]
