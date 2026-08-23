from __future__ import annotations

import hashlib
import re
import sqlite3
import tempfile
import logging
from threading import Lock
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request, status
from fastapi.responses import FileResponse

from .config import Settings
from .db import Database
from .schemas import FileResult, SyncEvent, SyncResult


SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
logger = logging.getLogger(__name__)


class TokenPathMiddleware:
    """Expose the app only below an unguessable path prefix.

    The TCP protocol guard closes unprefixed connections before they reach
    ASGI. This middleware both protects direct ASGI use and removes the token
    before handing a valid path to the FastAPI router.
    """

    def __init__(self, app, token: str):
        self.app = app
        self.prefix = f"/{token}"

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        if path != self.prefix and not path.startswith(f"{self.prefix}/"):
            # TestClient and other in-process ASGI consumers have no transport
            # to close, so keep their behaviour equivalent: no HTTP response.
            return
        trimmed = path[len(self.prefix):] or "/"
        forwarded_scope = dict(scope)
        forwarded_scope["path"] = trimmed
        raw_path = scope.get("raw_path")
        if raw_path is not None:
            prefix_bytes = self.prefix.encode("utf-8")
            forwarded_scope["raw_path"] = raw_path[len(prefix_bytes):] or b"/"
        await self.app(forwarded_scope, receive, send)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    settings.files_dir.mkdir(parents=True, exist_ok=True)
    database = Database(settings.database_path)
    file_write_lock = Lock()
    app = FastAPI(title="RFIDHunter Backup API", version="0.1.0")
    app.state.settings = settings
    app.state.database = database
    app.add_middleware(TokenPathMiddleware, token=settings.access_token)
    logger.info("RFIDHunter backup token: %s", settings.access_token)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.put("/v1/files/{sha256}", response_model=FileResult)
    async def upload_file(sha256: str, request: Request) -> FileResult:
        if not SHA256_RE.fullmatch(sha256):
            raise HTTPException(status_code=422, detail="sha256 must be a 64-character hex digest")
        normalized_hash = sha256.lower()
        target = _file_path(settings.files_dir, normalized_hash)
        if target.is_file():
            return FileResult(sha256=normalized_hash, sizeBytes=target.stat().st_size, status="already_exists")

        body = await request.body()
        if len(body) > settings.max_file_bytes:
            raise HTTPException(status_code=413, detail="file is too large")
        actual_hash = hashlib.sha256(body).hexdigest()
        if actual_hash != normalized_hash:
            raise HTTPException(status_code=422, detail="file SHA-256 does not match path")

        target.parent.mkdir(parents=True, exist_ok=True)
        with file_write_lock:
            if target.is_file():
                return FileResult(sha256=normalized_hash, sizeBytes=target.stat().st_size, status="already_exists")
            temporary_path: Path | None = None
            try:
                with tempfile.NamedTemporaryFile(dir=target.parent, prefix=f".{target.name}.", delete=False) as temporary:
                    temporary.write(body)
                    temporary_path = Path(temporary.name)
                temporary_path.replace(target)
            finally:
                if temporary_path is not None:
                    temporary_path.unlink(missing_ok=True)
        with database.connection() as connection:
            connection.execute(
                "INSERT OR IGNORE INTO files(sha256, size_bytes) VALUES(?, ?)",
                (normalized_hash, len(body)),
            )
        return FileResult(sha256=normalized_hash, sizeBytes=len(body), status="stored")

    @app.get("/v1/files/{sha256}")
    def download_file(sha256: str) -> FileResponse:
        if not SHA256_RE.fullmatch(sha256):
            raise HTTPException(status_code=422, detail="sha256 must be a 64-character hex digest")
        target = _file_path(settings.files_dir, sha256.lower())
        if not target.is_file():
            raise HTTPException(status_code=404, detail="file not found")
        return FileResponse(target, media_type="application/octet-stream")

    @app.post("/v1/sync/events", response_model=SyncResult)
    def sync_event(event: SyncEvent, x_rfid_operation_id: str | None = Header(default=None)) -> SyncResult:
        if x_rfid_operation_id is not None and x_rfid_operation_id != event.operationId:
            raise HTTPException(status_code=400, detail="X-RFID-Operation-Id does not match operationId")
        if event.operation == "UPSERT" and (not event.name or not event.epc):
            raise HTTPException(status_code=422, detail="UPSERT requires name and epc")
        if event.operation == "DELETE" and event.photoSha256 is not None:
            raise HTTPException(status_code=422, detail="DELETE cannot contain photoSha256")
        if event.photoSha256 and not _file_path(settings.files_dir, event.photoSha256).is_file():
            raise HTTPException(status_code=409, detail="photo must be uploaded before the event")

        payload = event.model_dump()
        payload_json = database.json_payload(payload)
        with database.connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                existing_operation = connection.execute(
                    "SELECT payload_json, result FROM operations WHERE operation_id = ?",
                    (event.operationId,),
                ).fetchone()
                if existing_operation:
                    if existing_operation["payload_json"] != payload_json:
                        raise HTTPException(status_code=409, detail="operationId was already used with another payload")
                    connection.execute("COMMIT")
                    return SyncResult(operationId=event.operationId, status="already_applied")

                current = connection.execute(
                    "SELECT updated_at FROM items WHERE device_id = ? AND sync_id = ?",
                    (event.deviceId, event.syncId),
                ).fetchone()
                result: str = "applied"
                if current and event.updatedAt < current["updated_at"]:
                    result = "stale"
                elif event.operation == "DELETE":
                    connection.execute(
                        """INSERT INTO items(device_id, sync_id, item_id, operation, name, epc, photo_sha256, updated_at)
                           VALUES(?, ?, ?, 'DELETE', NULL, NULL, NULL, ?)
                           ON CONFLICT(device_id, sync_id) DO UPDATE SET
                               item_id = excluded.item_id, operation = excluded.operation,
                               name = NULL, epc = NULL, photo_sha256 = NULL,
                               updated_at = excluded.updated_at, received_at = CURRENT_TIMESTAMP""",
                        (event.deviceId, event.syncId, event.itemId, event.updatedAt),
                    )
                else:
                    connection.execute(
                        """INSERT INTO items(device_id, sync_id, item_id, operation, name, epc, photo_sha256, updated_at)
                           VALUES(?, ?, ?, 'UPSERT', ?, ?, ?, ?)
                           ON CONFLICT(device_id, sync_id) DO UPDATE SET
                               item_id = excluded.item_id, operation = excluded.operation,
                               name = excluded.name, epc = excluded.epc,
                               photo_sha256 = excluded.photo_sha256,
                               updated_at = excluded.updated_at, received_at = CURRENT_TIMESTAMP""",
                        (event.deviceId, event.syncId, event.itemId, event.name, event.epc, event.photoSha256, event.updatedAt),
                    )
                connection.execute(
                    "INSERT INTO operations(operation_id, device_id, sync_id, operation, payload_json, result) VALUES(?, ?, ?, ?, ?, ?)",
                    (event.operationId, event.deviceId, event.syncId, event.operation, payload_json, result),
                )
                connection.execute("COMMIT")
                return SyncResult(operationId=event.operationId, status=result)  # type: ignore[arg-type]
            except HTTPException:
                connection.execute("ROLLBACK")
                raise
            except sqlite3.Error:
                connection.execute("ROLLBACK")
                raise

    @app.get("/v1/sync/items")
    def list_items(device_id: str | None = None) -> list[dict[str, object]]:
        with database.connection() as connection:
            if device_id:
                rows = connection.execute("SELECT * FROM items WHERE device_id = ? ORDER BY updated_at", (device_id,)).fetchall()
            else:
                rows = connection.execute("SELECT * FROM items ORDER BY device_id, updated_at").fetchall()
        return [dict(row) for row in rows]

    return app


def _file_path(files_dir: Path, sha256: str) -> Path:
    normalized = sha256.lower()
    return files_dir / normalized[:2] / normalized


app = create_app()
