from __future__ import annotations

import os
import secrets
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database_path: Path
    files_dir: Path
    access_token: str = "test-token"
    max_file_bytes: int = 15 * 1024 * 1024

    @classmethod
    def from_env(cls) -> "Settings":
        data_dir = Path(os.getenv("RFIDHUNTER_DATA_DIR", "./data")).expanduser()
        token = os.getenv("RFIDHUNTER_TOKEN", "").strip()
        token_file = Path(os.getenv("RFIDHUNTER_TOKEN_FILE", str(data_dir / "access_token"))).expanduser()
        if not token:
            try:
                token = token_file.read_text(encoding="ascii").strip()
            except (FileNotFoundError, OSError, UnicodeError):
                token = ""
        if not token:
            token = secrets.token_urlsafe(32)
            token_file.parent.mkdir(parents=True, exist_ok=True)
            token_file.write_text(f"{token}\n", encoding="ascii")
        return cls(
            database_path=Path(os.getenv("RFIDHUNTER_DATABASE", str(data_dir / "rfidhunter.sqlite3"))),
            files_dir=Path(os.getenv("RFIDHUNTER_FILES_DIR", str(data_dir / "files"))),
            access_token=token,
            max_file_bytes=int(os.getenv("RFIDHUNTER_MAX_FILE_BYTES", str(15 * 1024 * 1024))),
        )
