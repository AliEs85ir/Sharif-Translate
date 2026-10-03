package org.shariftranslate.app

import org.shariftranslate.api.core.Logger
import org.shariftranslate.api.tts.AudioFormat
import org.shariftranslate.api.tts.TTSAudio
import org.shariftranslate.core.audio.AudioPlayer
import javazoom.jl.player.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream

internal interface AudioPlayback {
    fun play()
    fun close()
}

/** Each playback owns its decoder; the caller retains ownership of the application scope. */
class JLayerAudioPlayer internal constructor(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val createPlayer: (ByteArray) -> AudioPlayback = { data ->
        val player = Player(ByteArrayInputStream(data))
        object : AudioPlayback {
            override fun play() { player.play() }
            override fun close() = player.close()
        }
    }
) : AudioPlayer {
    private val mutablePlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = mutablePlaying
    private val lock = Any()
    private var generation = 0L
    private var playbackJob: Job? = null
    private var currentPlayer: AudioPlayback? = null

    override fun play(audio: TTSAudio.Bytes) {
        if (audio.format != AudioFormat.MP3) {
            logger.warn("JLayerAudioPlayer only supports MP3; received ${audio.format}")
            return
        }
        synchronized(lock) {
            stop()
            val request = generation
            playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                var ownedPlayer: AudioPlayback? = null
                try {
                    ensureActive()
                    val player = createPlayer(audio.data)
                    ownedPlayer = player
                    synchronized(lock) {
                        if (request != generation) return@launch
                        currentPlayer = player
                        mutablePlaying.value = true
                    }
                    ensureActive()
                    player.play()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error during audio playback", e)
                } finally {
                    closePlayer(ownedPlayer)
                    synchronized(lock) {
                        if (request == generation) {
                            currentPlayer = null
                            mutablePlaying.value = false
                        }
                    }
                }
            }.also { it.start() }
        }
    }

    override fun stop() = synchronized(lock) {
        generation++
        playbackJob?.cancel()
        playbackJob = null
        closePlayer(currentPlayer)
        currentPlayer = null
        mutablePlaying.value = false
    }

    override fun close() = stop()

    private fun closePlayer(player: AudioPlayback?) {
        runCatching { player?.close() }.onFailure { logger.error("Error closing audio player", it) }
    }
}
