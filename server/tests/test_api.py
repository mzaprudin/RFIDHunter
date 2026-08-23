from __future__ import annotations

import hashlib

from fastapi.testclient import TestClient


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
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_file_upload_is_content_addressed_and_idempotent(client: TestClient) -> None:
    body = b"fake jpeg bytes"
    digest = hashlib.sha256(body).hexdigest()

    first = client.put(f"/v1/files/{digest}", content=body, headers={"Content-Type": "application/octet-stream"})
    second = client.put(f"/v1/files/{digest}", content=body)
    wrong = client.put(f"/v1/files/{'0' * 64}", content=body)

    assert first.status_code == 200
    assert first.json()["status"] == "stored"
    assert second.status_code == 200
    assert second.json()["status"] == "already_exists"
    assert wrong.status_code == 422
    assert client.get(f"/v1/files/{digest}").content == body


def test_event_requires_photo_to_be_uploaded_first(client: TestClient) -> None:
    digest = hashlib.sha256(b"photo").hexdigest()
    response = client.post("/v1/sync/events", json=event(photo_sha256=digest))
    assert response.status_code == 409


def test_uploaded_photo_can_be_referenced_by_event(client: TestClient) -> None:
    body = b"photo"
    digest = hashlib.sha256(body).hexdigest()
    assert client.put(f"/v1/files/{digest}", content=body).status_code == 200

    response = client.post("/v1/sync/events", json=event(photo_sha256=digest))

    assert response.status_code == 200
    assert client.get("/v1/sync/items").json()[0]["photo_sha256"] == digest


def test_header_mismatch_and_file_limit_are_rejected(client: TestClient) -> None:
    mismatch = client.post(
        "/v1/sync/events",
        json=event(),
        headers={"X-RFID-Operation-Id": "different"},
    )
    oversized = client.put(f"/v1/files/{hashlib.sha256(b'x' * 2048).hexdigest()}", content=b"x" * 2048)

    assert mismatch.status_code == 400
    assert oversized.status_code == 413


def test_event_is_idempotent_and_conflicting_reuse_is_rejected(client: TestClient) -> None:
    first = client.post("/v1/sync/events", json=event())
    repeated = client.post("/v1/sync/events", json=event(), headers={"X-RFID-Operation-Id": "op-1"})
    conflicting = client.post("/v1/sync/events", json=event(name="Ключи"))

    assert first.status_code == 200
    assert first.json()["status"] == "applied"
    assert repeated.status_code == 200
    assert repeated.json()["status"] == "already_applied"
    assert conflicting.status_code == 409

    rows = client.get("/v1/sync/items?device_id=phone-1").json()
    assert len(rows) == 1
    assert rows[0]["name"] == "Очки"


def test_stale_event_does_not_overwrite_newer_snapshot(client: TestClient) -> None:
    assert client.post("/v1/sync/events", json=event(updated_at=200)).json()["status"] == "applied"
    stale = client.post("/v1/sync/events", json=event(operation_id="op-old", updated_at=100, name="Старое"))

    assert stale.status_code == 200
    assert stale.json()["status"] == "stale"
    assert client.get("/v1/sync/items").json()[0]["name"] == "Очки"


def test_delete_is_a_tombstone(client: TestClient) -> None:
    assert client.post("/v1/sync/events", json=event()).status_code == 200
    deleted = client.post(
        "/v1/sync/events",
        json=event(operation_id="op-delete", operation="DELETE", updated_at=200, name=None, epc=None),
    )

    assert deleted.status_code == 200
    assert deleted.json()["status"] == "applied"
    row = client.get("/v1/sync/items").json()[0]
    assert row["operation"] == "DELETE"
    assert row["name"] is None
