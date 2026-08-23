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
`server/data/access_token` and logs it. Configure the Android service screen
with the full base URL, for example `http://host:8080/<token>`. To supply a
known value use `RFIDHUNTER_TOKEN`; to store it elsewhere use
`RFIDHUNTER_TOKEN_FILE`.

По умолчанию данные находятся в `server/data/` (этот каталог не коммитится).
Для другого места задайте `RFIDHUNTER_DATA_DIR`, либо отдельно
`RFIDHUNTER_DATABASE` и `RFIDHUNTER_FILES_DIR`.

## API

```text
PUT  /<token>/v1/files/{sha256}
POST /<token>/v1/sync/events
GET  /<token>/v1/sync/items?device_id=...
GET  /<token>/v1/files/{sha256}
```

Connections whose first request does not include the exact token prefix are
closed without an HTTP response. The token is deliberately not committed.
It prevents casual discovery of the API but does not replace HTTPS or network
access control; use both when the server is exposed outside a trusted LAN.

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
