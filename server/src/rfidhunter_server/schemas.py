from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class SyncEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    operationId: str = Field(min_length=1, max_length=128)
    deviceId: str = Field(min_length=1, max_length=128)
    operation: Literal["UPSERT", "DELETE"]
    syncId: str = Field(min_length=1, max_length=128)
    itemId: int | None = None
    name: str | None = Field(default=None, max_length=1000)
    epc: str | None = Field(default=None, max_length=512)
    photoSha256: str | None = None
    updatedAt: int = Field(ge=0)

    @field_validator("photoSha256")
    @classmethod
    def valid_photo_hash(cls, value: str | None) -> str | None:
        if value is not None and (len(value) != 64 or any(char not in "0123456789abcdefABCDEF" for char in value)):
            raise ValueError("photoSha256 must be a SHA-256 hex digest")
        return value.lower() if value else None


class SyncResult(BaseModel):
    operationId: str
    status: Literal["applied", "already_applied", "stale"]


class FileResult(BaseModel):
    sha256: str
    sizeBytes: int
    status: Literal["stored", "already_exists"]


class AccountAccessRequest(BaseModel):
    """Create or recover a catalog without an administrator-issued code."""

    model_config = ConfigDict(extra="forbid")

    password: str = Field(min_length=8, max_length=256)
    deviceId: str = Field(min_length=1, max_length=128)
    keyId: str = Field(min_length=1, max_length=128)
    publicKey: str = Field(min_length=32, max_length=4096)
    displayName: str | None = Field(default=None, max_length=200)


class AccountAccessResponse(BaseModel):
    accountId: str
    deviceId: str
    keyId: str
    scopes: list[str]
    created: bool
    serverTime: int


class RestorePage(BaseModel):
    deviceId: str
    items: list[dict[str, object]]
    nextCursor: str | None = None
