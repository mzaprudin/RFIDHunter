from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


SCHEMA = """
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS files (
    sha256 TEXT PRIMARY KEY,
    size_bytes INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS accounts (
    account_id TEXT PRIMARY KEY,
    password_lookup TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'REVOKED')) DEFAULT 'ACTIVE',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT
);

CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL,
    key_id TEXT NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    display_name TEXT,
    scopes TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'REVOKED')) DEFAULT 'ACTIVE',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT
);

CREATE TABLE IF NOT EXISTS request_nonces (
    device_id TEXT NOT NULL,
    nonce TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    PRIMARY KEY (device_id, nonce)
);

CREATE TABLE IF NOT EXISTS device_files (
    device_id TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, sha256),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (sha256) REFERENCES files(sha256)
);

CREATE TABLE IF NOT EXISTS upload_attempts (
    device_id TEXT NOT NULL,
    occurred_at INTEGER NOT NULL,
    bytes_count INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS account_access_attempts (
    remote_key TEXT NOT NULL,
    occurred_at INTEGER NOT NULL,
    successful INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS items (
    device_id TEXT NOT NULL,
    sync_id TEXT NOT NULL,
    item_id INTEGER,
    operation TEXT NOT NULL CHECK(operation IN ('UPSERT', 'DELETE')),
    name TEXT,
    epc TEXT,
    photo_sha256 TEXT,
    updated_at INTEGER NOT NULL,
    received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, sync_id),
    FOREIGN KEY (photo_sha256) REFERENCES files(sha256)
);

CREATE TABLE IF NOT EXISTS operations (
    operation_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    sync_id TEXT NOT NULL,
    operation TEXT NOT NULL CHECK(operation IN ('UPSERT', 'DELETE')),
    payload_json TEXT NOT NULL,
    result TEXT NOT NULL,
    received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_items_device_updated
    ON items(device_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_operations_device_sync
    ON operations(device_id, sync_id);
CREATE INDEX IF NOT EXISTS idx_request_nonces_timestamp
    ON request_nonces(timestamp);
CREATE INDEX IF NOT EXISTS idx_upload_attempts_device_time
    ON upload_attempts(device_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_account_access_attempts_time
    ON account_access_attempts(remote_key, occurred_at);
CREATE INDEX IF NOT EXISTS idx_device_files_hash ON device_files(sha256);
"""


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.initialize()

    def initialize(self) -> None:
        with self.connection() as connection:
            connection.executescript(SCHEMA)
            # The signed-device schema predates password accounts.  Keep all
            # existing catalogs intact by assigning each legacy device its own
            # account until the owner explicitly enables password backup.
            columns = {row[1] for row in connection.execute("PRAGMA table_info(devices)").fetchall()}
            if "account_id" not in columns:
                connection.execute("ALTER TABLE devices ADD COLUMN account_id TEXT")
            connection.execute("UPDATE devices SET account_id = device_id WHERE account_id IS NULL OR account_id = ''")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_id)")
            legacy_rows = connection.execute(
                "SELECT device_id FROM devices WHERE device_id NOT IN (SELECT account_id FROM accounts)"
            ).fetchall()
            for row in legacy_rows:
                device_id = row[0]
                connection.execute(
                    "INSERT OR IGNORE INTO accounts(account_id, password_lookup, password_hash) VALUES(?, ?, '')",
                    (device_id, f"legacy:{device_id}"),
                )

    @contextmanager
    def connection(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA foreign_keys = ON")
            yield connection
        finally:
            connection.close()

    @staticmethod
    def json_payload(payload: dict[str, Any]) -> str:
        return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
