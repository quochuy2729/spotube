package org.spotube.spotube.core.ui.image

import coil3.key.Keyer
import coil3.request.Options

/**
 * Coil 3 has no [Keyer] for [ByteArray], so embedded cover art (local tracks) was never put in the
 * memory cache and was decoded again on every scroll. The key is derived from the content.
 */
object ByteArrayKeyer : Keyer<ByteArray> {
    override fun key(data: ByteArray, options: Options): String = byteArrayImageKey(data)
}

internal fun byteArrayImageKey(data: ByteArray): String = "bytes:${data.size}:${data.contentHashCode()}"
