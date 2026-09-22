package com.example

import android.net.Uri

enum class StreamStatus(val displayText: String) {
    IDLE("Idle"),
    PREPARING("Preparing"),
    CONNECTING("Connecting"),
    LIVE("Live"),
    FINISHED("Finished"),
    ERROR("Error")
}

enum class StreamOrientation(val label: String, val description: String) {
    AUTO("Auto", "Preserve original video orientation"),
    PORTRAIT("Portrait", "Vertical stream format (9:16)"),
    LANDSCAPE("Landscape", "Horizontal stream format (16:9)")
}

data class VideoMetadata(
    val durationSeconds: Long = 0L,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val rotation: Int = 0 // 0, 90, 180, 270
) {
    val isRotated: Boolean
        get() = rotation == 90 || rotation == 270

    val displayWidth: Int
        get() = if (isRotated) rawHeight else rawWidth

    val displayHeight: Int
        get() = if (isRotated) rawWidth else rawHeight

    val isPortrait: Boolean
        get() = if (displayWidth > 0 && displayHeight > 0) {
            displayHeight > displayWidth
        } else {
            false
        }

    val displayResolution: String
        get() = if (displayWidth > 0 && displayHeight > 0) {
            "${displayWidth}x${displayHeight}"
        } else {
            ""
        }
}

data class UiState(
    val selectedVideoUri: Uri? = null,
    val selectedFileName: String = "",
    val videoMetadata: VideoMetadata = VideoMetadata(),
    val streamKey: String = "",
    val status: StreamStatus = StreamStatus.IDLE,
    val errorMessage: String? = null,
    val elapsedTimeSeconds: Long = 0L,
    val remainingTimeSeconds: Long = 0L,
    val totalDurationSeconds: Long = 0L,
    val isStreaming: Boolean = false,
    val currentBitrate: Long = 0L,
    val isLoopEnabled: Boolean = false,
    val streamOrientation: StreamOrientation = StreamOrientation.AUTO,
    val loopCount: Int = 0,
    val totalStreamElapsedSeconds: Long = 0L
)
