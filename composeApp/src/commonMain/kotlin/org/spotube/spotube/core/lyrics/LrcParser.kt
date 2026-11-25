/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.lyrics

import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsLine

/**
 * Parser for LRC lyrics. Accepts `[m:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]` and `[mm:ss:xx]` stamps and
 * several stamps in front of one line (`[00:10.00][01:20.00]chorus`). Tag lines such as
 * `[ar:Artist]` and lines without a leading stamp are ignored.
 */
object LrcParser {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricsLine> {
        val result = mutableListOf<LyricsLine>()
        for (raw in lrc.removePrefix("\uFEFF").lines()) {
            val line = raw.trim()
            val times = mutableListOf<Long>()
            var cursor = 0
            while (true) {
                val match = stamp.matchAt(line, cursor) ?: break
                val (min, sec, frac) = match.destructured
                // The fraction is a decimal part of a second: "5" = 500 ms, "05" = 50 ms, "050" = 50 ms.
                val ms = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').toLong()
                times += min.toLong() * 60_000 + sec.toLong() * 1_000 + ms
                cursor = match.range.last + 1
            }
            if (times.isEmpty()) continue
            val text = line.substring(cursor).trim()
            times.forEach { result += LyricsLine(time = it, text = text) }
        }
        // Stable sort: equal stamps keep file order.
        return result.sortedBy { it.time }
    }

    /** True when [lrc] holds at least one stamped line. */
    fun hasTimestamps(lrc: String): Boolean = parse(lrc).isNotEmpty()

    /** Plain text of an LRC file (stamps and tag lines dropped); non-stamped text is kept as is. */
    fun toPlainText(lrc: String): String {
        val tag = Regex("""^\[[A-Za-z#]+:.*]$""")
        return lrc.removePrefix("\uFEFF").lines().map { it.trim() }
            .filter { !tag.matches(it) }
            .joinToString("\n") { line ->
                var rest = line
                while (true) {
                    val m = stamp.matchAt(rest, 0) ?: break
                    rest = rest.substring(m.range.last + 1)
                }
                rest.trim()
            }.trim()
    }
}
