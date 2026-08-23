from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from rfidhunter_server.app import create_app
from rfidhunter_server.config import Settings


@pytest.fixture
def client(tmp_path: Path) -> TestClient:
    settings = Settings(
        database_path=tmp_path / "backup.sqlite3",
        files_dir=tmp_path / "files",
        max_file_bytes=1024,
    )
    with TestClient(create_app(settings)) as test_client:
        yield test_client
