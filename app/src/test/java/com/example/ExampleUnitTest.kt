package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun testTimeFormatting() {
    assertEquals("00:00", FilePicker.formatTime(0))
    assertEquals("00:45", FilePicker.formatTime(45))
    assertEquals("01:30", FilePicker.formatTime(90))
    assertEquals("01:00:00", FilePicker.formatTime(3600))
    assertEquals("01:02:03", FilePicker.formatTime(3723))
  }

  @Test
  fun testStreamStatusValues() {
    assertEquals("Idle", StreamStatus.IDLE.displayText)
    assertEquals("Preparing", StreamStatus.PREPARING.displayText)
    assertEquals("Connecting", StreamStatus.CONNECTING.displayText)
    assertEquals("Live", StreamStatus.LIVE.displayText)
    assertEquals("Finished", StreamStatus.FINISHED.displayText)
    assertEquals("Error", StreamStatus.ERROR.displayText)
  }

  @Test
  fun testInitialUiState() {
    val state = UiState()
    assertEquals(StreamStatus.IDLE, state.status)
    assertEquals("", state.streamKey)
    assertEquals("", state.selectedFileName)
    assertEquals(0L, state.elapsedTimeSeconds)
    assertEquals(false, state.isStreaming)
    assertEquals(false, state.isLoopEnabled)
    assertEquals(StreamOrientation.AUTO, state.streamOrientation)
    assertEquals(0, state.loopCount)
    assertEquals(0L, state.totalStreamElapsedSeconds)
  }

  @Test
  fun testVideoMetadataOrientation() {
    // Phone camera recorded video in portrait (raw 1920x1080 with 90 deg rotation)
    val phonePortrait = VideoMetadata(
      durationSeconds = 600,
      rawWidth = 1920,
      rawHeight = 1080,
      rotation = 90
    )
    assertEquals(1080, phonePortrait.displayWidth)
    assertEquals(1920, phonePortrait.displayHeight)
    assertEquals(true, phonePortrait.isPortrait)
    assertEquals("1080x1920", phonePortrait.displayResolution)

    // Standard landscape video (1920x1080 with 0 deg rotation)
    val landscapeVideo = VideoMetadata(
      durationSeconds = 600,
      rawWidth = 1920,
      rawHeight = 1080,
      rotation = 0
    )
    assertEquals(1920, landscapeVideo.displayWidth)
    assertEquals(1080, landscapeVideo.displayHeight)
    assertEquals(false, landscapeVideo.isPortrait)
    assertEquals("1920x1080", landscapeVideo.displayResolution)

    // Exported vertical video (1080x1920 with 0 deg rotation)
    val verticalVideo = VideoMetadata(
      durationSeconds = 600,
      rawWidth = 1080,
      rawHeight = 1920,
      rotation = 0
    )
    assertEquals(1080, verticalVideo.displayWidth)
    assertEquals(1920, verticalVideo.displayHeight)
    assertEquals(true, verticalVideo.isPortrait)
    assertEquals("1080x1920", verticalVideo.displayResolution)
  }

  @Test
  fun testStreamOrientations() {
    assertEquals("Auto", StreamOrientation.AUTO.label)
    assertEquals("Portrait", StreamOrientation.PORTRAIT.label)
    assertEquals("Landscape", StreamOrientation.LANDSCAPE.label)
  }
}
