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

enum class StreamResolutionProfile(
    val title: String,
    val baseCanvasWidth: Int,
    val baseCanvasHeight: Int,
    val outputScaledWidth: Int,
    val outputScaledHeight: Int,
    val aspectRatio: String,
    val description: String
) {
    LANDSCAPE(
        title = "Landscape Mode",
        baseCanvasWidth = 1920,
        baseCanvasHeight = 1080,
        outputScaledWidth = 1920,
        outputScaledHeight = 1080,
        aspectRatio = "16:9",
        description = "Standard 16:9 Landscape (1920x1080) for desktop & widescreen displays"
    ),
    PORTRAIT(
        title = "Portrait Mode",
        baseCanvasWidth = 1080,
        baseCanvasHeight = 1920,
        outputScaledWidth = 1080,
        outputScaledHeight = 1920,
        aspectRatio = "9:16",
        description = "Shorts 9:16 Portrait (1080x1920) for YouTube Shorts & mobile feeds"
    );

    val isPortrait: Boolean
        get() = outputScaledHeight > outputScaledWidth

    val baseResolution: String
        get() = "${baseCanvasWidth}x${baseCanvasHeight}"

    val outputResolution: String
        get() = "${outputScaledWidth}x${outputScaledHeight}"
}

// Deprecated alias maintained for backwards compatibility
typealias StreamOrientation = StreamResolutionProfile

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
    val streamProfile: StreamResolutionProfile = StreamResolutionProfile.LANDSCAPE,
    val loopCount: Int = 0,
    val totalStreamElapsedSeconds: Long = 0L
) {
    val streamOrientation: StreamResolutionProfile
        get() = streamProfile

    val baseCanvasWidth: Int
        get() = streamProfile.baseCanvasWidth

    val baseCanvasHeight: Int
        get() = streamProfile.baseCanvasHeight

    val outputScaledWidth: Int
        get() = streamProfile.outputScaledWidth

    val outputScaledHeight: Int
        get() = streamProfile.outputScaledHeight

    val activeBaseResolution: String
        get() = streamProfile.baseResolution

    val activeOutputResolution: String
        get() = streamProfile.outputResolution

    val activeAspectRatio: String
        get() = streamProfile.aspectRatio

    val scalingTransform: String
        get() = "Fit to Screen"
}
