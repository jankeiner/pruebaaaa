/*
 * Copyright (c) 2026 Jan's Project
 * Licensed under the GNU General Public License v3.0 or later.
 */
package org.oxycblt.auxio.playback.lyrics

/**
 * A timestamped line from an LRC lyric file.
 *
 * Timestamps are stored in milliseconds so they can be compared directly with the player's
 * position. Multiple timestamps on one LRC line produce multiple timed entries.
 */
data class LyricLine(val timeMs: Long, val text: String)

/** Parses standard LRC timestamp lines without depending on Android APIs. */
object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(contents: String): List<LyricLine> {
        if (contents.isBlank()) return emptyList()

        return contents.lineSequence()
            .flatMap { line ->
                val matches = timestamp.findAll(line).toList()
                if (matches.isEmpty()) {
                    emptySequence()
                } else {
                    val text = line.substring(matches.last().range.last + 1).trim()
                    matches.asSequence().mapNotNull { match ->
                        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                        val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                        val fraction = match.groupValues[3]
                        if (seconds >= 60) return@mapNotNull null
                        val millis = when (fraction.length) {
                            1 -> fraction.toLong() * 100
                            2 -> fraction.toLong() * 10
                            3 -> fraction.toLong()
                            else -> 0L
                        }
                        LyricLine((minutes * 60 + seconds) * 1000 + millis, text)
                    }
                }
            }
            .sortedBy(LyricLine::timeMs)
            .toList()
    }
}
