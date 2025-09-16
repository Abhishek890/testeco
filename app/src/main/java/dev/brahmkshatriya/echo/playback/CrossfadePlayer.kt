package dev.brahmkshatriya.echo.playback

import android.animation.Animator
import android.animation.ValueAnimator
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class CrossfadePlayer(
    private val scope: CoroutineScope,
    private val createPlayer: () -> ExoPlayer,
) : MutableForwardingPlayer(createPlayer()) {

    private val player1: ExoPlayer = forwardTo as ExoPlayer
    private val player2: ExoPlayer = createPlayer()

    private var activePlayer: ExoPlayer = player1
    private var inactivePlayer: ExoPlayer = player2

    private var crossfadeJob: Job? = null
    private var isCrossfading = false

    var crossfadeDuration: Int = 0 // in seconds

    private var isShuffled = false
    private var originalQueue = listOf<MediaItem>()

    init {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    triggerCrossfadeCheck()
                } else {
                    crossfadeJob?.cancel()
                }
            }
        }
        player1.addListener(listener)
        player2.addListener(listener)
    }

    private fun triggerCrossfadeCheck() {
        if (crossfadeDuration <= 0 || isCrossfading) return
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            while (activePlayer.isPlaying) {
                val remaining = activePlayer.duration - activePlayer.currentPosition
                if (activePlayer.duration > 0 && remaining < crossfadeDuration * 1000) {
                    if (activePlayer.hasNextMediaItem()) {
                        startCrossfade()
                    }
                    break // Exit loop once crossfade is initiated
                }
                delay(250) // Check every 250ms
            }
        }
    }

    private fun startCrossfade() {
        if (isCrossfading) return
        isCrossfading = true

        val nextIndex = activePlayer.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) {
            isCrossfading = false
            return
        }

        // Prepare next player
        val mediaItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
        inactivePlayer.setMediaItems(mediaItems, nextIndex, 0)
        inactivePlayer.prepare()
        inactivePlayer.playWhenReady = true
        inactivePlayer.volume = 0f

        // Animator
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = (crossfadeDuration * 1000).toLong()
            addUpdateListener {
                val newVolume = it.animatedValue as Float
                activePlayer.volume = newVolume
                inactivePlayer.volume = 1 - newVolume
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    activePlayer.stop()

                    // Swap players
                    forwardTo = inactivePlayer
                    val temp = activePlayer
                    activePlayer = inactivePlayer
                    inactivePlayer = temp

                    isCrossfading = false
                    // The new active player is now playing, its onIsPlayingChanged will trigger the next check
                }

                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {
                    // Reset if cancelled
                    inactivePlayer.stop()
                    inactivePlayer.volume = 1f
                    isCrossfading = false
                }

                override fun onAnimationRepeat(animation: Animator) {}
            })
        }
        animator.start()
    }

    override fun release() {
        super.release()
        player1.release()
        player2.release()
        crossfadeJob?.cancel()
    }

    fun onMediaItemChanged(old: MediaItem, new: MediaItem) {
        originalQueue = originalQueue.toMutableList().apply {
            val index = indexOfFirst { it.mediaId == old.mediaId }.takeIf { it != -1 } ?: return
            set(index, new)
        }
    }

    private fun syncPlayers(block: (Player) -> Unit) {
        block(player1)
        block(player2)
    }

    override fun setShuffleModeEnabled(enabled: Boolean) {
        if (enabled) {
            originalQueue = (0 until super.getMediaItemCount()).map { super.getMediaItemAt(it) }
        }
        isShuffled = enabled
        val newQueue = if (enabled) originalQueue.shuffled() else originalQueue
        setMediaItems(newQueue.toMutableList(), super.getCurrentMediaItemIndex(), super.getCurrentPosition())
        syncPlayers { it.shuffleModeEnabled = enabled }
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        originalQueue = originalQueue + mediaItem
        syncPlayers { it.addMediaItem(mediaItem) }
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        originalQueue = originalQueue + mediaItems
        syncPlayers { it.addMediaItems(mediaItems) }
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        originalQueue = originalQueue.toMutableList().apply { add(index, mediaItem) }
        syncPlayers { it.addMediaItem(index, mediaItem) }
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        originalQueue = originalQueue.toMutableList().apply { addAll(index, mediaItems) }
        syncPlayers { it.addMediaItems(index, mediaItems) }
    }

    private fun getItemAt(index: Int) = super.getMediaItemAt(index).let {
        originalQueue.first { item -> item.mediaId == it.mediaId }
    }

    override fun removeMediaItem(index: Int) {
        originalQueue = originalQueue - getItemAt(index)
        syncPlayers { it.removeMediaItem(index) }
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        originalQueue = originalQueue - (fromIndex until toIndex).map { getItemAt(it) }.toSet()
        syncPlayers { it.removeMediaItems(fromIndex, toIndex) }
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        originalQueue = originalQueue.toMutableList().apply { set(index, mediaItem) }
        syncPlayers { it.replaceMediaItem(index, mediaItem) }
    }

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) {
        originalQueue = originalQueue.toMutableList().apply {
            for (i in fromIndex until toIndex) {
                set(i, mediaItems[i - fromIndex])
            }
        }
        syncPlayers { it.replaceMediaItems(fromIndex, toIndex, mediaItems) }
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        originalQueue = listOf(mediaItem)
        syncPlayers { it.setMediaItem(mediaItem) }
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        originalQueue = listOf(mediaItem)
        syncPlayers { it.setMediaItem(mediaItem, resetPosition) }
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        originalQueue = listOf(mediaItem)
        syncPlayers { it.setMediaItem(mediaItem, startPositionMs) }
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        originalQueue = mediaItems
        syncPlayers { it.setMediaItems(mediaItems) }
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        originalQueue = mediaItems
        syncPlayers { it.setMediaItems(mediaItems, resetPosition) }
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        originalQueue = mediaItems
        syncPlayers { it.setMediaItems(mediaItems, startIndex, startPositionMs) }
    }

    override fun clearMediaItems() {
        originalQueue = emptyList()
        syncPlayers { it.clearMediaItems() }
    }
}
