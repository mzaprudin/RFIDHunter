from __future__ import annotations

import hashlib
import base64
import binascii
import hmac
import json
import re
import secrets
import sqlite3
import tempfile
import logging
import time
import uuid
from dataclasses import dataclass
from threading import Lock
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request, status
from fastapi.responses import FileResponse
from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .config import Settings
from .db import Database
from .schemas import (
    AccountAccessRequest,
    AccountAccessResponse,
    FileResult,
    RestorePage,
    SyncEvent,
    SyncResult,
)


SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
logger = logging.getLogger(__name__)
UPLOAD_SCOPE = "UPLOAD"
RESTORE_SCOPE = "RESTORE"
SUPPORTED_SCOPES = frozenset({UPLOAD_SCOPE, RESTORE_SCOPE})


@dataclass(frozen=True)
class AuthenticatedDevice:
    device_id: str
    key_id: str
    account_id: str
    scopes: frozenset[str]

    def can(self, scope: str) -> bool:
        return scope in self.scopes


def _password_lookup(password: str, pepper: str) -> str:
    # The server-side pepper keeps a database-only leak from becoming an
    # offline password dictionary.  Passwords are accepted only over HTTPS in
    # production; they are never persisted in plaintext.
    return hmac.new(pepper.encode("utf-8"), password.encode("utf-8"), hashlib.sha256).hexdigest()


def _password_hash(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=16_384, r=8, p=1, dklen=32)
    return "scrypt$16384$8$1${}${}".format(
        base64.urlsafe_b64encode(salt).decode("ascii").rstrip("="),
        base64.urlsafe_b64encode(digest).decode("ascii").rstrip("="),
    )


def _verify_password(password: str, encoded: str) -> bool:
    try:
        scheme, n_text, r_text, p_text, salt_text, digest_text = encoded.split("$", 5)
        if scheme != "scrypt":
            return False
        pad = lambda value: value + "=" * (-len(value) % 4)
        salt = base64.urlsafe_b64decode(pad(salt_text))
        expected = base64.urlsafe_b64decode(pad(digest_text))
        actual = hashlib.scrypt(
            password.encode("utf-8"), salt=salt, n=int(n_text), r=int(r_text), p=int(p_text), dklen=len(expected)
        )
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError, binascii.Error):
        return False


def _validate_public_key(enrollment: AccountAccessRequest) -> None:
    try:
        key_bytes = base64.b64decode(enrollment.publicKey, validate=True)
        public_key = serialization.load_der_public_key(key_bytes)
    except (ValueError, TypeError, binascii.Error, UnsupportedAlgorithm) as error:
        raise HTTPException(status_code=422, detail="publicKey must be base64 encoded DER") from error
    if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
        raise HTTPException(status_code=422, detail="publicKey must be an ECDSA P-256 key")


def _scopes_text(scopes: set[str] | frozenset[str] | list[str]) -> str:
    return ",".join(sorted(set(scopes) & SUPPORTED_SCOPES))


def _scopes_from_text(value: str) -> frozenset[str]:
    return frozenset(part for part in value.split(",") if part in SUPPORTED_SCOPES)


