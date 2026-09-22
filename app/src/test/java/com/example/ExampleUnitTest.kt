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
  }
}
