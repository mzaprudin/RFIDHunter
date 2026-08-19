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

enum class CatalogMatchKind {
    /** The query is an exact name or a clear inflection/partial-name match. */
    DIRECT,
    /** The query is close in sound/spelling, but should be confirmed. */
    FUZZY,
}

data class CatalogMatch(
    val item: Item,
    val score: Int,
    val kind: CatalogMatchKind,
)

/**
 * Finds the catalogue item most likely intended by a spoken query. Russian
 * inflection is handled by comparing lightweight stems in addition to the
 * normalized full name. The matcher deliberately returns null for a query
 * with no evidence instead of guessing an arbitrary item.
 */
fun findBestItem(query: String, candidates: List<Item>): Item? {
    return findBestItemMatch(query, candidates)?.item
}

/**
 * Matches a recognition result without forcing an unrelated catalogue item.
 * In addition to edit distance, a consonant skeleton handles common ASR
 * fragments such as "а чки"/"ачике" for "очки". Such matches are marked
 * fuzzy so the UI can ask the user before starting RFID search.
 */
fun findBestItemMatch(query: String, candidates: List<Item>): CatalogMatch? {
    val normalizedQuery = normalizeText(query)
    if (normalizedQuery.isBlank()) return null
    val queryTokens = normalizedTokens(normalizedQuery)
    val ranked = candidates
        .asSequence()
        .map { item ->
            val name = normalizeText(item.name)
            item to matchScore(normalizedQuery, queryTokens, name)
        }
        .filter { (_, score) -> score >= MIN_MATCH_SCORE }
        .sortedByDescending { (_, score) -> score }
        .toList()
    val best = ranked.firstOrNull() ?: return null
    val item = best.first
    val score = best.second
    val kind = matchKind(normalizedQuery, queryTokens, normalizeText(item.name), score)
    // If two catalogue entries are equally plausible, do not pick one just
    // because it happened to be first in the database. Ask the user to repeat
    // the name instead of suggesting an arbitrary thing.
    if (kind == CatalogMatchKind.FUZZY && ranked.getOrNull(1)?.let { score - it.second < FUZZY_MARGIN } == true) return null
    return CatalogMatch(item, score, kind)
}

private const val MIN_MATCH_SCORE = 34
private const val FUZZY_MARGIN = 10

private fun matchScore(query: String, queryTokens: List<String>, name: String): Int {
    val nameTokens = normalizedTokens(name)
    var score = 0
    if (name == query) score += 100
    if (name.contains(query) || query.contains(name)) score += 60
    val meaningfulQueryTokens = queryTokens.filterNot(::isSpeechFiller)
    val compactQuery = meaningfulQueryTokens.joinToString("")
    if (nameTokens.size == 1 && compactQuery.isNotBlank()) {
        val compactDistance = editDistance(compactQuery, nameTokens.first())
        if (compactDistance <= 2) score += 28 - compactDistance * 6
    }
    for (queryToken in meaningfulQueryTokens) {
        val queryStem = russianStem(queryToken)
        for (nameToken in nameTokens) {
            val nameStem = russianStem(nameToken)
            if (queryToken == nameToken) score += 30
            else if (queryStem == nameStem || queryStem.startsWith(nameStem) || nameStem.startsWith(queryStem)) score += 35
            else {
                val querySkeleton = consonantSkeleton(queryToken)
                val nameSkeleton = consonantSkeleton(nameToken)
                if (querySkeleton.length >= 2 && querySkeleton == nameSkeleton) score += 38
                else {
                    val distance = editDistanceAtMostValue(queryStem, nameStem, 2)
                    if (distance <= 2) score += 14 - distance * 3
                }
            }
        }
    }
    return score
}

private fun matchKind(query: String, queryTokens: List<String>, name: String, score: Int): CatalogMatchKind {
    if (name == query || name.contains(query) || query.contains(name)) return CatalogMatchKind.DIRECT
    val meaningful = queryTokens.filterNot(::isSpeechFiller)
    val directEvidence = meaningful.sumOf { queryToken ->
        val queryStem = russianStem(queryToken)
        normalizedTokens(name).count { nameToken ->
            val nameStem = russianStem(nameToken)
            queryToken == nameToken || queryStem == nameStem ||
                queryStem.startsWith(nameStem) || nameStem.startsWith(queryStem)
        }
    }
    return if (directEvidence > 0 || score >= 70) CatalogMatchKind.DIRECT else CatalogMatchKind.FUZZY
}

private fun isSpeechFiller(token: String): Boolean = token in setOf("а", "э", "ну", "это", "вот")

private fun consonantSkeleton(token: String): String = token.filterNot { it in "аеёиоуыэюя" }

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
    return editDistanceAtMostValue(left, right, maxDistance) <= maxDistance
}

private fun editDistanceAtMostValue(left: String, right: String, maxDistance: Int): Int {
    if (kotlin.math.abs(left.length - right.length) > maxDistance) return maxDistance + 1
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
        if (current.minOrNull()!! > maxDistance) return maxDistance + 1
        previous = current
    }
    return previous[right.length]
}

private fun editDistance(left: String, right: String): Int = editDistanceAtMostValue(left, right, maxOf(left.length, right.length))
