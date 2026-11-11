package com.quizstudiopro.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.quizstudiopro.app.audio.VoiceEngine
import com.quizstudiopro.app.logging.QuizLogger
import com.quizstudiopro.app.service.QuizEncodingService
import com.quizstudiopro.app.video.VideoEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var output: OutputStream? = null
    private var pendingUri: Uri? = null
    private var pendingName: String = "quiz-video.mp4"
    private var lastSavedUri: Uri? = null
    private val pickFileRequest = 4102

    private val activityScope = CoroutineScope(Dispatchers.Main)

    // Export Session variables
    private var exportWidth = 1280
    private var exportHeight = 720
    private var exportFps = 24
    private var exportTotalFrames = 0
    private var exportTotalAudioSamples = 0L
    private var pcmTempFile: File? = null
    private var pcmOutputStream: FileOutputStream? = null
    private var streamingSession: com.quizstudiopro.app.video.StreamingVideoEncoderSession? = null
    private var currentExportOutputFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView = WebView(this)
        webView.setBackgroundColor(android.graphics.Color.rgb(10, 10, 12))
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                request?.url?.let { assetLoader.shouldInterceptRequest(it) }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request?.url?.host ?: return true
                return host != "appassets.androidplatform.net"
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "image/*", "video/*", "application/json", "text/csv", "text/plain"))
                }
                return try { startActivityForResult(intent, pickFileRequest); true }
                catch (_: Exception) { pendingFileCallback?.onReceiveValue(null); pendingFileCallback = null; false }
            }
        }
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/login.html")
    }

    inner class AndroidBridge {
        @JavascriptInterface fun deviceId(): String = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        @JavascriptInterface fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        @JavascriptInterface fun setKeepScreenOn(enabled: Boolean) {
            runOnUiThread { if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        // --- Edge TTS Bridge APIs ---
        @JavascriptInterface fun fetchVoiceInventory(): String {
            return fetchVoiceInventory(false)
        }

        @JavascriptInterface fun fetchVoiceInventory(forceRefresh: Boolean): String {
            return try {
                runBlocking(Dispatchers.IO) {
                    val res = VoiceEngine.fetchVoiceInventoryResult(this@MainActivity, forceRefresh)
                    val jsonArray = JSONArray()
                    for (v in res.voices) {
                        val obj = JSONObject().apply {
                            put("shortName", v.shortName)
                            put("friendlyName", v.friendlyName)
                            put("locale", v.locale)
                            put("language", v.language)
                            put("gender", v.gender)
                            put("isOfflineCached", v.isOfflineCached)
                        }
                        jsonArray.put(obj)
                    }
                    JSONObject().apply {
                        put("ok", res.voices.isNotEmpty())
                        put("isLive", res.isLive)
                        put("error", res.error ?: "")
                        put("voices", jsonArray)
                    }.toString()
                }
            } catch (e: Exception) {
                QuizLogger.error("AndroidBridge", "fetchVoiceInventory error: ${e.message}")
                JSONObject().apply {
                    put("ok", false)
                    put("isLive", false)
                    put("error", e.message ?: "Failed to fetch voices")
                    put("voices", JSONArray())
                }.toString()
            }
        }

        @JavascriptInterface fun synthesizeSpeech(text: String, voiceShortName: String, speedPercent: Int): String {
            return try {
                runBlocking(Dispatchers.IO) {
                    val mp3Bytes = VoiceEngine.synthesizeToMp3Bytes(this@MainActivity, text, voiceShortName, speedPercent)
                    val b64 = Base64.encodeToString(mp3Bytes, Base64.NO_WRAP)
                    JSONObject().apply {
                        put("ok", true)
                        put("mp3Base64", "data:audio/mp3;base64,$b64")
                    }.toString()
                }
            } catch (e: Exception) {
                QuizLogger.error("AndroidBridge", "synthesizeSpeech error: ${e.message}")
                JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "Edge TTS synthesis failed")
                }.toString()
            }
        }

        @JavascriptInterface fun playPreviewAudio(text: String, voiceShortName: String, speedPercent: Int) {
            activityScope.launch {
                try {
                    runOnUiThread {
                        webView.evaluateJavascript("window.onQuizLog && window.onQuizLog('Edge TTS connection started for voice $voiceShortName', 'info')", null)
                    }
                    VoiceEngine.playPreviewAudio(this@MainActivity, text, voiceShortName, speedPercent) {
                        runOnUiThread {
                            webView.evaluateJavascript("window.onPreviewFinished && window.onPreviewFinished()", null)
                        }
                    }
                    runOnUiThread {
                        webView.evaluateJavascript("window.onQuizLog && window.onQuizLog('Audio decoding completed. Playing preview.', 'success')", null)
                    }
                } catch (e: Exception) {
                    val err = e.message ?: "Voice preview error"
                    QuizLogger.error("AndroidBridge", "Voice preview failed: $err")
                    runOnUiThread {
                        webView.evaluateJavascript("window.onQuizLog && window.onQuizLog('Voice preview failed: $err', 'error')", null)
                        webView.evaluateJavascript("window.onPreviewError && window.onPreviewError(${JSONObject.quote(err)})", null)
                        toast("Voice preview error: $err")
                    }
                }
            }
        }

        @JavascriptInterface fun stopPlayback() {
            VoiceEngine.stopPlayback()
        }

        @JavascriptInterface fun logLiveEvent(event: String, type: String) {
            QuizLogger.info("LiveEvent", event)
            runOnUiThread {
                webView.evaluateJavascript("window.onQuizLog && window.onQuizLog(${JSONObject.quote(event)}, ${JSONObject.quote(type)})", null)
            }
        }

        // --- Native Export Bridge APIs ---
        @JavascriptInterface fun initNativeExport(width: Int, height: Int, fps: Int, totalFrames: Int, totalAudioSamples: Long): Boolean {
            return try {
                exportWidth = width
                exportHeight = height
                exportFps = fps
                exportTotalFrames = totalFrames
                exportTotalAudioSamples = totalAudioSamples

                try { streamingSession?.abort() } catch (_: Exception) {}
                streamingSession = null

                QuizEncodingService.start(this@MainActivity, "Preparing native MP4 export ($width x $height @ ${fps}fps)...")
                val cacheDir = File(cacheDir, "export_pcm_temp").apply { mkdirs() }
                pcmTempFile = File(cacheDir, "audio_track_${System.currentTimeMillis()}.pcm")
                pcmOutputStream = FileOutputStream(pcmTempFile)

                val safeName = "quiz_export_${System.currentTimeMillis()}.mp4"
                val targetFile = File(cacheDir, safeName)
                currentExportOutputFile = targetFile

                val session = com.quizstudiopro.app.video.StreamingVideoEncoderSession(
                    outputFile = targetFile,
                    width = width,
                    height = height,
                    fps = fps,
                    totalFrames = totalFrames,
                    audioPcmFile = pcmTempFile,
                    totalAudioSamplesOverride = totalAudioSamples,
                    listener = object : VideoEncoder.ProgressListener {
                        override fun onProgress(currentFrame: Int, totalFrames: Int, stage: String, explicitProgressPercent: Float?) {
                            val pct = (explicitProgressPercent ?: (25f + (currentFrame.toFloat() / totalFrames) * 65f)).toInt()
                            QuizEncodingService.updateProgress(this@MainActivity, pct, stage)
                            runOnUiThread {
                                webView.evaluateJavascript("window.onExportProgress && window.onExportProgress($pct, '$stage')", null)
                            }
                        }
                    }
                )
                session.start()
                streamingSession = session

                QuizLogger.info("QuizStudioPro-Encoder", "Native export streaming pipeline initialized: ${width}x${height} @ ${fps}fps ($totalFrames frames)")
                true
            } catch (e: Throwable) {
                val stackTrace = android.util.Log.getStackTraceString(e)
                QuizLogger.error("QuizStudioPro-Encoder", "initNativeExport fatal error: ${e.message}\n$stackTrace")
                false
            }
        }

        @JavascriptInterface fun writePcmChunk(base64Pcm: String): Boolean {
            return try {
                val bytes = Base64.decode(base64Pcm, Base64.DEFAULT)
                pcmOutputStream?.write(bytes)
                true
            } catch (e: Throwable) {
                val stackTrace = android.util.Log.getStackTraceString(e)
                QuizLogger.error("QuizStudioPro-Encoder", "writePcmChunk error: ${e.message}\n$stackTrace")
                false
            }
        }

        @JavascriptInterface fun submitFrame(frameIndex: Int, base64Image: String): Boolean {
            return try {
                val session = streamingSession ?: throw IllegalStateException("Streaming encoder session not initialized")
                val clean = if (base64Image.contains(",")) base64Image.split(",")[1] else base64Image
                val jpegBytes = Base64.decode(clean, Base64.DEFAULT)
                val accepted = session.submitFrameBytes(frameIndex, jpegBytes)
                if (!accepted) {
                    QuizLogger.warn("QuizStudioPro-Encoder", "Frame $frameIndex queue offer timed out or aborted")
                }
                accepted
            } catch (e: Throwable) {
                val stackTrace = android.util.Log.getStackTraceString(e)
                val msg = "submitFrame failed for frame $frameIndex (dims: ${exportWidth}x${exportHeight}, fps: $exportFps): ${e.javaClass.simpleName}: ${e.message}"
                QuizLogger.error("QuizStudioPro-Encoder", "$msg\n$stackTrace")
                runOnUiThread {
                    webView.evaluateJavascript("window.onQuizLog && window.onQuizLog(${JSONObject.quote(msg)}, 'error')", null)
                }
                throw RuntimeException(msg, e)
            }
        }

        @JavascriptInterface fun finalizeNativeExport(filename: String): String {
            return try {
                try {
                    pcmOutputStream?.flush()
                    pcmOutputStream?.close()
                } catch (_: Exception) {}

                val session = streamingSession ?: throw IllegalStateException("Streaming encoder session is null")
                val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "quiz-video.mp4" }
                val targetFile = currentExportOutputFile ?: File(cacheDir, safeName)

                QuizEncodingService.updateProgress(this@MainActivity, 92, "Finalizing MediaMuxer MP4 container...")
                val success = session.finishAndAwait(timeoutMs = 60_000L)

                if (success && targetFile.exists() && targetFile.length() > 0) {
                    // Save to Gallery Movies/QuizStudioPro
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/QuizStudioPro")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            targetFile.inputStream().use { input -> input.copyTo(out) }
                        }
                        if (Build.VERSION.SDK_INT >= 29) {
                            val updateValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                            contentResolver.update(uri, updateValues, null, null)
                        }
                        lastSavedUri = uri
                        targetFile.delete()
                    }
                    QuizEncodingService.stop(this@MainActivity)
                    cleanupExportTemp()
                    JSONObject().apply {
                        put("ok", true)
                        put("message", "Saved to Movies/QuizStudioPro")
                    }.toString()
                } else {
                    QuizEncodingService.stop(this@MainActivity)
                    cleanupExportTemp()
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "Hardware video encoder failed to produce valid MP4 output.")
                    }.toString()
                }
            } catch (e: Throwable) {
                val stackTrace = android.util.Log.getStackTraceString(e)
                QuizLogger.error("QuizStudioPro-Encoder", "finalizeNativeExport error: ${e.message}\n$stackTrace")
                QuizEncodingService.stop(this@MainActivity)
                cleanupExportTemp()
                JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "Native MP4 export failed.")
                }.toString()
            }
        }

        @JavascriptInterface fun cancelNativeExport() {
            QuizEncodingService.stop(this@MainActivity)
            cleanupExportTemp()
        }

        // --- Save / File bridge methods ---
        @JavascriptInterface fun saveBegin(name: String): Boolean {
            return try {
                synchronized(this@MainActivity) {
                    closePending(false)
                    pendingName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "quiz-video.mp4" }
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, pendingName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/QuizStudioPro")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    pendingUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    output = pendingUri?.let { contentResolver.openOutputStream(it, "w") }
                    if (output == null) { pendingUri?.let { contentResolver.delete(it, null, null) }; pendingUri = null; false } else true
                }
            } catch (_: Exception) { closePending(false); false }
        }
        @JavascriptInterface fun saveChunk(base64: String): Boolean {
            return try {
                synchronized(this@MainActivity) {
                    val stream = output ?: return false
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    stream.write(bytes)
                    true
                }
            } catch (_: Exception) { false }
        }
        @JavascriptInterface fun saveEnd(): Boolean {
            return try {
                synchronized(this@MainActivity) {
                    output?.flush(); output?.close(); output = null
                    val uri = pendingUri ?: return false
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        contentResolver.update(uri, values, null, null)
                    }
                    lastSavedUri = uri
                    pendingUri = null
                    runOnUiThread { webView.evaluateJavascript("window.onFileSaved && window.onFileSaved('Saved to Movies/QuizStudioPro')", null) }
                    true
                }
            } catch (_: Exception) { closePending(false); false }
        }
        @JavascriptInterface fun saveFailed(message: String) {
            synchronized(this@MainActivity) { closePending(false) }
            runOnUiThread { webView.evaluateJavascript("window.onFileSaved && window.onFileSaved('')", null); Toast.makeText(this@MainActivity, "Video save failed: $message", Toast.LENGTH_LONG).show() }
        }
        @JavascriptInterface fun openSavedVideo(): Boolean {
            val uri = lastSavedUri ?: return false
            return try { runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }; true } catch (_: Exception) { false }
        }
        @JavascriptInterface fun shareSavedVideo(): Boolean {
            val uri = lastSavedUri ?: return false
            return try {
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    startActivity(Intent.createChooser(intent, "Share quiz video"))
                }; true
            } catch (_: Exception) { false }
        }
        @JavascriptInterface fun toast(message: String) { runOnUiThread { Toast.makeText(this@MainActivity, message.take(180), Toast.LENGTH_SHORT).show() } }
    }

    private fun cleanupExportTemp() {
        try {
            pcmOutputStream?.flush()
            pcmOutputStream?.close()
        } catch (_: Exception) {}
        pcmOutputStream = null
        try {
            pcmTempFile?.delete()
        } catch (_: Exception) {}
        pcmTempFile = null
        try {
            streamingSession?.abort()
        } catch (_: Exception) {}
        streamingSession = null
        currentExportOutputFile = null
    }

    private fun closePending(publish: Boolean) {
        try { output?.flush(); output?.close() } catch (_: Exception) {}
        output = null
        val uri = pendingUri
        if (uri != null && !publish) try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        pendingUri = null
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFileRequest) {
            val result = if (resultCode == RESULT_OK) data?.data?.let { arrayOf(it) } else null
            pendingFileCallback?.onReceiveValue(result)
            pendingFileCallback = null
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        cleanupExportTemp()
        closePending(false)
        if (::webView.isInitialized) { webView.removeJavascriptInterface("AndroidBridge"); webView.destroy() }
        super.onDestroy()
    }
}
