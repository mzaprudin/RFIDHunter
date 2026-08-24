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
    max_storage_bytes_per_device: int = 512 * 1024 * 1024
    max_upload_bytes_per_window: int = 256 * 1024 * 1024
    upload_window_seconds: int = 24 * 60 * 60
    max_upload_requests_per_window: int = 1000
    auth_clock_skew_seconds: int = 300
    password_pepper: str = "development-pepper"

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
        pepper = os.getenv("RFIDHUNTER_PASSWORD_PEPPER", "").strip()
        pepper_file = Path(os.getenv("RFIDHUNTER_PASSWORD_PEPPER_FILE", str(data_dir / "password_pepper"))).expanduser()
        if not pepper:
            try:
                pepper = pepper_file.read_text(encoding="ascii").strip()
            except (FileNotFoundError, OSError, UnicodeError):
                pepper = ""
        if not pepper:
            pepper = secrets.token_urlsafe(32)
            pepper_file.parent.mkdir(parents=True, exist_ok=True)
            pepper_file.write_text(f"{pepper}\n", encoding="ascii")
        return cls(
            database_path=Path(os.getenv("RFIDHUNTER_DATABASE", str(data_dir / "rfidhunter.sqlite3"))),
            files_dir=Path(os.getenv("RFIDHUNTER_FILES_DIR", str(data_dir / "files"))),
            access_token=token,
            max_file_bytes=int(os.getenv("RFIDHUNTER_MAX_FILE_BYTES", str(15 * 1024 * 1024))),
            max_storage_bytes_per_device=int(os.getenv("RFIDHUNTER_MAX_STORAGE_BYTES_PER_DEVICE", str(512 * 1024 * 1024))),
            max_upload_bytes_per_window=int(os.getenv("RFIDHUNTER_MAX_UPLOAD_BYTES_PER_WINDOW", str(256 * 1024 * 1024))),
            upload_window_seconds=int(os.getenv("RFIDHUNTER_UPLOAD_WINDOW_SECONDS", str(24 * 60 * 60))),
            max_upload_requests_per_window=int(os.getenv("RFIDHUNTER_MAX_UPLOAD_REQUESTS_PER_WINDOW", "1000")),
            auth_clock_skew_seconds=int(os.getenv("RFIDHUNTER_AUTH_CLOCK_SKEW_SECONDS", "300")),
            password_pepper=pepper,
        )
