package dev.brahmkshatriya.echo.playback

import androidx.media3.common.Player

open class MutableForwardingPlayer(
    var forwardTo: Player
) : Player by forwardTo
