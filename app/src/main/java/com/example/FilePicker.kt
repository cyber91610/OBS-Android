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
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            durationMs / 1000L
        } catch (_: Exception) {
            0L
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
