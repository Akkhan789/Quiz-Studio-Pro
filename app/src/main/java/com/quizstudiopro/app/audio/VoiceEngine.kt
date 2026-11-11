package com.quizstudiopro.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.quizstudiopro.app.logging.QuizLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object VoiceEngine {
    private const val TAG = "VoiceEngine"
    const val SAMPLE_RATE = 48000

    @Volatile
    private var cachedVoices: List<EdgeTtsClient.VoiceItem> = emptyList()

    @Volatile
    private var isLastFetchLive: Boolean = false

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    data class InventoryResult(
        val voices: List<EdgeTtsClient.VoiceItem>,
        val isLive: Boolean,
        val error: String? = null
    )

    fun clearVoiceCache() {
        QuizLogger.info(TAG, "Clearing in-memory voice inventory cache")
        cachedVoices = emptyList()
        isLastFetchLive = false
    }

    suspend fun fetchVoiceInventoryResult(
        context: Context,
        forceRefresh: Boolean = false
    ): InventoryResult = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            clearVoiceCache()
        } else if (cachedVoices.isNotEmpty()) {
            return@withContext InventoryResult(cachedVoices, isLive = isLastFetchLive)
        }

        val cacheFile = File(context.cacheDir, "edge_voices_v2.json")
        val result = EdgeTtsClient.fetchVoiceList(cacheFile, forceRefresh = forceRefresh)
        if (result.voices.isNotEmpty()) {
            cachedVoices = result.voices
            isLastFetchLive = result.isLive
            InventoryResult(result.voices, isLive = result.isLive, error = result.error)
        } else {
            // Live connection failed and no cache
            QuizLogger.warn(TAG, "Voice list fetch returned 0 voices. Live: ${result.isLive}, Error: ${result.error}")
            InventoryResult(emptyList(), isLive = false, error = result.error ?: "Unable to retrieve voices")
        }
    }

    suspend fun fetchVoiceInventory(
        context: Context,
        forceRefresh: Boolean = false
    ): List<EdgeTtsClient.VoiceItem> = withContext(Dispatchers.IO) {
        fetchVoiceInventoryResult(context, forceRefresh).voices
    }

    suspend fun synthesizeSpeech(
        context: Context,
        text: String,
        voiceShortName: String,
        speedPercent: Int
    ): AudioDecoder.DecodedAudio = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "edge_tts_audio_cache").apply { mkdirs() }
        val cacheKey = md5Hash("$voiceShortName|$speedPercent|$text")
        val mp3CacheFile = File(cacheDir, "$cacheKey.mp3")

        val mp3Bytes: ByteArray = if (mp3CacheFile.exists() && mp3CacheFile.length() > 100) {
            mp3CacheFile.readBytes()
        } else {
            val liveBytes = EdgeTtsClient.synthesizeToMp3(text, voiceShortName, speedPercent)
            if (liveBytes.isEmpty()) {
                throw IllegalStateException("Edge TTS returned 0 audio bytes for voice '$voiceShortName'")
            }
            try {
                FileOutputStream(mp3CacheFile).use { it.write(liveBytes) }
            } catch (e: Exception) {
                QuizLogger.warn(TAG, "Failed to write MP3 cache: ${e.message}")
            }
            liveBytes
        }
        val decoded = AudioDecoder.decodeMp3ToPcm(mp3Bytes, cacheDir, targetSampleRate = SAMPLE_RATE)
        QuizLogger.info(TAG, "Audio decoding completed: ${decoded.pcm.size} bytes PCM @ ${SAMPLE_RATE}Hz")
        decoded
    }

    suspend fun synthesizeToMp3Bytes(
        context: Context,
        text: String,
        voiceShortName: String,
        speedPercent: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "edge_tts_audio_cache").apply { mkdirs() }
        val cacheKey = md5Hash("$voiceShortName|$speedPercent|$text")
        val mp3CacheFile = File(cacheDir, "$cacheKey.mp3")

        if (mp3CacheFile.exists() && mp3CacheFile.length() > 100) {
            mp3CacheFile.readBytes()
        } else {
            val liveBytes = EdgeTtsClient.synthesizeToMp3(text, voiceShortName, speedPercent)
            try {
                FileOutputStream(mp3CacheFile).use { it.write(liveBytes) }
            } catch (_: Exception) {}
            liveBytes
        }
    }

    suspend fun playPreviewAudio(
        context: Context,
        text: String,
        voiceShortName: String,
        speedPercent: Int,
        onPlaybackComplete: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        stopPlayback()
        QuizLogger.info(TAG, "Preview synthesis requested for voice '$voiceShortName'")
        val decoded = synthesizeSpeech(context, text, voiceShortName, speedPercent)
        withContext(Dispatchers.Main) {
            try {
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(decoded.pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                audioTrack.write(decoded.pcm, 0, decoded.pcm.size)
                val sampleCount = decoded.pcm.size / 2
                if (sampleCount > 0) {
                    audioTrack.notificationMarkerPosition = sampleCount
                    audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(track: AudioTrack?) {
                            onPlaybackComplete?.invoke()
                        }
                        override fun onPeriodicNotification(track: AudioTrack?) {}
                    })
                }
                audioTrack.play()
                activeAudioTrack = audioTrack
                QuizLogger.success(TAG, "Playing preview audio track for '$voiceShortName'")
            } catch (e: Exception) {
                QuizLogger.error(TAG, "AudioTrack preview playback failed: ${e.message}")
                throw e
            }
        }
    }

    fun stopPlayback() {
        try {
            activeAudioTrack?.stop()
            activeAudioTrack?.release()
            activeAudioTrack = null
        } catch (_: Exception) {}
    }

    private fun md5Hash(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }
}
