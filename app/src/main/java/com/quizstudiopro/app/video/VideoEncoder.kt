package com.quizstudiopro.app.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.quizstudiopro.app.logging.QuizLogger
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object VideoEncoder {
    private const val TAG = "VideoEncoder"

    interface ProgressListener {
        fun onProgress(currentFrame: Int, totalFrames: Int, stage: String, explicitProgressPercent: Float? = null)
    }

    fun encodeVideo(
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int,
        totalFrames: Int,
        frameProvider: (frameIndex: Int) -> Bitmap?,
        audioPcmFile: File? = null,
        totalAudioSamplesOverride: Long? = null,
        listener: ProgressListener? = null
    ): Boolean {
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

        val pendingVideoBuffers = ArrayList<PendingSample>()
        val pendingAudioBuffers = ArrayList<PendingSample>()

        var videoEOSReceived = false
        var audioOutputEosReceived = false
        var audioEOSInputSent = false

        val hasAudio = audioPcmFile != null && audioPcmFile.exists() && audioPcmFile.length() > 0
        var totalPcmSamples = 0L
        var pcmSampleOffset = 0L

        var totalVideoFramesWritten = 0
        var totalAudioSamplesWritten = 0L
        var lastVideoPtsUs = -1L
        var lastAudioPtsUs = -1L

        return try {
            if (outputFile.exists()) {
                outputFile.delete()
            }

            // 1. Setup Video Codec
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec.createInputSurface()
            videoCodec.start()
            eglRenderer = EglTextureRenderer(inputSurface, width, height)

            // 2. Setup Audio Codec if PCM file present
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
                    muxer?.start()
                    muxerStarted = true
                    val m = muxer ?: return

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
                val stream = audioFileInputStream ?: return
                if (audioEOSInputSent) return

                var inIndex = ac.dequeueInputBuffer(0)
                while (inIndex >= 0 && !audioEOSInputSent) {
                    val buf = ac.getInputBuffer(inIndex)
                    if (buf != null) {
                        buf.clear()
                        val bytesToRead = minOf(buf.capacity(), 4096)
                        val tempBytes = ByteArray(bytesToRead)
                        val readBytes = stream.read(tempBytes)

                        if (readBytes > 0) {
                            buf.put(tempBytes, 0, readBytes)
                            val samplesRead = readBytes / 2
                            val ptsUs = pcmSampleOffset * 1_000_000L / 48000L
                            ac.queueInputBuffer(inIndex, 0, readBytes, ptsUs, 0)
                            pcmSampleOffset += samplesRead
                        } else {
                            val ptsUs = pcmSampleOffset * 1_000_000L / 48000L
                            ac.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioEOSInputSent = true
                        }
                    }
                    if (!audioEOSInputSent) {
                        inIndex = ac.dequeueInputBuffer(0)
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
                                    pendingAudioBuffers.add(PendingSample(dupData, copyInfo))
                                }
                                totalAudioSamplesWritten++
                                lastAudioPtsUs = audioBufferInfo.presentationTimeUs
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
                                    pendingVideoBuffers.add(PendingSample(dupData, copyInfo))
                                }
                                totalVideoFramesWritten++
                                lastVideoPtsUs = videoBufferInfo.presentationTimeUs
                            }
                        }
                        vc.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = vc.dequeueOutputBuffer(videoBufferInfo, 0)
                }
            }

            // 3. Main Frame Loop
            var frameIdx = 0
            while (frameIdx < totalFrames) {
                val bmp = frameProvider(frameIdx)
                if (bmp != null) {
                    val ptsNs = frameIdx.toLong() * 1_000_000_000L / selectedFps
                    eglRenderer?.drawFrame(bmp, ptsNs)
                    bmp.recycle()
                }
                drainVideoOutput()
                if (hasAudio) {
                    feedAudioInput()
                    drainAudioOutput()
                }
                frameIdx++
                if (frameIdx % 10 == 0 || frameIdx == totalFrames) {
                    val p = 25f + (frameIdx.toFloat() / totalFrames) * 65f
                    listener?.onProgress(frameIdx, totalFrames, "Rendering & encoding video frames ($frameIdx / $totalFrames)", p)
                }
            }

            // 4. Video EOS
            videoCodec.signalEndOfInputStream()

            // 5. Submit Audio EOS
            if (hasAudio && !audioEOSInputSent) {
                feedAudioInput()
            }

            // 6. Finalization Loop
            val startTime = System.currentTimeMillis()
            val deadline = startTime + 20_000L
            while ((!videoEOSReceived || (hasAudio && !audioOutputEosReceived)) && System.currentTimeMillis() < deadline) {
                drainVideoOutput()
                if (hasAudio && !audioOutputEosReceived) {
                    feedAudioInput()
                    drainAudioOutput()
                }
                if (!videoEOSReceived || (hasAudio && !audioOutputEosReceived)) {
                    Thread.sleep(10)
                }
            }

            if (!videoEOSReceived) {
                throw IllegalStateException("Video encoder finalization timeout: Video EOS flag not received.")
            }

            // 7. Finalize MediaMuxer
            if (muxerStarted) {
                muxer?.stop()
                muxerStarted = false
            }
            true
        } catch (e: Exception) {
            QuizLogger.error(TAG, "Fatal error during video encoding: ${e.message}")
            false
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

    fun verifyMp4AudioTrack(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasAudio = false
            var sampleCount = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                if (mime.startsWith("audio/") && duration > 0) {
                    hasAudio = true
                    extractor.selectTrack(i)
                    val buf = ByteBuffer.allocate(1024)
                    while (extractor.readSampleData(buf, 0) >= 0) {
                        sampleCount++
                        if (!extractor.advance()) break
                    }
                    break
                }
            }
            hasAudio && sampleCount > 0
        } catch (_: Exception) {
            false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    data class PendingSample(
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )
}
