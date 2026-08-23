# Incremental catalog backup

The Android client keeps the catalog local and records every catalog mutation
in the Room `sync_outbox` table. The outbox is part of the same database
transaction as the item insert/update/delete, so a process kill cannot leave a
catalog change without its backup event.

Pending changes are compacted per item: if an item is edited several times
while offline, only its newest pending event remains. A delete is represented
by a tombstone event so the server can remove a previously uploaded item.

`WorkManager` runs one unique worker when a network connection is available.
The worker sends at most 16 events per run and keeps an event until the server
acknowledges it. Temporary network/server errors are retried; permanent HTTP
errors remain visible in the service screen and can be retried with the manual
button after correcting the endpoint.

## Server contract

The endpoint is configured later in the service screen. Production should use
HTTPS. The client currently expects:

```text
PUT  {endpoint}/v1/files/{sha256}
POST {endpoint}/v1/sync/events
```

The file request contains the JPEG bytes and is idempotent by SHA-256. A
`2xx` or `409 Conflict` response is accepted. The event request is JSON and
contains `operationId`, `deviceId`, `operation` (`UPSERT` or `DELETE`),
`syncId`, `itemId`, `name`, `epc`, `photoSha256`, and `updatedAt`. The
`operationId` is also sent as `X-RFID-Operation-Id`; the server must treat a
repeated operation ID as an already-applied operation.

Before a catalog photo reaches this queue, the Android app centre-crops it to
portrait 3:4 and stores a JPEG no larger than 1080x1440 at quality 82. The
original camera file is removed, so neither the local catalog nor the backup
traffic contains full-resolution photos.

The device ID is generated once and stored locally. It is not an Android ID
and is not sent until an endpoint is configured. Authentication is deliberately
left for the server phase; credentials must not be hard-coded into the APK.

Room migration `1 -> 2` assigns stable sync IDs to existing rows and queues
them once for the initial import. A future migration must preserve the
`syncId` and `sync_outbox` tables.
