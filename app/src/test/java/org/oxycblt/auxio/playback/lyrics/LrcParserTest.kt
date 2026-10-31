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
