package org.spotube.spotube.modules.shell

import org.spotube.spotube.modules.shell.player_queue.queueItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QueueItemKeyTest {
    private fun keys(urls: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return urls.map { queueItemKey(it, seen.merge(it, 1, Int::plus)!! - 1) }
    }

    @Test
    fun removingOneItemKeepsKeysOfOthers() {
        val before = keys(listOf("a", "b", "c", "d"))
        val after = keys(listOf("a", "c", "d"))
        assertEquals(listOf(before[0], before[2], before[3]), after)
    }

    @Test
    fun duplicateUrlsGetDistinctKeys() {
        val k = keys(listOf("a", "a"))
        assertNotEquals(k[0], k[1])
    }
}
