package com.tanasi.streamflix.util

import java.util.regex.PatternSyntaxException

/** The JVM supports (?U), but Android's ICU regex engine rejects this flag.
 * Android uses Unicode character classes by default. */
internal fun unicodeRegex(pattern: String, options: Set<RegexOption> = emptySet()): Regex =
    try {
        Regex("(?U)$pattern", options)
    } catch (_: PatternSyntaxException) {
        Regex(pattern, options)
    }

internal fun unicodeRegex(pattern: String, option: RegexOption): Regex = unicodeRegex(pattern, setOf(option))
