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

data class UiState(
    val selectedVideoUri: Uri? = null,
    val selectedFileName: String = "",
    val streamKey: String = "",
    val status: StreamStatus = StreamStatus.IDLE,
    val errorMessage: String? = null,
    val elapsedTimeSeconds: Long = 0L,
    val remainingTimeSeconds: Long = 0L,
    val totalDurationSeconds: Long = 0L,
    val isStreaming: Boolean = false,
    val currentBitrate: Long = 0L
)
