package ru.rfidhunter.app.domain

/** A command understood by the offline voice flow. */
sealed interface VoiceCommand {
    data class Search(val query: String) : VoiceCommand
    data class Add(val name: String?) : VoiceCommand
}

/**
 * Parses the small Russian command vocabulary used by the application.
 * A bare phrase is intentionally treated as a search query after the prompt
 * "Что найти?". This keeps speech interaction short for users who cannot
 * reliably pronounce a command word.
 */
fun parseVoiceCommand(input: String): VoiceCommand? {
    val value = normalizeText(input)
    if (value.isBlank()) return null

    val words = value.split(' ')
    val first = words.first()
    val rest = value.removePrefix(first).trim().ifBlank { null }
    return when (first) {
        "добавь", "добавить" -> VoiceCommand.Add(rest)
        "ищи", "найди", "ищу", "поиск" -> rest?.let(VoiceCommand::Search)
        else -> VoiceCommand.Search(value)
    }
}

/**
 * Finds the catalogue item most likely intended by a spoken query. Russian
 * inflection is handled by comparing lightweight stems in addition to the
 * normalized full name. The matcher deliberately returns null for a query
 * with no evidence instead of guessing an arbitrary item.
 */
fun findBestItem(query: String, candidates: List<Item>): Item? {
    val normalizedQuery = normalizeText(query)
    if (normalizedQuery.isBlank()) return null
    val queryTokens = normalizedTokens(normalizedQuery)
    return candidates
        .asSequence()
        .map { item -> item to matchScore(normalizedQuery, queryTokens, normalizeText(item.name)) }
        .filter { (_, score) -> score > 0 }
        .maxByOrNull { (_, score) -> score }
        ?.first
}

private fun matchScore(query: String, queryTokens: List<String>, name: String): Int {
    val nameTokens = normalizedTokens(name)
    var score = 0
    if (name == query) score += 100
    if (name.contains(query) || query.contains(name)) score += 60
    for (queryToken in queryTokens) {
        val queryStem = russianStem(queryToken)
        for (nameToken in nameTokens) {
            val nameStem = russianStem(nameToken)
            if (queryToken == nameToken) score += 30
            else if (queryStem == nameStem || queryStem.startsWith(nameStem) || nameStem.startsWith(queryStem)) score += 22
            else if (editDistanceAtMost(queryStem, nameStem, 1)) score += 10
        }
    }
    return score
}

private fun russianStem(token: String): String {
    val suffixes = listOf(
        "иями", "ами", "ями", "ого", "ему", "ому", "ов", "ев", "ей", "ах", "ях",
        "ам", "ям", "ом", "ем", "ой", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие",
        "ую", "юю", "ую", "ю", "у", "а", "я", "ы", "и", "е", "о",
    )
    val suffix = suffixes.firstOrNull { token.length > it.length + 2 && token.endsWith(it) }
    return if (suffix == null) token else token.removeSuffix(suffix)
}

private fun editDistanceAtMost(left: String, right: String, maxDistance: Int): Boolean {
    if (kotlin.math.abs(left.length - right.length) > maxDistance) return false
    var previous = IntArray(right.length + 1) { it }
    for (i in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = i + 1
        for (j in right.indices) {
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + if (left[i] == right[j]) 0 else 1,
            )
        }
        if (current.minOrNull()!! > maxDistance) return false
        previous = current
    }
    return previous[right.length] <= maxDistance
}
