package org.spotube.spotube.core.ui

import coil3.request.ImageRequest
import coil3.request.allowHardware

internal actual fun ImageRequest.Builder.softwareBitmapsOnly(): ImageRequest.Builder = allowHardware(false)
