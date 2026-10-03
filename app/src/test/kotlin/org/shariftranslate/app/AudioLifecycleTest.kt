package org.shariftranslate.app

import kotlinx.coroutines.*
import org.shariftranslate.api.tts.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class AudioLifecycleTest {
    @Test fun replacingPlaybackCannotCloseTheNewDecoderAndClosePreservesAppScope() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = ConcurrentHashMap<Int, CountDownLatch>().apply { put(1, CountDownLatch(1)); put(2, CountDownLatch(1)) }
        val released = ConcurrentHashMap<Int, CountDownLatch>().apply { put(1, CountDownLatch(1)); put(2, CountDownLatch(1)) }
        val logger = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.ERROR).getLogger("AudioTest")
        val audio = JLayerAudioPlayer(scope, logger) { bytes ->
            val id = bytes[0].toInt()
            object : AudioPlayback {
                override fun play() { started[id]!!.countDown(); check(released[id]!!.await(4, TimeUnit.SECONDS)); if (id == 1) Thread.sleep(80) }
                override fun close() { released[id]!!.countDown() }
            }
        }
        try {
            audio.play(TTSAudio.Bytes(byteArrayOf(1), AudioFormat.MP3))
            assertTrue(started[1]!!.await(2, TimeUnit.SECONDS))
            audio.play(TTSAudio.Bytes(byteArrayOf(2), AudioFormat.MP3))
            assertTrue(started[2]!!.await(2, TimeUnit.SECONDS))
            delay(150)
            assertTrue(audio.isPlaying.value)
            assertEquals(1L, released[2]!!.count)
            audio.close()
            assertFalse(audio.isPlaying.value)
            assertTrue(scope.isActive)
        } finally { audio.close(); scope.cancel() }
    }
}
