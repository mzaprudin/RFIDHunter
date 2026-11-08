# RFIDHunter backup server

[Русская версия](README.RU.md) · [Application overview](../README.md) · [Backup architecture and protocol](../BACKUP_SYNC.md)

This server backs up the RFIDHunter catalog and restores it on a replacement phone. The Android app keeps working offline; backup is enabled from its service screen. FastAPI accepts catalog changes and photographs, SQLite stores the catalog and operation history, and JPEG files are stored on disk.

## Engineering decisions

- **Retryable changes.** Each event has an `operationId`. Repeating the same event returns `already_applied`; reusing its ID with different contents returns `409 Conflict`. Older updates return `stale`, and deletions remain as tombstones for restoration.
- **Photographs addressed by content.** The client uploads a JPEG before its catalog event. The server verifies the SHA-256 digest in the URL and reuses the stored file on repeated uploads.
- **A catalog survives phone replacement.** Password-based registration binds the first device to an account. Restore attaches a new phone to the same catalog and revokes the previous devices. Upload limits and restore access apply to the account's catalog, including data uploaded from its earlier phones.
- **Signed requests.** The phone keeps an ECDSA P-256 private key in Android Keystore. The server checks signatures, timestamps, nonces, and `UPLOAD`/`RESTORE` permissions. Passwords use a salted scrypt verifier and a persistent server-side pepper; registration and recovery attempts are rate-limited.

The Android transactional outbox, request fields, and restore merge rules are described in [BACKUP_SYNC.md](../BACKUP_SYNC.md).

## Run locally

Requires Python 3.11+ and `uv`. From `server/`:

```sh
uv sync --dev
uv run python -m rfidhunter_server --host 127.0.0.1 --port 8080
```

These commands also work in PowerShell. At first startup the server creates `data/access_token` and `data/password_pepper`. Keep these files with the server data. In a second terminal, check health:

Bash:

```sh
rfidToken=$(cat data/access_token)
curl --fail "http://127.0.0.1:8080/$rfidToken/health"
```

PowerShell:

```powershell
$rfidToken = (Get-Content data/access_token -Raw).Trim()
Invoke-RestMethod "http://127.0.0.1:8080/$rfidToken/health"
```

For a phone on the same trusted LAN, bind to the server's LAN address and set the app endpoint to `http://SERVER_LAN_IP:8080/<token>`. HTTP is accepted by Android debug builds. For deployed use, configure HTTPS and use the full endpoint `https://backup.example/<token>`.

In the app's service screen, enter a password of at least eight characters to register a catalog. On a replacement phone, use the same password and choose restore. Account access needs no administrator pairing step.

## Configuration and deployment

Paths are relative to the process working directory. The default data directory is `./data` and is ignored by Git.

| Environment variable | Purpose |
| --- | --- |
| `RFIDHUNTER_DATA_DIR` | Base directory for persistent data |
| `RFIDHUNTER_DATABASE`, `RFIDHUNTER_FILES_DIR` | Override the SQLite and photograph paths |
| `RFIDHUNTER_TOKEN`, `RFIDHUNTER_TOKEN_FILE` | Supply the secret URL prefix or change its file location |
| `RFIDHUNTER_PASSWORD_PEPPER`, `RFIDHUNTER_PASSWORD_PEPPER_FILE` | Supply the password lookup pepper or change its file location |

File-size, catalog-storage, and upload-window limits are configured in [config.py](src/rfidhunter_server/config.py). The setting named `RFIDHUNTER_MAX_STORAGE_BYTES_PER_DEVICE` is retained for compatibility; its limit is calculated across the account's catalog.

The [systemd unit](deploy/rfidhunter.service) and [nginx HTTPS configuration](deploy/rfid-nginx.conf) are included. Adapt their user, paths, hostname, certificates, and `__TOKEN__` placeholder to the deployment. The Python entry point closes connections whose first request lacks the exact token prefix; nginx also drops unmatched paths. The prefix is an additional path guard, while request signatures authenticate devices. Use HTTPS for account access and synchronization.

Device administration uses the same data configuration as the server:

```sh
uv run python -m rfidhunter_server --list-devices
uv run python -m rfidhunter_server --revoke-device DEVICE_ID
```

Server database backup and remote file storage remain future work.

## API and verification

All routes include the configured `/<token>` prefix:

| Method and path | Purpose |
| --- | --- |
| `GET /health` | Health check |
| `POST /v1/accounts/register` | Create a catalog and register its first device |
| `POST /v1/accounts/restore` | Attach a replacement phone and revoke earlier devices |
| `PUT /v1/files/{sha256}` | Upload a photograph |
| `POST /v1/sync/events` | Apply a catalog change |
| `GET /v1/sync/items?device_id=...` | Read the account's catalog snapshot |
| `GET /v1/files/{sha256}` | Download a photograph belonging to that catalog |

Health and password-based account access are the exceptions to device signing. Other calls require `X-RFID-Device-Id`, `X-RFID-Key-Id`, `X-RFID-Timestamp`, `X-RFID-Nonce`, and `X-RFID-Signature`.

Run the existing API tests from `server/`:

```sh
uv run pytest
```

The 14 tests cover route guarding, signatures and nonce replay, catalog isolation, file hashes and limits, event ordering and idempotency, tombstones, and password-based recovery on another device.
