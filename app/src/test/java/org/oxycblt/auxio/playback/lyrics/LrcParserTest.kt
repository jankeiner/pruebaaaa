/*
 * Copyright (c) 2026 Auxio Project
 * LrcParserTest.kt is part of Auxio.
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

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesAndSortsTimestampedLines() {
        val lines = LrcParser.parse("[00:02.50]Second\n[00:01.2]First")
        assertEquals(listOf(LyricLine(1200L, "First"), LyricLine(2500L, "Second")), lines)
    }

    @Test
    fun expandsMultipleTimestampsOnOneLine() {
        assertEquals(
            listOf(LyricLine(1000L, "Repeat"), LyricLine(2000L, "Repeat")),
            LrcParser.parse("[00:01.00][00:02.00]Repeat"),
        )
    }

    @Test
    fun ignoresMetadataAndInvalidSeconds() {
        assertEquals(
            listOf(LyricLine(3000L, "Valid")),
            LrcParser.parse("[ar:Artist]\n[00:61.00]Invalid\n[00:03]Valid"),
        )
    }
}
