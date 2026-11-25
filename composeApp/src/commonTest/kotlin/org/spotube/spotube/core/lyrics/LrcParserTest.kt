/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LrcParserTest {
    @Test
    fun parsesStrictLrclibFormat() {
        val lines = LrcParser.parse("[00:12.34] Hello\n[01:02.50] World")
        assertEquals(listOf(12_340L, 62_500L), lines.map { it.time })
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
    }

    @Test
    fun toleratesLooseStamps() {
        val lines = LrcParser.parse("[0:05]a\n[00:06.5]b\n[00:07.123]c\n[00:08:25]d")
        assertEquals(listOf(5_000L, 6_500L, 7_123L, 8_250L), lines.map { it.time })
    }

    @Test
    fun multipleStampsShareOneLineAndAreSorted() {
        val lines = LrcParser.parse("[01:00.00][00:10.00]chorus\n[00:30.00]verse")
        assertEquals(listOf(10_000L, 30_000L, 60_000L), lines.map { it.time })
        assertEquals(listOf("chorus", "verse", "chorus"), lines.map { it.text })
    }

    @Test
    fun ignoresTagsAndUnstampedLinesAndBom() {
        val lines = LrcParser.parse("\uFEFF[ar:Someone]\n[ti:Song]\nplain\r\n[00:01.00]x\r\n")
        assertEquals(1, lines.size)
        assertEquals("x", lines.single().text)
    }

    @Test
    fun keepsEmptyInstrumentalLines() {
        val lines = LrcParser.parse("[00:01.00]\n[00:02.00]go")
        assertEquals(listOf("", "go"), lines.map { it.text })
    }

    @Test
    fun plainTextDropsStampsAndTags() {
        assertEquals("one\ntwo", LrcParser.toPlainText("[ar:X]\n[00:01.00]one\n[00:02.00]two"))
        assertTrue(!LrcParser.hasTimestamps("just words"))
    }
}
