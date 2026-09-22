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

    private var rtmpFromFile: RtmpFromFile? = null
    private var tickerJob: Job? = null
    var onServiceStopRequested: (() -> Unit)? = null

    fun setVideo(uri: Uri, fileName: String, durationSec: Long) {
        _uiState.update {
            it.copy(
                selectedVideoUri = uri,
                selectedFileName = fileName,
                totalDurationSeconds = durationSec,
                elapsedTimeSeconds = 0L,
                remainingTimeSeconds = durationSec,
                errorMessage = null,
                status = if (it.status == StreamStatus.FINISHED || it.status == StreamStatus.ERROR) {
                    StreamStatus.IDLE
                } else {
                    it.status
                }
            )
        }
    }

    fun setStreamKey(key: String) {
        _uiState.update {
            it.copy(
                streamKey = key,
                errorMessage = if (it.errorMessage?.contains("Stream Key", ignoreCase = true) == true) null else it.errorMessage
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun requestStartStream(context: Context) {
        val current = _uiState.value
        if (current.selectedVideoUri == null) {
            _uiState.update {
                it.copy(
                    status = StreamStatus.ERROR,
                    errorMessage = "Please select an MP4 video file first."
                )
            }
            return
        }

        val key = current.streamKey.trim()
        if (key.isEmpty()) {
            _uiState.update {
                it.copy(
                    status = StreamStatus.ERROR,
                    errorMessage = "Please enter your YouTube Stream Key."
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
                scope.launch {
                    _uiState.update { it.copy(status = StreamStatus.CONNECTING) }
                }
            }

            override fun onConnectionSuccess() {
                scope.launch {
                    _uiState.update { it.copy(status = StreamStatus.LIVE, isStreaming = true) }
                }
            }

            override fun onConnectionFailed(reason: String) {
                scope.launch {
                    _uiState.update {
                        it.copy(
                            status = StreamStatus.ERROR,
                            isStreaming = false,
                            errorMessage = "RTMP connection failed: $reason"
                        )
                    }
                    stopStreamInternal(finishedNaturally = false, keepError = true)
                }
            }

            override fun onDisconnect() {
                scope.launch {
                    if (_uiState.value.status == StreamStatus.LIVE) {
                        _uiState.update {
                            it.copy(
                                status = StreamStatus.ERROR,
                                isStreaming = false,
                                errorMessage = "Disconnected from YouTube RTMP server."
                            )
                        }
                        stopStreamInternal(finishedNaturally = false, keepError = true)
                    }
                }
            }

            override fun onAuthError() {
                scope.launch {
                    _uiState.update {
                        it.copy(
                            status = StreamStatus.ERROR,
                            isStreaming = false,
                            errorMessage = "Stream key authentication failed. Check your key."
                        )
                    }
                    stopStreamInternal(finishedNaturally = false, keepError = true)
                }
            }

            override fun onAuthSuccess() {}

            override fun onNewBitrate(bitrate: Long) {
                scope.launch {
                    _uiState.update { it.copy(currentBitrate = bitrate) }
                }
            }
        }

        val videoDecoderInterface = object : VideoDecoderInterface {
            override fun onVideoDecoderFinished() {
                scope.launch {
                    // Video reached the end!
                    stopStreamInternal(finishedNaturally = true, keepError = false)
                }
            }
        }

        val audioDecoderInterface = object : AudioDecoderInterface {
            override fun onAudioDecoderFinished() {}
        }

        try {
            val rtmp = RtmpFromFile(context, connectChecker, videoDecoderInterface, audioDecoderInterface)
            rtmp.setLoopMode(false)

            val vPrep = rtmp.prepareVideo(context, uri)
            val aPrep = rtmp.prepareAudio(context, uri)

            if (!vPrep) {
                _uiState.update {
                    it.copy(
                        status = StreamStatus.ERROR,
                        errorMessage = "Cannot prepare video decoder. Verify file is valid MP4."
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

            rtmp.startStream(endpoint)
            rtmpFromFile = rtmp

            startTicker()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    status = StreamStatus.ERROR,
                    errorMessage = "Streaming error: ${e.localizedMessage ?: "Unknown failure"}"
                )
            }
            stopStreamInternal(finishedNaturally = false, keepError = true)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(500)
                val rtmp = rtmpFromFile
                if (rtmp != null && (rtmp.isStreaming || _uiState.value.status == StreamStatus.LIVE)) {
                    val elapsed = rtmp.videoTime.toLong()
                    val total = if (rtmp.videoDuration > 0) {
                        rtmp.videoDuration.toLong()
                    } else {
                        _uiState.value.totalDurationSeconds
                    }
                    val remaining = maxOf(0L, total - elapsed)
                    _uiState.update {
                        it.copy(
                            elapsedTimeSeconds = elapsed,
                            remainingTimeSeconds = remaining,
                            totalDurationSeconds = total
                        )
                    }
                }
            }
        }
    }

    private fun stopStreamInternal(finishedNaturally: Boolean, keepError: Boolean) {
        tickerJob?.cancel()
        tickerJob = null

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
