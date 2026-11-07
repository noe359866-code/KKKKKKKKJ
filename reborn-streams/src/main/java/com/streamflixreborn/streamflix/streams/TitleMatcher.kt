package com.streamflixreborn.streamflix.streams

import java.text.Normalizer
import java.util.Locale

/** Small, deterministic title/year matcher used only to locate a stream target. */
object TitleMatcher {
    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun score(candidateTitle: String, candidateYear: Int?, request: StreamRequest): Int {
        val wanted = normalize(request.title)
        val candidate = normalize(candidateTitle)
        if (wanted.isBlank() || candidate.isBlank()) return Int.MIN_VALUE
        var score = when {
            candidate == wanted -> 100
            candidate.startsWith("$wanted ") || candidate.endsWith(" $wanted") -> 80
            candidate.contains(wanted) || wanted.contains(candidate) -> 65
            else -> {
                val wantedTokens = wanted.split(' ').filter { it.isNotBlank() }.toSet()
                val candidateTokens = candidate.split(' ').filter { it.isNotBlank() }.toSet()
                if (wantedTokens.isEmpty()) 0
                else (wantedTokens.intersect(candidateTokens).size * 40) / wantedTokens.size
            }
        }
        val year = request.year
        if (year != null && candidateYear != null) {
            score += if (year == candidateYear) 25 else -35
        }
        return score
    }

    fun best(candidates: List<Pair<String, Int?>>, request: StreamRequest): Int? {
        var bestIndex: Int? = null
        var bestScore = 45
        candidates.forEachIndexed { index, (title, year) ->
            val score = score(title, year, request)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex
    }
}
