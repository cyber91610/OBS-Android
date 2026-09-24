package com.example

import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.decoder.AudioDecoder
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.BaseDecoder
import com.pedro.encoder.input.decoder.VideoDecoder
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.library.base.FromFileBase
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.library.view.GlStreamInterface
import com.pedro.library.view.OrientationForced
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom implementation extending [FromFileBase] allowing exact control over
 * Canvas (Base) and Output (Scaled) resolutions (1920x1080 Landscape, 1080x1920 Portrait)
 * and OpenGL Fit-To-Screen aspect ratio scaling without letterboxing/pillarboxing.
 */
class CustomRtmpFromFile(
    context: Context,
    connectChecker: ConnectChecker,
    videoDecoderInterface: VideoDecoderInterface,
    audioDecoderInterface: AudioDecoderInterface
) : FromFileBase(context, videoDecoderInterface, audioDecoderInterface) {

    private val rtmpClient = RtmpClient(connectChecker)
    private val streamClient = RtmpStreamClient(rtmpClient, object : StreamClientListener {
        override fun onRequestKeyframe() {
            requestKeyFrame()
        }
    })

    private var pauseStartTimeNs = 0L
    @Volatile
    private var decodersPaused = false
    @Volatile
    private var isAfterReconnect = false
    private var lastSentVideoTimestampMs = 0L
    private var lastSentAudioTimestampMs = 0L

    fun markReconnect() {
        isAfterReconnect = true
        AppLogManager.i("StreamManager", "Marked CustomRtmpFromFile for timestamp continuity after reconnect.")
    }

    private fun captureLastTimestamps() {
        try {
            val rtmpSenderField = rtmpClient::class.java.declaredFields.firstOrNull { 
                it.name.contains("sender", ignoreCase = true) || it.type.name.contains("Sender") 
            } ?: return
            rtmpSenderField.isAccessible = true
            val rtmpSender = rtmpSenderField.get(rtmpClient) ?: return
            val senderClass = rtmpSender::class.java

            for (field in senderClass.declaredFields) {
                field.isAccessible = true
                val name = field.name.lowercase()
                if (field.type == Long::class.java || field.type == Long::class.javaPrimitiveType) {
                    val valLong = field.getLong(rtmpSender)
                    if (name.contains("videotimestamp") || name.equals("videotimestamp", ignoreCase = true)) {
                        lastSentVideoTimestampMs = valLong
                    } else if (name.contains("audiotimestamp") || name.equals("audiotimestamp", ignoreCase = true)) {
                        lastSentAudioTimestampMs = valLong
                    }
                }
            }
            AppLogManager.i("RtmpTimestampFix", "Captured last sent timestamps before disconnect: video=${lastSentVideoTimestampMs}ms, audio=${lastSentAudioTimestampMs}ms")
        } catch (e: Exception) {
            AppLogManager.w("RtmpTimestampFix", "Error capturing last timestamps: ${e.message}")
        }
    }

    private fun adjustRtmpSenderTimestamp(isAudio: Boolean, firstPtsUs: Long) {
        try {
            val rtmpSenderField = rtmpClient::class.java.declaredFields.firstOrNull { 
                it.name.contains("sender", ignoreCase = true) || it.type.name.contains("Sender") 
            } ?: return
            rtmpSenderField.isAccessible = true
            val rtmpSender = rtmpSenderField.get(rtmpClient) ?: return

            val senderClass = rtmpSender::class.java
            val startTsFieldName = if (isAudio) "startAudioTimestamp" else "startVideoTimestamp"
            val lastTsMs = if (isAudio) lastSentAudioTimestampMs else lastSentVideoTimestampMs
            val intervalMs = if (isAudio) 23L else 33L // standard frame interval

            val targetStartTs = firstPtsUs - ((lastTsMs + intervalMs) * 1000L)

            for (field in senderClass.declaredFields) {
                field.isAccessible = true
                val name = field.name
                if (name.equals(startTsFieldName, ignoreCase = true) || (name.contains(if (isAudio) "audio" else "video", ignoreCase = true) && name.contains("start", ignoreCase = true))) {
                    if (field.type == Long::class.java || field.type == Long::class.javaPrimitiveType) {
                        field.setLong(rtmpSender, targetStartTs)
                        AppLogManager.s("RtmpTimestampFix", "Successfully adjusted ${field.name} to $targetStartTs (firstPts=$firstPtsUs, lastTsMs=$lastTsMs)")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogManager.e("RtmpTimestampFix", "Failed to adjust RtmpSender timestamp: ${e.message}", e)
        }
    }

    private val videoDecoderField by lazy {
        try {
            FromFileBase::class.java.getDeclaredField("videoDecoder").apply { isAccessible = true }
        } catch (_: Exception) { null }
    }

    private val audioDecoderField by lazy {
        try {
            FromFileBase::class.java.getDeclaredField("audioDecoder").apply { isAccessible = true }
        } catch (_: Exception) { null }
    }

    private val baseDecoderPauseField by lazy {
        try {
            BaseDecoder::class.java.getDeclaredField("pause").apply { isAccessible = true }
        } catch (_: Exception) { null }
    }

    private val baseDecoderSyncField by lazy {
        try {
            BaseDecoder::class.java.getDeclaredField("sync").apply { isAccessible = true }
        } catch (_: Exception) { null }
    }

    private val baseDecoderStartTsField by lazy {
        try {
            BaseDecoder::class.java.getDeclaredField("startTs").apply { isAccessible = true }
        } catch (_: Exception) { null }
    }

    init {
        streamClient.setReTries(100)
    }

    fun isDecodersPaused(): Boolean = decodersPaused

    fun pauseDecoders() {
        if (decodersPaused) return
        decodersPaused = true
        pauseStartTimeNs = System.nanoTime()
        captureLastTimestamps()
        AppLogManager.i("StreamManager", "Pausing MP4 file decoders during disconnect to prevent A/V buffer misalignment...")
        try {
            val vDec = videoDecoderField?.get(this) as? VideoDecoder
            vDec?.pauseRender()

            val aDec = audioDecoderField?.get(this) as? AudioDecoder
            if (aDec != null) {
                val sync = baseDecoderSyncField?.get(aDec)
                val pause = baseDecoderPauseField?.get(aDec) as? AtomicBoolean
                if (sync != null && pause != null) {
                    synchronized(sync) {
                        pause.set(true)
                    }
                }
            }
            AppLogManager.s("StreamManager", "File decoders paused (video=${String.format(java.util.Locale.US, "%.1f", videoTime)}s, audio=${String.format(java.util.Locale.US, "%.1f", audioTime)}s).")
        } catch (e: Exception) {
            AppLogManager.w("StreamManager", "Error pausing decoders: ${e.message}")
        }
    }

    fun resumeDecoders() {
        if (!decodersPaused && pauseStartTimeNs == 0L) return
        val pauseDurationMicros = if (pauseStartTimeNs > 0L) (System.nanoTime() - pauseStartTimeNs) / 1000L else 0L
        decodersPaused = false
        pauseStartTimeNs = 0L
        AppLogManager.i("StreamManager", "Resuming MP4 file decoders after reconnect (network down for ${pauseDurationMicros / 1000L}ms)...")
        try {
            val vDec = videoDecoderField?.get(this) as? VideoDecoder
            val aDec = audioDecoderField?.get(this) as? AudioDecoder

            if (pauseDurationMicros > 0L) {
                adjustStartTs(vDec, pauseDurationMicros)
                adjustStartTs(aDec, pauseDurationMicros)
            }

            vDec?.resumeRender()

            if (aDec != null) {
                val sync = baseDecoderSyncField?.get(aDec)
                val pause = baseDecoderPauseField?.get(aDec) as? AtomicBoolean
                if (sync != null && pause != null) {
                    synchronized(sync) {
                        pause.set(false)
                    }
                }
            }

            // Resynchronize audio decoder to video timeline
            try {
                reSyncFile()
            } catch (_: Exception) {}

            requestKeyFrame()
            AppLogManager.s("StreamManager", "File decoders resumed and A/V sync restored at video=${String.format(java.util.Locale.US, "%.1f", videoTime)}s.")
        } catch (e: Exception) {
            AppLogManager.w("StreamManager", "Error resuming decoders: ${e.message}")
        }
    }

    private fun adjustStartTs(decoder: Any?, deltaMicros: Long) {
        if (decoder == null || deltaMicros <= 0L) return
        try {
            val startTsField = baseDecoderStartTsField ?: return
            val currentStartTs = startTsField.getLong(decoder)
            if (currentStartTs > 0L) {
                startTsField.setLong(decoder, currentStartTs + deltaMicros)
            }
        } catch (e: Exception) {
            AppLogManager.d("StreamManager", "adjustStartTs ignored: ${e.message}")
        }
    }

    fun reTry(delayMs: Long = 1000L, reason: String = "auto_reconnect"): Boolean {
        AppLogManager.i("RTMP", "Triggering streamClient.reTry(delay=${delayMs}ms, reason='$reason')...")
        return streamClient.reTry(delayMs, reason)
    }

    fun reConnect(delayMs: Long = 1000L) {
        AppLogManager.i("RTMP", "Directly triggering rtmpClient.reConnect(delay=${delayMs}ms) while keeping decoders active...")
        requestKeyFrame()
        rtmpClient.reConnect(delayMs)
    }

    fun resetReTries(count: Int = 100) {
        streamClient.setReTries(count)
    }

    override fun setVideoCodecImp(codec: VideoCodec) {
        rtmpClient.setVideoCodec(codec)
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        rtmpClient.setAudioCodec(codec)
    }

    override fun getStreamClient(): StreamBaseClient = streamClient

    override fun prepareAudioRtp(isStereo: Boolean, sampleRate: Int) {
        AppLogManager.i("AudioEncoder", "Audio RTP prepared: sampleRate=${sampleRate}Hz, isStereo=$isStereo")
        rtmpClient.setAudioInfo(sampleRate, isStereo)
    }

    override fun startStreamRtp(url: String) {
        val width = if (videoEncoder.rotation == 90 || videoEncoder.rotation == 270) videoEncoder.height else videoEncoder.width
        val height = if (videoEncoder.rotation == 90 || videoEncoder.rotation == 270) videoEncoder.width else videoEncoder.height
        val maskedUrl = if (url.contains("/")) url.substringBeforeLast("/") + "/***KEY" else url
        AppLogManager.i("RTMP", "Starting RTP to $maskedUrl with resolution ${width}x${height} @ ${videoEncoder.fps} fps")
        rtmpClient.setVideoResolution(width, height)
        rtmpClient.setFps(videoEncoder.fps)
        rtmpClient.connect(url)
    }

    override fun stopStreamRtp() {
        AppLogManager.i("RTMP", "Stopping RTMP client connection...")
        rtmpClient.disconnect()
    }

    override fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
        AppLogManager.d("VideoEncoder", "H.264 SPS/PPS parameters received and configured.")
        rtmpClient.setVideoInfo(sps, pps, vps)
    }

    override fun getH264DataRtp(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (isAfterReconnect) {
            try {
                adjustRtmpSenderTimestamp(isAudio = false, firstPtsUs = info.presentationTimeUs)
            } catch (e: Exception) {
                AppLogManager.w("RtmpTimestampFix", "Error adjusting video start timestamp: ${e.message}")
            }
        }
        rtmpClient.sendVideo(h264Buffer, info)
    }

    override fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (isAfterReconnect) {
            isAfterReconnect = false // Reset after both video and audio first packets are adjusted
            try {
                adjustRtmpSenderTimestamp(isAudio = true, firstPtsUs = info.presentationTimeUs)
            } catch (e: Exception) {
                AppLogManager.w("RtmpTimestampFix", "Error adjusting audio start timestamp: ${e.message}")
            }
        }
        rtmpClient.sendAudio(aacBuffer, info)
    }

    /**
     * Prepares video with the explicit Canvas and Output resolution profile.
     * Reconfigures [videoEncoder] to target resolution and sets OpenGL [GlStreamInterface]
     * to Fit-to-screen mode ([AspectRatioMode.Fill]).
     */
    fun prepareVideoWithProfile(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        bitrate: Int,
        isPortrait: Boolean
    ): Boolean {
        AppLogManager.i("VideoDecoder", "Starting prepareVideoWithProfile: target=${targetWidth}x${targetHeight}, bitrate=${bitrate / 1000}kbps, isPortrait=$isPortrait")
        // 1. Initial decoder extraction using base prepareVideo
        val initialOk = super.prepareVideo(context, uri, bitrate, 0)
        if (!initialOk) {
            AppLogManager.e("VideoDecoder", "Initial FromFileBase.prepareVideo failed! Video file track cannot be extracted or parsed.")
            return false
        }
        AppLogManager.s("VideoDecoder", "Base video decoder initialized: duration=${videoDuration}s")

        // 2. Reconfigure videoEncoder to target Canvas/Output resolution (1920x1080 or 1080x1920)
        try {
            videoEncoder.stop(false)
            val fps = if (videoEncoder.fps > 0) videoEncoder.fps else 30
            AppLogManager.i("VideoEncoder", "Preparing VideoEncoder: ${targetWidth}x${targetHeight} @ ${fps}fps, format=SURFACE")
            val prepared = videoEncoder.prepareVideoEncoder(
                targetWidth,
                targetHeight,
                fps,
                bitrate,
                0,
                2,
                FormatVideoEncoder.SURFACE
            )
            if (!prepared) {
                AppLogManager.e("VideoEncoder", "videoEncoder.prepareVideoEncoder returned false for ${targetWidth}x${targetHeight}. Codec rejected configuration.")
                return false
            }
            AppLogManager.s("VideoEncoder", "VideoEncoder successfully configured for ${targetWidth}x${targetHeight} @ ${fps}fps")
        } catch (e: Exception) {
            AppLogManager.e("VideoEncoder", "Exception configuring VideoEncoder: ${e.message}", e)
            return false
        }

        // 3. Configure GL interface for fit-to-screen scaling without stretching or empty black bars
        val gl = glInterface as? GlStreamInterface
        if (gl != null) {
            gl.setEncoderSize(targetWidth, targetHeight)
            gl.setPreviewResolution(targetWidth, targetHeight)
            gl.setIsPortrait(isPortrait)
            gl.forceOrientation(if (isPortrait) OrientationForced.PORTRAIT else OrientationForced.LANDSCAPE)
            gl.setAspectRatioMode(AspectRatioMode.Fill)
            AppLogManager.s("OpenGL", "GL pipeline configured: ${targetWidth}x${targetHeight}, OrientationForced=${if (isPortrait) "PORTRAIT" else "LANDSCAPE"}, AspectRatioMode.Fill")
        } else {
            AppLogManager.w("OpenGL", "GlStreamInterface not available; falling back to default renderer.")
        }

        return true
    }

    override fun startStream(endpoint: String) {
        super.startStream(endpoint)
        // Enforce GL surface parameters after base encoders start
        val isPortrait = videoEncoder.height > videoEncoder.width
        (glInterface as? GlStreamInterface)?.let { gl ->
            gl.setEncoderSize(videoEncoder.width, videoEncoder.height)
            gl.setPreviewResolution(videoEncoder.width, videoEncoder.height)
            gl.setIsPortrait(isPortrait)
            gl.forceOrientation(if (isPortrait) OrientationForced.PORTRAIT else OrientationForced.LANDSCAPE)
            gl.setAspectRatioMode(AspectRatioMode.Fill)
        }
    }
}
