package com.quizstudiopro.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Base64
import android.view.Surface
import com.quizstudiopro.app.logging.QuizLogger
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipelined hardware video + audio encoder session.
 * Consumes frames in real-time as they are submitted from WebView, preventing
 * unbounded memory accumulation (OOM) and enabling faster-than-realtime encoding.
 */
class StreamingVideoEncoderSession(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val totalFrames: Int,
    private val audioPcmFile: File?,
    private val totalAudioSamplesOverride: Long?,
    private val listener: VideoEncoder.ProgressListener?
) {
    companion object {
        private const val TAG = "StreamingEncoder"
        private const val QUEUE_CAPACITY = 8
    }

    private data class FrameItem(val frameIndex: Int, val jpegBytes: ByteArray, val isEos: Boolean = false)

    private val frameQueue = ArrayBlockingQueue<FrameItem>(QUEUE_CAPACITY)
    private val isAborted = AtomicBoolean(false)
    private val isFinished = AtomicBoolean(false)

    @Volatile
    private var workerException: Throwable? = null

    private var workerThread: Thread? = null

    fun start() {
        if (outputFile.exists()) {
            outputFile.delete()
        }
        val t = Thread({
            runPipeline()
        }, "MediaCodecEncoderWorker")
        workerThread = t
        t.start()
    }

    /**
     * Submits a frame from the JavaScript bridge. Blocks briefly if the queue is full,
     * applying backpressure to JavaScript so rendering paces itself with hardware encoding.
     */
    fun submitFrameBytes(frameIndex: Int, jpegBytes: ByteArray): Boolean {
        if (isAborted.get()) {
            throw IllegalStateException("Encoder worker aborted: ${workerException?.message ?: "cancelled"}")
        }
        workerException?.let { throw RuntimeException("Encoder worker failed: ${it.message}", it) }

        try {
            var offered = false
            while (!offered && !isAborted.get()) {
                workerException?.let { throw RuntimeException("Encoder worker failed: ${it.message}", it) }
                offered = frameQueue.offer(FrameItem(frameIndex, jpegBytes, false), 500, TimeUnit.MILLISECONDS)
            }
            return offered
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    /**
     * Signals that all frames have been submitted and awaits pipeline completion.
     */
    fun finishAndAwait(timeoutMs: Long = 60_000L): Boolean {
        if (isAborted.get()) return false
        try {
            frameQueue.offer(FrameItem(totalFrames, ByteArray(0), isEos = true), 10, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        workerThread?.join(timeoutMs)
        workerException?.let { throw RuntimeException("Encoder pipeline error: ${it.message}", it) }
        return isFinished.get() && outputFile.exists() && outputFile.length() > 0
    }

    fun abort() {
        isAborted.set(true)
        frameQueue.clear()
        workerThread?.interrupt()
    }

    private fun runPipeline() {
        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var eglRenderer: EglTextureRenderer? = null
        var audioFileInputStream: FileInputStream? = null

        val selectedFps = if (fps in 10..60) fps else 24
        val bitRate = when {
            width >= 1920 -> 10_000_000
            width >= 1280 -> 6_000_000
            else -> 3_000_000
        }

        var muxerStarted = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        val pendingVideoBuffers = ArrayList<VideoEncoder.PendingSample>()
        val pendingAudioBuffers = ArrayList<VideoEncoder.PendingSample>()
        var videoEOSReceived = false
        var audioOutputEosReceived = false
        var audioEOSInputSent = false

        val hasAudio = audioPcmFile != null && audioPcmFile.exists() && audioPcmFile.length() > 0
        var totalPcmSamples = 0L
        var pcmSampleOffset = 0L

        try {
            // 1. Configure Video MediaCodec
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            videoCodec = selectAndCreateEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec.createInputSurface()
            videoCodec.start()

            eglRenderer = EglTextureRenderer(inputSurface, width, height)

            // 2. Configure Audio MediaCodec if audio exists
            if (hasAudio) {
                audioFileInputStream = FileInputStream(audioPcmFile)
                totalPcmSamples = totalAudioSamplesOverride ?: (audioPcmFile.length() / 2)
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec.start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            fun checkStartMuxer() {
                if (!muxerStarted && videoTrackIndex >= 0 && (!hasAudio || audioTrackIndex >= 0)) {
                    muxer.start()
                    muxerStarted = true
                    val m = muxer
                    for (s in pendingVideoBuffers) {
                        m.writeSampleData(videoTrackIndex, s.data, s.info)
                    }
                    pendingVideoBuffers.clear()
                    for (s in pendingAudioBuffers) {
                        m.writeSampleData(audioTrackIndex, s.data, s.info)
                    }
                    pendingAudioBuffers.clear()
                }
            }

            fun feedAudioInput() {
                val ac = audioCodec ?: return
                val fis = audioFileInputStream ?: return
                if (audioEOSInputSent) return

                val inIndex = ac.dequeueInputBuffer(0)
                if (inIndex >= 0) {
                    val inputBuf = ac.getInputBuffer(inIndex) ?: return
                    inputBuf.clear()
                    val pcmBytes = ByteArray(inputBuf.capacity())
                    val readBytes = fis.read(pcmBytes)

                    if (readBytes > 0) {
                        inputBuf.put(pcmBytes, 0, readBytes)
                        val samplesRead = readBytes / 2
                        val ptsUs = pcmSampleOffset * 1_000_000L / 48000L
                        pcmSampleOffset += samplesRead
                        ac.queueInputBuffer(inIndex, 0, readBytes, ptsUs, 0)
                    } else {
                        val ptsUs = pcmSampleOffset * 1_000_000L / 48000L
                        ac.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEOSInputSent = true
                    }
                }
            }

            fun drainAudioOutput() {
                val ac = audioCodec ?: return
                val audioBufferInfo = MediaCodec.BufferInfo()
                var outIndex = ac.dequeueOutputBuffer(audioBufferInfo, 0)
                while (outIndex >= 0) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = muxer?.addTrack(ac.outputFormat) ?: -1
                            checkStartMuxer()
                        }
                    } else {
                        val encodedData = ac.getOutputBuffer(outIndex)
                        if (encodedData != null && audioBufferInfo.size > 0) {
                            if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                audioBufferInfo.size = 0
                            }
                            if (audioBufferInfo.size > 0) {
                                val dupData = ByteBuffer.allocate(audioBufferInfo.size)
                                encodedData.position(audioBufferInfo.offset)
                                encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
                                dupData.put(encodedData)
                                dupData.flip()
                                val copyInfo = MediaCodec.BufferInfo().apply {
                                    set(0, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                                }
                                if (muxerStarted && audioTrackIndex >= 0) {
                                    muxer?.writeSampleData(audioTrackIndex, dupData, copyInfo)
                                } else {
                                    pendingAudioBuffers.add(VideoEncoder.PendingSample(dupData, copyInfo))
                                }
                            }
                        }
                        if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            audioOutputEosReceived = true
                        }
                        ac.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = ac.dequeueOutputBuffer(audioBufferInfo, 0)
                }
            }

            fun drainVideoOutput() {
                val vc = videoCodec ?: return
                val videoBufferInfo = MediaCodec.BufferInfo()
                var outIndex = vc.dequeueOutputBuffer(videoBufferInfo, 0)
                while (outIndex >= 0) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = muxer?.addTrack(vc.outputFormat) ?: -1
                            checkStartMuxer()
                        }
                    } else {
                        if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            videoEOSReceived = true
                        }
                        val encodedData = vc.getOutputBuffer(outIndex)
                        if (encodedData != null && videoBufferInfo.size > 0) {
                            if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                videoBufferInfo.size = 0
                            }
                            if (videoBufferInfo.size > 0) {
                                val dupData = ByteBuffer.allocate(videoBufferInfo.size)
                                encodedData.position(videoBufferInfo.offset)
                                encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)
                                dupData.put(encodedData)
                                dupData.flip()
                                val copyInfo = MediaCodec.BufferInfo().apply {
                                    set(0, videoBufferInfo.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags)
                                }
                                if (muxerStarted && videoTrackIndex >= 0) {
                                    muxer?.writeSampleData(videoTrackIndex, dupData, copyInfo)
                                } else {
                                    pendingVideoBuffers.add(VideoEncoder.PendingSample(dupData, copyInfo))
                                }
                            }
                        }
                        vc.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = vc.dequeueOutputBuffer(videoBufferInfo, 0)
                }
            }

            val bOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            var framesProcessed = 0
            while (!isAborted.get()) {
                val item = frameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (item.isEos) break

                val bmp = BitmapFactory.decodeByteArray(item.jpegBytes, 0, item.jpegBytes.size, bOptions)
                if (bmp != null) {
                    val ptsNs = item.frameIndex.toLong() * 1_000_000_000L / selectedFps
                    eglRenderer.drawFrame(bmp, ptsNs)
                    bmp.recycle()
                }

                drainVideoOutput()
                if (hasAudio) {
                    feedAudioInput()
                    drainAudioOutput()
                }

                framesProcessed++
                if (framesProcessed % 10 == 0 || framesProcessed == totalFrames) {
                    val pct = 25f + (framesProcessed.toFloat() / totalFrames) * 65f
                    listener?.onProgress(framesProcessed, totalFrames, "Encoding video frames ($framesProcessed / $totalFrames)", pct)
                }
            }

            if (!isAborted.get()) {
                videoCodec.signalEndOfInputStream()
                if (hasAudio && !audioEOSInputSent) {
                    feedAudioInput()
                }

                val deadline = System.currentTimeMillis() + 25_000L
                while ((!videoEOSReceived || (hasAudio && !audioOutputEosReceived)) && System.currentTimeMillis() < deadline) {
                    drainVideoOutput()
                    if (hasAudio && !audioOutputEosReceived) {
                        feedAudioInput()
                        drainAudioOutput()
                    }
                    if (!videoEOSReceived || (hasAudio && !audioOutputEosReceived)) {
                        Thread.sleep(5)
                    }
                }

                if (!videoEOSReceived) {
                    throw IllegalStateException("Video encoder finalization timeout: Video EOS flag not received.")
                }

                if (muxerStarted) {
                    muxer.stop()
                    muxerStarted = false
                }
                isFinished.set(true)
            }
        } catch (e: Throwable) {
            QuizLogger.error(TAG, "Fatal error in encoder pipeline: ${e.message}")
            workerException = e
            isAborted.set(true)
        } finally {
            try { eglRenderer?.release() } catch (_: Exception) {}
            try { videoCodec?.stop() } catch (_: Exception) {}
            try { videoCodec?.release() } catch (_: Exception) {}
            try { audioCodec?.stop() } catch (_: Exception) {}
            try { audioCodec?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { audioFileInputStream?.close() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun selectAndCreateEncoder(mimeType: String): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            val types = info.supportedTypes
            for (t in types) {
                if (t.equals(mimeType, ignoreCase = true)) {
                    val caps = info.getCapabilitiesForType(mimeType)
                    if (caps != null) {
                        try {
                            return MediaCodec.createByCodecName(info.name)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        return MediaCodec.createEncoderByType(mimeType)
    }
}
