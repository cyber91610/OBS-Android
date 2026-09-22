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

    override fun setVideoCodecImp(codec: VideoCodec) {
        rtmpClient.setVideoCodec(codec)
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        rtmpClient.setAudioCodec(codec)
    }

    override fun getStreamClient(): StreamBaseClient = streamClient

    override fun prepareAudioRtp(isStereo: Boolean, sampleRate: Int) {
        rtmpClient.setAudioInfo(sampleRate, isStereo)
    }

    override fun startStreamRtp(url: String) {
        if (videoEncoder.rotation == 90 || videoEncoder.rotation == 270) {
            rtmpClient.setVideoResolution(videoEncoder.height, videoEncoder.width)
        } else {
            rtmpClient.setVideoResolution(videoEncoder.width, videoEncoder.height)
        }
        rtmpClient.setFps(videoEncoder.fps)
        rtmpClient.connect(url)
    }

    override fun stopStreamRtp() {
        rtmpClient.disconnect()
    }

    override fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
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
        // 1. Initial decoder extraction using base prepareVideo
        val initialOk = super.prepareVideo(context, uri, bitrate, 0)
        if (!initialOk) return false

        // 2. Reconfigure videoEncoder to target Canvas/Output resolution (1920x1080 or 1080x1920)
        try {
            videoEncoder.stop(false)
            val fps = if (videoEncoder.fps > 0) videoEncoder.fps else 30
            val prepared = videoEncoder.prepareVideoEncoder(
                targetWidth,
                targetHeight,
                fps,
                bitrate,
                0,
                2,
                FormatVideoEncoder.SURFACE
            )
            if (!prepared) return false
        } catch (e: Exception) {
            return false
        }

        // 3. Configure GL interface for fit-to-screen scaling without stretching or empty black bars
        (glInterface as? GlStreamInterface)?.let { gl ->
            gl.setEncoderSize(targetWidth, targetHeight)
            gl.setPreviewResolution(targetWidth, targetHeight)
            gl.setIsPortrait(isPortrait)
            gl.forceOrientation(if (isPortrait) OrientationForced.PORTRAIT else OrientationForced.LANDSCAPE)
            gl.setAspectRatioMode(AspectRatioMode.Fill)
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
