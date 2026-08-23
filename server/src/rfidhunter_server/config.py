from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database_path: Path
    files_dir: Path
    max_file_bytes: int = 15 * 1024 * 1024

    @classmethod
    def from_env(cls) -> "Settings":
        data_dir = Path(os.getenv("RFIDHUNTER_DATA_DIR", "./data")).expanduser()
        return cls(
            database_path=Path(os.getenv("RFIDHUNTER_DATABASE", str(data_dir / "rfidhunter.sqlite3"))),
            files_dir=Path(os.getenv("RFIDHUNTER_FILES_DIR", str(data_dir / "files"))),
            max_file_bytes=int(os.getenv("RFIDHUNTER_MAX_FILE_BYTES", str(15 * 1024 * 1024))),
        )
