# RFIDHunter backup server

Локальный сервер для инкрементальных бэкапов каталога RFIDHunter. Он принимает
фотографии по SHA-256 и события `UPSERT`/`DELETE` из Android-клиента, хранит
состояние в SQLite и не применяет одну и ту же операцию дважды.

## Запуск через uv

Из каталога `server`:

```powershell
uv sync --dev
uv run python -m rfidhunter_server --host 127.0.0.1 --port 8080
```

Проверка:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/<token>/health
uv run pytest
```

At first start the server creates a random URL-safe token in
`server/data/access_token`. Read it from that protected file and configure the Android service screen
with the full base URL, for example `http://host:8080/<token>`. To supply a
known value use `RFIDHUNTER_TOKEN`; to store it elsewhere use
`RFIDHUNTER_TOKEN_FILE`.

For new installations no administrator action is needed. In the service screen
the owner enters a password (minimum 8 characters) and the app calls:

```text
POST /<token>/v1/accounts/register
POST /<token>/v1/accounts/restore
```

`register` creates a catalog and its first signed device. `restore` verifies
the same password, attaches a replacement phone to that catalog, and revokes
the old device keys. Passwords are never stored in plaintext: the server uses
a persistent pepper for lookup and a salted scrypt verifier. Use HTTPS in
production. The catalog remains entirely local until the owner performs this
step. Registration/recovery attempts are rate-limited, and upload quotas are
calculated across the whole catalog rather than per handset.

The phone generates an ECDSA P-256 key in Android Keystore and sends only its
public key. To inspect or revoke devices use `--list-devices` and
`--revoke-device <device_id>`.

По умолчанию данные находятся в `server/data/` (этот каталог не коммитится).
Для другого места задайте `RFIDHUNTER_DATA_DIR`, либо отдельно
`RFIDHUNTER_DATABASE` и `RFIDHUNTER_FILES_DIR`.

## API

```text
POST /<token>/v1/accounts/register
POST /<token>/v1/accounts/restore
PUT  /<token>/v1/files/{sha256}
POST /<token>/v1/sync/events
GET  /<token>/v1/sync/items?device_id=...
GET  /<token>/v1/files/{sha256}
```

Connections whose first request does not include the exact token prefix are
closed without an HTTP response. The token is deliberately not committed.
It prevents casual discovery of the API but does not replace HTTPS or network
access control; use both when the server is exposed outside a trusted LAN.

All API calls except health and account access also require a signed request. The
server validates `X-RFID-Device-Id`, `X-RFID-Key-Id`, `X-RFID-Timestamp`,
`X-RFID-Nonce` and `X-RFID-Signature`. Uploads require the `UPLOAD` scope and
are subject to per-device byte/request quotas. Item and file downloads require
`RESTORE` and are restricted to that device's own namespace.

Сначала отправляется JPEG-файл. Сервер проверяет, что SHA-256 тела совпадает с
путём. Затем отправляется событие. Для `UPSERT` обязательны `name` и `epc`;
если событие ссылается на фото, файл должен быть загружен заранее.

`operationId` — ключ идемпотентности. Повтор того же события вернёт
`already_applied`, а повтор с другим содержимым вернёт `409 Conflict`.
Событие с более старым `updatedAt` не затирает новое и получает статус `stale`.

Пример события:

```json
{
  "operationId": "a7d5f7cf-2a36-4f4e-bf93-5d6c3a4c8d5c",
  "deviceId": "phone-1",
  "operation": "UPSERT",
  "syncId": "item-1",
  "itemId": 42,
  "name": "Очки",
  "epc": "E2000017221101441890AB01",
  "photoSha256": null,
  "updatedAt": 1730000000000
}
```

Аутентификация, HTTPS reverse proxy, резервирование самой серверной SQLite и
удалённое файловое хранилище будут добавлены после проверки локального API.
