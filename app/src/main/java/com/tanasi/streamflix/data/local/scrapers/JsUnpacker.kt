package com.tanasi.streamflix.data.local.scrapers

import androidx.annotation.Keep
import java.util.Locale

/** Desempaqueta literales Dean Edwards sin evaluar ni ejecutar JavaScript. */
@Keep
class JsUnpacker {
    /** `null` delega casos poco habituales al desempaquetador anterior. */
    fun unpack(html: String, limit: Int): String? {
        if (limit < 0) return null
        if (html.isEmpty() || !html.lowercase(Locale.ROOT).contains("function(p,a,c,k,e")) return ""
        val decoded = mutableListOf<String>()
        for (match in HEADER.findAll(html).take(limit)) {
            val marker = html.indexOf("('", match.range.last + 1)
            if (marker < 0) continue
            val start = marker + 2
            val args = ARGUMENTS.find(html, start) ?: continue
            if (args.range.first - start > 400_000) continue
            var payload = unescape(html.substring(start, args.range.first))
            val table = args.groupValues[3].split('|')
            // re.sub de Python interpreta backreferences en sustituciones; si
            // aparecen, conservar ese comportamiento mediante el fallback.
            if (table.any { '\\' in it }) return null
            for (index in table.indices.reversed()) {
                val word = table[index]
                if (word.isNotEmpty()) payload = Regex("\\b$index\\b").replace(payload) { word }
            }
            decoded.add(payload)
        }
        return decoded.joinToString("\n")
    }

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c == '\\' && index + 1 < text.length) {
                val next = text[index + 1]
                out.append(when (next) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> next })
                index += 2
            } else { out.append(c); index++ }
        }
        return out.toString()
    }

    private companion object {
        val HEADER = Regex("function\\s*\\(\\s*p\\s*,\\s*a\\s*,\\s*c\\s*,\\s*k\\s*,\\s*e\\s*,\\s*d\\s*\\)", RegexOption.IGNORE_CASE)
        val ARGUMENTS = Regex("',(\\d{1,3}),(\\d{1,4}),'([^']*)'\\.split\\('\\|'\\)", RegexOption.IGNORE_CASE)
    }
}
