package org.spotube.spotube.modules.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class PlayerGestureTest {
    @Test
    fun swipeNeedsThirtyPercentOfTheWidth() {
        assertNull(resolvePlayerSwipe(-29f, 100f))
        assertEquals(PlayerSwipe.Next, resolvePlayerSwipe(-30f, 100f))
        assertEquals(PlayerSwipe.Previous, resolvePlayerSwipe(45f, 100f))
        assertNull(resolvePlayerSwipe(500f, 0f))
    }

    @Test
    fun seekStaysInsideTheTrack() {
        assertEquals(0.seconds, seekTarget(4.seconds, 100.seconds, forward = false))
        assertEquals(100.seconds, seekTarget(95.seconds, 100.seconds, forward = true))
        assertEquals(24.seconds, seekTarget(14.seconds, 100.seconds, forward = true))
        // Unknown duration: do not clamp the upper end.
        assertEquals(40.seconds, seekTarget(30.seconds, 0.seconds, forward = true))
    }
}
