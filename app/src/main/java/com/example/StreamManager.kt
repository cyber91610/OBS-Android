package com.example

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtmp.RtmpFromFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object StreamManager {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var rtmpFromFile: CustomRtmpFromFile? = null
    private var tickerJob: Job? = null
    private var streamStartTime = 0L
    private var lastVideoTime = 0.0
    var onServiceStopRequested: (() -> Unit)? = null

    fun setVideo(uri: Uri, fileName: String, metadata: VideoMetadata) {
        AppLogManager.i(
            "FilePicker",
            "Video selected: '$fileName' (${metadata.displayWidth}x${metadata.displayHeight}, duration=${metadata.durationSeconds}s, rotation=${metadata.rotation}°)"
        )
        _uiState.update {
            it.copy(
                selectedVideoUri = uri,
                selectedFileName = fileName,
                videoMetadata = metadata,
                totalDurationSeconds = metadata.durationSeconds,
                elapsedTimeSeconds = 0L,
                remainingTimeSeconds = metadata.durationSeconds,
                errorMessage = null,
                loopCount = 0,
                totalStreamElapsedSeconds = 0L,
                status = if (it.status == StreamStatus.FINISHED || it.status == StreamStatus.ERROR) {
                    StreamStatus.IDLE
                } else {
                    it.status
                }
            )
        }
    }

    fun setLoopEnabled(enabled: Boolean) {
        AppLogManager.i("StreamManager", "Loop mode set to: ${if (enabled) "ENABLED (Endless)" else "DISABLED (Play once)"}")
        _uiState.update { it.copy(isLoopEnabled = enabled) }
        try {
            rtmpFromFile?.setLoopMode(enabled)
        } catch (_: Exception) {}
    }

    fun setStreamProfile(profile: StreamResolutionProfile) {
        AppLogManager.i(
            "StreamProfile",
            "Active profile set to: ${profile.title} (Canvas: ${profile.baseResolution}, Output: ${profile.outputResolution}, Aspect Ratio: ${profile.aspectRatio})"
        )
        _uiState.update { it.copy(streamProfile = profile) }
    }

    fun setStreamOrientation(orientation: StreamResolutionProfile) {
        setStreamProfile(orientation)
    }

    fun setStreamKey(key: String) {
        if (key.length != _uiState.value.streamKey.length) {
            AppLogManager.d("StreamKey", "Stream key length changed to ${key.length} chars")
        }
        _uiState.update {
            it.copy(
                streamKey = key,
                errorMessage = if (it.errorMessage?.contains("Stream Key", ignoreCase = true) == true) null else it.errorMessage
            )
        }
    }

    fun clearError() {
        AppLogManager.d("StreamManager", "Error cleared by user")
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun requestStartStream(context: Context) {
        AppLogManager.i("StreamManager", "User requested to start stream")
        val current = _uiState.value
        if (current.selectedVideoUri == null) {
            val err = "Please select an MP4 video file first."
            AppLogManager.e("StreamManager", err)
            _uiState.update {
                it.copy(
                    status = StreamStatus.ERROR,
                    errorMessage = err
                )
            }
            return
        }

        val key = current.streamKey.trim()
        if (key.isEmpty()) {
            val err = "Please enter your YouTube Stream Key."
            AppLogManager.e("StreamManager", err)
            _uiState.update {
                it.copy(
                    status = StreamStatus.ERROR,
                    errorMessage = err
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                status = StreamStatus.PREPARING,
                errorMessage = null,
                isStreaming = false,
                elapsedTimeSeconds = 0L,
                remainingTimeSeconds = it.totalDurationSeconds
            )
        }

        StreamService.start(context)
    }

    fun requestStopStream() {
        AppLogManager.i("StreamManager", "User requested to stop stream")
        stopStreamInternal(finishedNaturally = false, keepError = false)
        _uiState.update {
            it.copy(
                status = StreamStatus.IDLE,
                isStreaming = false,
                errorMessage = null
            )
        }
    }

    internal fun startInternal(context: Context) {
        val uri = _uiState.value.selectedVideoUri
        if (uri == null) {
            _uiState.update {
                it.copy(status = StreamStatus.ERROR, errorMessage = "No video selected.")
            }
            stopStreamInternal(finishedNaturally = false, keepError = true)
            return
        }

        val key = _uiState.value.streamKey.trim()
        if (key.isEmpty()) {
            _uiState.update {
                it.copy(status = StreamStatus.ERROR, errorMessage = "YouTube Stream Key is missing.")
            }
            stopStreamInternal(finishedNaturally = false, keepError = true)
            return
        }

        val endpoint = "rtmp://a.rtmp.youtube.com/live2/$key"

        cleanupRtmp()

        val connectChecker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                val masked = if (url.contains("/")) url.substringBeforeLast("/") + "/***KEY" else url
                AppLogManager.i("RTMP", "Connecting to YouTube RTMP: $masked")
                scope.launch {
                    _uiState.update { it.copy(status = StreamStatus.CONNECTING) }
                }
            }

            override fun onConnectionSuccess() {
                AppLogManager.s("RTMP", "YouTube RTMP connection successful! Handshake complete. Status: LIVE")
                scope.launch {
                    _uiState.update { it.copy(status = StreamStatus.LIVE, isStreaming = true) }
                }
            }

            override fun onConnectionFailed(reason: String) {
                val errMsg = "RTMP connection failed: $reason"
                AppLogManager.e("RTMP", errMsg)
                scope.launch {
                    _uiState.update {
                        it.copy(
                            status = StreamStatus.ERROR,
                            isStreaming = false,
                            errorMessage = errMsg
                        )
                    }
                    stopStreamInternal(finishedNaturally = false, keepError = true)
                }
            }

            override fun onDisconnect() {
                AppLogManager.w("RTMP", "RTMP connection disconnected.")
                scope.launch {
                    if (_uiState.value.status == StreamStatus.LIVE) {
                        val errMsg = "Disconnected from YouTube RTMP server."
                        AppLogManager.e("RTMP", errMsg)
                        _uiState.update {
                            it.copy(
                                status = StreamStatus.ERROR,
                                isStreaming = false,
                                errorMessage = errMsg
                            )
                        }
                        stopStreamInternal(finishedNaturally = false, keepError = true)
                    }
                }
            }

            override fun onAuthError() {
                val errMsg = "Stream key authentication failed. YouTube rejected your key."
                AppLogManager.e("RTMP", errMsg)
                scope.launch {
                    _uiState.update {
                        it.copy(
                            status = StreamStatus.ERROR,
                            isStreaming = false,
                            errorMessage = errMsg
                        )
                    }
                    stopStreamInternal(finishedNaturally = false, keepError = true)
                }
            }

            override fun onAuthSuccess() {
                AppLogManager.s("RTMP", "Stream key authenticated successfully by YouTube server.")
            }

            override fun onNewBitrate(bitrate: Long) {
                scope.launch {
                    _uiState.update { it.copy(currentBitrate = bitrate) }
                }
            }
        }

        val videoDecoderInterface = object : VideoDecoderInterface {
            override fun onVideoDecoderFinished() {
                AppLogManager.i("VideoDecoder", "Video file playback reached the end.")
                scope.launch {
                    // Video reached the end!
                    stopStreamInternal(finishedNaturally = true, keepError = false)
                }
            }
        }

        val audioDecoderInterface = object : AudioDecoderInterface {
            override fun onAudioDecoderFinished() {
                AppLogManager.d("AudioDecoder", "Audio file track reached the end.")
            }
        }

        try {
            val state = _uiState.value
            val profile = state.streamProfile

            val baseCanvasWidth = profile.baseCanvasWidth
            val baseCanvasHeight = profile.baseCanvasHeight
            val outputScaledWidth = profile.outputScaledWidth
            val outputScaledHeight = profile.outputScaledHeight
            val isPortrait = profile.isPortrait
            val bitrate = 3000000 // 3.0 Mbps for crisp 1080p stream

            AppLogManager.i(
                "StreamManager",
                "Preparing stream: Profile=${profile.title}, Base=${profile.baseResolution}, Output=${profile.outputResolution}, Bitrate=${bitrate / 1000}kbps, Loop=${state.isLoopEnabled}"
            )

            val rtmp = CustomRtmpFromFile(context, connectChecker, videoDecoderInterface, audioDecoderInterface)
            rtmp.setLoopMode(state.isLoopEnabled)

            // Prepares video decoder, sets native output resolution, and configures GL Fit-to-Screen aspect ratio scaling
            val vPrep = rtmp.prepareVideoWithProfile(
                context = context,
                uri = uri,
                targetWidth = outputScaledWidth,
                targetHeight = outputScaledHeight,
                bitrate = bitrate,
                isPortrait = isPortrait
            )
            val aPrep = rtmp.prepareAudio(context, uri)
            if (!aPrep) {
                AppLogManager.w("AudioDecoder", "Audio track preparation returned false. Stream may have no sound.")
            } else {
                AppLogManager.s("AudioDecoder", "Audio decoder prepared successfully.")
            }

            if (!vPrep) {
                val err = "Cannot prepare video decoder. Verify file is valid MP4 with H.264 video."
                AppLogManager.e("VideoDecoder", err)
                _uiState.update {
                    it.copy(
                        status = StreamStatus.ERROR,
                        errorMessage = err
                    )
                }
                stopStreamInternal(finishedNaturally = false, keepError = true)
                return
            }

            val totalDur = if (rtmp.videoDuration > 0) {
                rtmp.videoDuration.toLong()
            } else {
                _uiState.value.totalDurationSeconds
            }

            _uiState.update {
                it.copy(
                    totalDurationSeconds = totalDur,
                    remainingTimeSeconds = totalDur
                )
            }

            AppLogManager.i("StreamManager", "Initiating RTMP connection to YouTube live servers...")
            rtmp.startStream(endpoint)
            rtmpFromFile = rtmp

            startTicker()
        } catch (e: Exception) {
            val err = "Streaming initialization error: ${e.localizedMessage ?: "Unknown failure"}"
            AppLogManager.e("StreamManager", err, e)
            _uiState.update {
                it.copy(
                    status = StreamStatus.ERROR,
                    errorMessage = err
                )
            }
            stopStreamInternal(finishedNaturally = false, keepError = true)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        streamStartTime = System.currentTimeMillis()
        lastVideoTime = 0.0
        tickerJob = scope.launch {
            while (isActive) {
                delay(500)
                val rtmp = rtmpFromFile
                if (rtmp != null && (rtmp.isStreaming || _uiState.value.status == StreamStatus.LIVE)) {
                    val currentVideoSecs = rtmp.videoTime
                    val elapsed = currentVideoSecs.toLong()
                    val total = if (rtmp.videoDuration > 0) {
                        rtmp.videoDuration.toLong()
                    } else {
                        _uiState.value.totalDurationSeconds
                    }
                    val totalStreamElapsed = (System.currentTimeMillis() - streamStartTime) / 1000L

                    // Detect loop completion: when videoTime wraps back to near 0
                    if (_uiState.value.isLoopEnabled && lastVideoTime > 2.0 && currentVideoSecs < 1.0) {
                        val nextLoop = _uiState.value.loopCount + 1
                        AppLogManager.s("StreamManager", "Video looped! Starting playback cycle #$nextLoop")
                        _uiState.update { it.copy(loopCount = nextLoop) }
                    }
                    lastVideoTime = currentVideoSecs

                    val remaining = maxOf(0L, total - elapsed)
                    _uiState.update {
                        it.copy(
                            elapsedTimeSeconds = elapsed,
                            remainingTimeSeconds = remaining,
                            totalDurationSeconds = total,
                            totalStreamElapsedSeconds = totalStreamElapsed
                        )
                    }
                }
            }
        }
    }

    private fun stopStreamInternal(finishedNaturally: Boolean, keepError: Boolean) {
        val totalSecs = _uiState.value.totalStreamElapsedSeconds
        AppLogManager.i("StreamManager", "Stream teardown: finishedNaturally=$finishedNaturally, keepError=$keepError, totalStreamed=${totalSecs}s")
        tickerJob?.cancel()
        tickerJob = null
        lastVideoTime = 0.0

        cleanupRtmp()

        _uiState.update {
            it.copy(
                isStreaming = false,
                currentBitrate = 0L,
                status = if (finishedNaturally) {
                    StreamStatus.FINISHED
                } else if (keepError) {
                    it.status
                } else {
                    StreamStatus.IDLE
                },
                remainingTimeSeconds = if (finishedNaturally) 0L else it.remainingTimeSeconds
            )
        }

        onServiceStopRequested?.invoke()
    }

    private fun cleanupRtmp() {
        try {
            if (rtmpFromFile?.isStreaming == true) {
                rtmpFromFile?.stopStream()
            }
        } catch (_: Exception) {}
        rtmpFromFile = null
    }
}
