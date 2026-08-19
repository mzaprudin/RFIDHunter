package ru.rfidhunter.app.domain

/** Volume+ is represented by a positive key delta, but moves toward the top. */
fun moveCatalogIndex(selectedIndex: Int, volumeDelta: Int, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    val movement = if (volumeDelta > 0) -1 else if (volumeDelta < 0) 1 else 0
    return (selectedIndex + movement).coerceIn(0, itemCount - 1)
}

