package com.example

import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.decoder.AudioDecoderInterface
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

    init {
        streamClient.setReTries(100)
    }

    fun reTry(delayMs: Long = 3000L, reason: String = "auto_reconnect"): Boolean {
        AppLogManager.i("RTMP", "Triggering streamClient.reTry(delay=${delayMs}ms, reason='$reason')...")
        return streamClient.reTry(delayMs, reason)
    }

    fun reConnect(delayMs: Long = 3000L) {
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
        rtmpClient.sendVideo(h264Buffer, info)
    }

    override fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
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
