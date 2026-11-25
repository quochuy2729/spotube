/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.lyrics

import org.spotube.spotube.core.audioplayer.QueueEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalLyricsSourceTest {
    @Test
    fun readsLrcNextToTrack() {
        val dir = Files.createTempDirectory("lrc").toFile()
        val audio = java.io.File(dir, "My Song.v2.mp3").apply { writeText("x") }
        java.io.File(dir, "My Song.v2.lrc").writeText("[00:01.00]hi\n[00:02.00]there")
        val lyrics = readSidecarLyrics(audio.absolutePath)!!
        assertEquals(listOf("hi", "there"), lyrics.syncedLyrics!!.map { it.text })
        assertEquals("hi\nthere", lyrics.plainLyrics)
    }

    @Test
    fun plainLrcWithoutStampsGivesOnlyPlainText() {
        val dir = Files.createTempDirectory("lrc").toFile()
        val audio = java.io.File(dir, "a.mp3")
        java.io.File(dir, "a.lrc").writeText("just words")
        val lyrics = readSidecarLyrics(audio.absolutePath)!!
        assertNull(lyrics.syncedLyrics)
        assertEquals("just words", lyrics.plainLyrics)
    }

    @Test
    fun missingFileAndContentUrisGiveNull() {
        val dir = Files.createTempDirectory("lrc").toFile()
        assertNull(readSidecarLyrics(java.io.File(dir, "none.mp3").absolutePath))
        assertNull(readSidecarLyrics("content://media/external/audio/media/42"))
    }

    @Test
    fun oversizedOrDirectorySidecarIsIgnored() {
        val dir = Files.createTempDirectory("lrc").toFile()
        java.io.File(dir, "big.lrc").writeBytes(ByteArray(2 shl 20) { 'a'.code.toByte() })
        assertNull(readSidecarLyrics(java.io.File(dir, "big.mp3").absolutePath))
        java.io.File(dir, "folder.lrc").mkdir()
        assertNull(readSidecarLyrics(java.io.File(dir, "folder.mp3").absolutePath))
    }

    @Test
    fun bomAndCrlfAreHandled() {
        val dir = Files.createTempDirectory("lrc").toFile()
        java.io.File(dir, "b.lrc").writeText("﻿[ar:X]\r\n[00:01.50]một\r\n[00:00.50]hai\r\n")
        val lyrics = readSidecarLyrics(java.io.File(dir, "b.mp3").absolutePath)!!
        assertEquals(listOf(500L to "hai", 1_500L to "một"), lyrics.syncedLyrics!!.map { it.time to it.text })
    }

    @Test
    fun searchTrackCarriesTags() {
        val t = QueueEntry.LocalTrack("Song", listOf("A", " "), 123_000, "Alb", null, "/m/s.mp3").toLyricsSearchTrack()
        assertEquals("Song", t.title)
        assertEquals(listOf("A"), t.artists.map { it.name })
        assertEquals("Alb", t.album?.title)
        assertEquals(123_000L, t.durationMs)
    }
}
