package ru.rfidhunter.app.domain

import java.util.Locale

data class Item(
    val id: Long,
    val name: String,
    val epc: String,
    val photoPath: String? = null,
)

data class TagRead(
    val epc: String,
    val rssi: Int,
)

/** A Bluetooth reader discovered during the IH25 scan. */
data class RfidDevice(
    val name: String,
    val macAddress: String,
    val rssi: Int,
    val likelyHoneywell: Boolean = false,
)

enum class ReaderScanState {
    IDLE,
    SCANNING,
    COMPLETE,
    ERROR,
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class TriggerEvent {
    PRESSED,
    RELEASED,
}

data class InventoryConfig(
    val power: Int,
    val durationMs: Long,
)

enum class FakeScenario {
    NONE,
    UNKNOWN,
    KNOWN,
    MULTIPLE,
    SEARCH_FOUND,
}

fun normalizeText(value: String): String = value
    .lowercase(Locale("ru"))
    .replace('ё', 'е')
    .replace(Regex("[^а-яa-z0-9\\s]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

fun normalizedTokens(value: String): List<String> = normalizeText(value)
    .split(' ')
    .filter { it.isNotBlank() }