def _canonical_request(request: Request, body_hash: str, device_id: str, timestamp: str, nonce: str) -> bytes:
    query = request.scope.get("query_string", b"").decode("ascii")
    return "\n".join((request.method.upper(), request.url.path, query, device_id, timestamp, nonce, body_hash)).encode("utf-8")


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

    def authenticate(request: Request, body_hash: str, required_scope: str) -> AuthenticatedDevice:
        device_id = request.headers.get("X-RFID-Device-Id", "").strip()
        key_id = request.headers.get("X-RFID-Key-Id", "").strip()
        timestamp_text = request.headers.get("X-RFID-Timestamp", "").strip()
        nonce = request.headers.get("X-RFID-Nonce", "").strip()
        signature_text = request.headers.get("X-RFID-Signature", "").strip()
        if not all((device_id, key_id, timestamp_text, nonce, signature_text)):
            raise HTTPException(status_code=401, detail="device authentication headers are required")
        try:
            timestamp = int(timestamp_text)
        except ValueError as error:
            raise HTTPException(status_code=401, detail="invalid authentication timestamp") from error
        if abs(int(time.time()) - timestamp) > settings.auth_clock_skew_seconds:
            raise HTTPException(status_code=401, detail="authentication timestamp expired")
        with database.connection() as connection:
            row = connection.execute(
                "SELECT device_id, key_id, account_id, public_key, scopes, status FROM devices WHERE device_id = ?",
                (device_id,),
            ).fetchone()
            if row is None or row["status"] != "ACTIVE" or row["key_id"] != key_id:
                raise HTTPException(status_code=401, detail="device is not registered")
            scopes = _scopes_from_text(row["scopes"])
            if required_scope not in scopes:
                raise HTTPException(status_code=403, detail=f"scope {required_scope} is required")
            try:
                public_key = serialization.load_der_public_key(base64.b64decode(row["public_key"], validate=True))
                signature = base64.b64decode(signature_text, validate=True)
            except (ValueError, TypeError, binascii.Error, UnsupportedAlgorithm) as error:
                raise HTTPException(status_code=401, detail="invalid device key or signature encoding") from error
            if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
                raise HTTPException(status_code=401, detail="unsupported device key")
            try:
                public_key.verify(signature, _canonical_request(request, body_hash, device_id, timestamp_text, nonce), ec.ECDSA(hashes.SHA256()))
            except (InvalidSignature, ValueError) as error:
                raise HTTPException(status_code=401, detail="invalid device signature") from error
            connection.execute("DELETE FROM request_nonces WHERE timestamp < ?", (int(time.time()) - settings.auth_clock_skew_seconds * 2,))
            try:
                connection.execute(
                    "INSERT INTO request_nonces(device_id, nonce, timestamp) VALUES(?, ?, ?)",
                    (device_id, nonce, timestamp),
                )
            except sqlite3.IntegrityError as error:
                raise HTTPException(status_code=401, detail="request nonce was already used") from error
            connection.execute("UPDATE devices SET last_seen_at = CURRENT_TIMESTAMP WHERE device_id = ?", (device_id,))
            connection.execute("UPDATE accounts SET last_seen_at = CURRENT_TIMESTAMP WHERE account_id = ?", (row["account_id"],))
        return AuthenticatedDevice(device_id=device_id, key_id=key_id, account_id=row["account_id"], scopes=scopes)

    def access_response(account_id: str, request: AccountAccessRequest, created: bool, scopes: frozenset[str]) -> AccountAccessResponse:
        return AccountAccessResponse(
            accountId=account_id,
            deviceId=request.deviceId,
            keyId=request.keyId,
            scopes=sorted(scopes),
            created=created,
            serverTime=int(time.time() * 1000),
        )

    def check_account_access_rate(request: Request) -> None:
        # Registration/recovery is intentionally unauthenticated, so bound
        # password guessing and account spraying before a device key exists.
        remote_key = request.client.host if request.client is not None else "unknown"
        now = int(time.time())
        window_start = now - 60 * 60
        with database.connection() as connection:
            connection.execute("DELETE FROM account_access_attempts WHERE occurred_at < ?", (window_start,))
            count = connection.execute(
                "SELECT COUNT(*) AS count FROM account_access_attempts WHERE remote_key = ? AND occurred_at >= ?",
                (remote_key, window_start),
            ).fetchone()["count"]
            if count >= 20:
                raise HTTPException(status_code=429, detail="too many account attempts; try again later")
            connection.execute(
                "INSERT INTO account_access_attempts(remote_key, occurred_at) VALUES(?, ?)", (remote_key, now)
            )

    @app.post("/v1/accounts/register", response_model=AccountAccessResponse)
    def register_account(access: AccountAccessRequest, request: Request) -> AccountAccessResponse:
        """Create a catalog account and bind this installation as its first device."""
        check_account_access_rate(request)
        _validate_public_key(access)
        lookup = _password_lookup(access.password, settings.password_pepper)
        scopes = frozenset({UPLOAD_SCOPE, RESTORE_SCOPE})
        account_id = str(uuid.uuid4())
        with database.connection() as connection:
            if connection.execute("SELECT 1 FROM accounts WHERE password_lookup = ? AND password_hash != ''", (lookup,)).fetchone():
                raise HTTPException(status_code=409, detail="an account with this password already exists; use restore")
            existing_device = connection.execute(
                "SELECT key_id, public_key FROM devices WHERE device_id = ?", (access.deviceId,)
            ).fetchone()
            if existing_device is not None and (existing_device["key_id"] != access.keyId or existing_device["public_key"] != access.publicKey):
                raise HTTPException(status_code=409, detail="device id is already bound to another key")
            key_exists = connection.execute("SELECT device_id FROM devices WHERE key_id = ? AND device_id != ?", (access.keyId, access.deviceId)).fetchone()
            if key_exists is not None:
                raise HTTPException(status_code=409, detail="key_id is already registered")
            connection.execute(
                "INSERT INTO accounts(account_id, password_lookup, password_hash) VALUES(?, ?, ?)",
                (account_id, lookup, _password_hash(access.password)),
            )
            if existing_device is None:
                connection.execute(
                    "INSERT INTO devices(device_id, account_id, key_id, public_key, display_name, scopes) VALUES(?, ?, ?, ?, ?, ?)",
                    (access.deviceId, account_id, access.keyId, access.publicKey, access.displayName, _scopes_text(set(scopes))),
                )
            else:
                connection.execute(
                    "UPDATE devices SET account_id = ?, display_name = COALESCE(?, display_name), scopes = ?, status = 'ACTIVE' WHERE device_id = ?",
                    (account_id, access.displayName, _scopes_text(set(scopes)), access.deviceId),
                )
        return access_response(account_id, access, True, scopes)

    @app.post("/v1/accounts/restore", response_model=AccountAccessResponse)
    def restore_account(access: AccountAccessRequest, request: Request) -> AccountAccessResponse:
        """Bind a fresh installation to the catalog selected by its password."""
        check_account_access_rate(request)
        _validate_public_key(access)
        lookup = _password_lookup(access.password, settings.password_pepper)
        scopes = frozenset({UPLOAD_SCOPE, RESTORE_SCOPE})
        with database.connection() as connection:
            account = connection.execute(
                "SELECT account_id, password_hash, status FROM accounts WHERE password_lookup = ?", (lookup,)
            ).fetchone()
            if account is None or account["status"] != "ACTIVE" or not _verify_password(access.password, account["password_hash"]):
                raise HTTPException(status_code=403, detail="password is incorrect or account does not exist")
            existing_device = connection.execute(
                "SELECT key_id, public_key FROM devices WHERE device_id = ?", (access.deviceId,)
            ).fetchone()
            if existing_device is not None and (existing_device["key_id"] != access.keyId or existing_device["public_key"] != access.publicKey):
                raise HTTPException(status_code=409, detail="device id is already bound to another key")
            key_exists = connection.execute("SELECT device_id FROM devices WHERE key_id = ? AND device_id != ?", (access.keyId, access.deviceId)).fetchone()
            if key_exists is not None:
                raise HTTPException(status_code=409, detail="key_id is already registered")
            if existing_device is None:
                connection.execute(
                    "INSERT INTO devices(device_id, account_id, key_id, public_key, display_name, scopes) VALUES(?, ?, ?, ?, ?, ?)",
                    (access.deviceId, account["account_id"], access.keyId, access.publicKey, access.displayName, _scopes_text(set(scopes))),
                )
            else:
                connection.execute(
                    "UPDATE devices SET account_id = ?, display_name = COALESCE(?, display_name), scopes = ?, status = 'ACTIVE' WHERE device_id = ?",
                    (account["account_id"], access.displayName, _scopes_text(set(scopes)), access.deviceId),
                )
            # A recovery is normally performed after losing a phone. Revoke
            # the old signing identities so that a lost handset cannot keep
            # downloading or uploading after the owner has recovered.
            connection.execute(
                "UPDATE devices SET status = 'REVOKED' WHERE account_id = ? AND device_id != ?",
                (account["account_id"], access.deviceId),
            )
            connection.execute("UPDATE accounts SET last_seen_at = CURRENT_TIMESTAMP WHERE account_id = ?", (account["account_id"],))
        return access_response(account["account_id"], access, False, scopes)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.put("/v1/files/{sha256}", response_model=FileResult)
    async def upload_file(sha256: str, request: Request) -> FileResult:
        body = await request.body()
        principal = authenticate(request, hashlib.sha256(body).hexdigest(), UPLOAD_SCOPE)
        if not SHA256_RE.fullmatch(sha256):
            raise HTTPException(status_code=422, detail="sha256 must be a 64-character hex digest")
        normalized_hash = sha256.lower()
        target = _file_path(settings.files_dir, normalized_hash)
        if len(body) > settings.max_file_bytes:
            raise HTTPException(status_code=413, detail="file is too large")
        actual_hash = hashlib.sha256(body).hexdigest()
        if actual_hash != normalized_hash:
            raise HTTPException(status_code=422, detail="file SHA-256 does not match path")

        target.parent.mkdir(parents=True, exist_ok=True)
        with file_write_lock:
            with database.connection() as connection:
                connection.execute("BEGIN IMMEDIATE")
                existing = target.is_file()
                if not existing:
                    usage = connection.execute(
                        """SELECT COALESCE(SUM(df.size_bytes), 0) AS total
                           FROM device_files df JOIN devices d ON d.device_id = df.device_id
                           WHERE d.account_id = ?""",
                        (principal.account_id,),
                    ).fetchone()["total"]
                    window_start = int(time.time()) - settings.upload_window_seconds
                    connection.execute("DELETE FROM upload_attempts WHERE occurred_at < ?", (window_start,))
                    attempts = connection.execute(
                        """SELECT COUNT(*) AS count, COALESCE(SUM(ua.bytes_count), 0) AS bytes
                           FROM upload_attempts ua JOIN devices d ON d.device_id = ua.device_id
                           WHERE d.account_id = ? AND ua.occurred_at >= ?""",
                        (principal.account_id, window_start),
                    ).fetchone()
                    if usage + len(body) > settings.max_storage_bytes_per_device:
                        raise HTTPException(status_code=413, detail="device storage quota exceeded")
                    if attempts["bytes"] + len(body) > settings.max_upload_bytes_per_window or attempts["count"] >= settings.max_upload_requests_per_window:
                        raise HTTPException(status_code=429, detail="device upload rate limit exceeded")
                    temporary_path: Path | None = None
                    try:
                        with tempfile.NamedTemporaryFile(dir=target.parent, prefix=f".{target.name}.", delete=False) as temporary:
                            temporary.write(body)
                            temporary_path = Path(temporary.name)
                        temporary_path.replace(target)
                    finally:
                        if temporary_path is not None:
                            temporary_path.unlink(missing_ok=True)
                    connection.execute(
                        "INSERT OR IGNORE INTO files(sha256, size_bytes) VALUES(?, ?)",
                        (normalized_hash, len(body)),
                    )
                    connection.execute(
                        "INSERT INTO upload_attempts(device_id, occurred_at, bytes_count) VALUES(?, ?, ?)",
                        (principal.device_id, int(time.time()), len(body)),
                    )
                else:
                    size = target.stat().st_size
                    connection.execute("INSERT OR IGNORE INTO files(sha256, size_bytes) VALUES(?, ?)", (normalized_hash, size))
                connection.execute(
                    "INSERT OR IGNORE INTO device_files(device_id, sha256, size_bytes) VALUES(?, ?, ?)",
                    (principal.device_id, normalized_hash, target.stat().st_size),
                )
                connection.execute("COMMIT")
        return FileResult(sha256=normalized_hash, sizeBytes=target.stat().st_size, status="already_exists" if existing else "stored")

    @app.get("/v1/files/{sha256}")
    def download_file(sha256: str, request: Request) -> FileResponse:
        principal = authenticate(request, hashlib.sha256(b"").hexdigest(), RESTORE_SCOPE)
        if not SHA256_RE.fullmatch(sha256):
            raise HTTPException(status_code=422, detail="sha256 must be a 64-character hex digest")
        target = _file_path(settings.files_dir, sha256.lower())
        if not target.is_file():
            raise HTTPException(status_code=404, detail="file not found")
        with database.connection() as connection:
            owned = connection.execute(
                """SELECT 1 FROM device_files df JOIN devices d ON d.device_id = df.device_id
                   WHERE d.account_id = ? AND df.sha256 = ?""",
                (principal.account_id, sha256.lower()),
            ).fetchone()
        if owned is None:
            raise HTTPException(status_code=404, detail="file not found")
        return FileResponse(target, media_type="application/octet-stream")

    @app.post("/v1/sync/events", response_model=SyncResult)
    async def sync_event(request: Request) -> SyncResult:
        raw_body = await request.body()
        principal = authenticate(request, hashlib.sha256(raw_body).hexdigest(), UPLOAD_SCOPE)
        try:
            event = SyncEvent.model_validate(json.loads(raw_body))
        except (json.JSONDecodeError, ValueError) as error:
            raise HTTPException(status_code=422, detail="invalid sync event") from error
        x_rfid_operation_id = request.headers.get("X-RFID-Operation-Id")
        if event.deviceId != principal.device_id:
            raise HTTPException(status_code=403, detail="event deviceId does not match authenticated device")
        if x_rfid_operation_id is not None and x_rfid_operation_id != event.operationId:
            raise HTTPException(status_code=400, detail="X-RFID-Operation-Id does not match operationId")
        if event.operation == "UPSERT" and (not event.name or not event.epc):
            raise HTTPException(status_code=422, detail="UPSERT requires name and epc")
        if event.operation == "DELETE" and event.photoSha256 is not None:
            raise HTTPException(status_code=422, detail="DELETE cannot contain photoSha256")
        if event.photoSha256:
            with database.connection() as connection:
                owned = connection.execute(
                    """SELECT 1 FROM device_files df JOIN devices d ON d.device_id = df.device_id
                       WHERE d.account_id = ? AND df.sha256 = ?""",
                    (principal.account_id, event.photoSha256),
                ).fetchone()
            if owned is None:
                raise HTTPException(status_code=409, detail="photo must be uploaded by this device before the event")

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
    def list_items(request: Request, device_id: str | None = None) -> list[dict[str, object]]:
        principal = authenticate(request, hashlib.sha256(b"").hexdigest(), RESTORE_SCOPE)
        if device_id is not None:
            with database.connection() as connection:
                requested = connection.execute("SELECT account_id FROM devices WHERE device_id = ?", (device_id,)).fetchone()
            if requested is None or requested["account_id"] != principal.account_id:
                raise HTTPException(status_code=403, detail="a device can restore only its own catalog")
        with database.connection() as connection:
            rows = connection.execute(
                """SELECT i.* FROM items i JOIN devices d ON d.device_id = i.device_id
                   WHERE d.account_id = ? ORDER BY i.updated_at, i.sync_id""",
                (principal.account_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    return app


def _file_path(files_dir: Path, sha256: str) -> Path:
    normalized = sha256.lower()
    return files_dir / normalized[:2] / normalized


def revoke_device(settings: Settings, device_id: str) -> bool:
    database = Database(settings.database_path)
    with database.connection() as connection:
        result = connection.execute("UPDATE devices SET status = 'REVOKED' WHERE device_id = ?", (device_id,))
    return result.rowcount > 0


app = create_app()
