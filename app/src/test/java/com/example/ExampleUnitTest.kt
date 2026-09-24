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
    assertEquals("Reconnecting", StreamStatus.RECONNECTING.displayText)
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
    assertEquals(StreamResolutionProfile.LANDSCAPE, state.streamProfile)
    assertEquals("1920x1080", state.activeBaseResolution)
    assertEquals("1920x1080", state.activeOutputResolution)
    assertEquals("16:9", state.activeAspectRatio)
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
  fun testStreamResolutionProfiles() {
    // Landscape Profile (Standard 16:9 Aspect Ratio)
    val landscape = StreamResolutionProfile.LANDSCAPE
    assertEquals(1920, landscape.baseCanvasWidth)
    assertEquals(1080, landscape.baseCanvasHeight)
    assertEquals(1920, landscape.outputScaledWidth)
    assertEquals(1080, landscape.outputScaledHeight)
    assertEquals("1920x1080", landscape.baseResolution)
    assertEquals("1920x1080", landscape.outputResolution)
    assertEquals("16:9", landscape.aspectRatio)
    assertEquals(false, landscape.isPortrait)

    // Portrait Profile (Shorts 9:16 Aspect Ratio)
    val portrait = StreamResolutionProfile.PORTRAIT
    assertEquals(1080, portrait.baseCanvasWidth)
    assertEquals(1920, portrait.baseCanvasHeight)
    assertEquals(1080, portrait.outputScaledWidth)
    assertEquals(1920, portrait.outputScaledHeight)
    assertEquals("1080x1920", portrait.baseResolution)
    assertEquals("1080x1920", portrait.outputResolution)
    assertEquals("9:16", portrait.aspectRatio)
    assertEquals(true, portrait.isPortrait)
  }

  @Test
  fun testAppLogManagerOperations() {
    AppLogManager.clearLogs()
    assertEquals(0, AppLogManager.logs.value.size)

    AppLogManager.i("TestTag", "Info message")
    AppLogManager.w("TestTag", "Warning message")
    AppLogManager.e("TestTag", "Error message", RuntimeException("Simulated exception"))
    AppLogManager.s("TestTag", "Success message")

    val currentLogs = AppLogManager.logs.value
    assertEquals(4, currentLogs.size)

    assertEquals(LogLevel.INFO, currentLogs[0].level)
    assertEquals("Info message", currentLogs[0].message)

    assertEquals(LogLevel.WARN, currentLogs[1].level)
    assertEquals("Warning message", currentLogs[1].message)

    assertEquals(LogLevel.ERROR, currentLogs[2].level)
    assertEquals("Error message", currentLogs[2].message)
    assertNotNull(currentLogs[2].details)

    assertEquals(LogLevel.SUCCESS, currentLogs[3].level)
    assertEquals("Success message", currentLogs[3].message)

    val exportedText = AppLogManager.getAllLogsAsText()
    org.junit.Assert.assertTrue(exportedText.contains("[INFO] [TestTag] Info message"))
    org.junit.Assert.assertTrue(exportedText.contains("[WARN] [TestTag] Warning message"))
    org.junit.Assert.assertTrue(exportedText.contains("[ERROR] [TestTag] Error message"))
    org.junit.Assert.assertTrue(exportedText.contains("[SUCCESS] [TestTag] Success message"))

    // Test clear logs
    AppLogManager.clearLogs()
    assertEquals(0, AppLogManager.logs.value.size)
    assertEquals("No logs recorded.", AppLogManager.getAllLogsAsText())
  }
}
