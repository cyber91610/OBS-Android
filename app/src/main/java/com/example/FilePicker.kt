package com.example

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

object FilePicker {

    fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(nameIndex) ?: ""
                    }
                }
            } catch (_: Exception) {}
        }
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: "video.mp4"
        }
        return name
    }

    fun getVideoDurationSeconds(context: Context, uri: Uri): Long {
        return getVideoMetadata(context, uri).durationSeconds
    }

    fun getVideoMetadata(context: Context, uri: Uri): VideoMetadata {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            retriever.release()

            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val rawW = widthStr?.toIntOrNull() ?: 0
            val rawH = heightStr?.toIntOrNull() ?: 0
            val rot = rotationStr?.toIntOrNull() ?: 0

            val meta = VideoMetadata(
                durationSeconds = durationMs / 1000L,
                rawWidth = rawW,
                rawHeight = rawH,
                rotation = rot
            )
            AppLogManager.s("FilePicker", "Extracted video metadata: ${meta.displayWidth}x${meta.displayHeight} (raw ${rawW}x${rawH}, rot ${rot}°), duration=${meta.durationSeconds}s")
            meta
        } catch (e: Exception) {
            AppLogManager.e("FilePicker", "Failed to extract metadata from video: ${e.message}", e)
            VideoMetadata(0L, 0, 0, 0)
        }
    }

    fun formatTime(seconds: Long): String {
        val totalSecs = if (seconds < 0) 0L else seconds
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
        }
    }
}
