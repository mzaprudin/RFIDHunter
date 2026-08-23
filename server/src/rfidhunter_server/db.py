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
"""


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.initialize()

    def initialize(self) -> None:
        with self.connection() as connection:
            connection.executescript(SCHEMA)

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
