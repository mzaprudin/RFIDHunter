# RFIDHunter backup server

Локальный сервер для инкрементальных бэкапов каталога RFIDHunter. Он принимает
фотографии по SHA-256 и события `UPSERT`/`DELETE` из Android-клиента, хранит
состояние в SQLite и не применяет одну и ту же операцию дважды.

## Запуск через uv

Из каталога `server`:

```powershell
uv sync --dev
uv run uvicorn rfidhunter_server.app:app --reload --host 127.0.0.1 --port 8080
```

Проверка:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
uv run pytest
```

По умолчанию данные находятся в `server/data/` (этот каталог не коммитится).
Для другого места задайте `RFIDHUNTER_DATA_DIR`, либо отдельно
`RFIDHUNTER_DATABASE` и `RFIDHUNTER_FILES_DIR`.

## API

```text
PUT  /v1/files/{sha256}
POST /v1/sync/events
GET  /v1/sync/items?device_id=...
GET  /v1/files/{sha256}
```

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
