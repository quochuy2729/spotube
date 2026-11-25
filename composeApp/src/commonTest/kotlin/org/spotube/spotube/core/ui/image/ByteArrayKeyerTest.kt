package org.spotube.spotube.core.ui.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ByteArrayKeyerTest {
    @Test
    fun sameContentSameKey() {
        assertEquals(byteArrayImageKey(byteArrayOf(1, 2, 3)), byteArrayImageKey(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun differentContentDifferentKey() {
        assertNotEquals(byteArrayImageKey(byteArrayOf(1, 2, 3)), byteArrayImageKey(byteArrayOf(1, 2, 4)))
    }
}
