/*
 * Copyright (c) 2026 Auxio Project
 * LrcParser.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
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

        return contents
            .lineSequence()
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
                        val millis =
                            when (fraction.length) {
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
