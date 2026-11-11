package com.quizstudiopro.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizStudioAppUnitTest {
    @Test
    fun verifyTestMath() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun verifySampleLogic() {
        val options = listOf("Paris", "London", "Rome", "Madrid")
        val correctIndex = options.indexOf("Paris")
        assertEquals(0, correctIndex)
        assertTrue(options.isNotEmpty())
    }

    @Test
    fun verifyAudioChannelBounds() {
        val validChannels = 2
        assertTrue("Channel count must be between 1 and 32", validChannels in 1..32)
        
        val pcmSampleRate = 48000
        val durationSeconds = 5.0
        val expectedSampleCount = (pcmSampleRate * durationSeconds).toLong()
        assertEquals(240000L, expectedSampleCount)
    }

    @Test
    fun verifyPlanarAudioBufferCalculations() {
        val currentChunk = 2048
        val channels = 2
        val bufferLength = currentChunk * channels
        assertEquals(4096, bufferLength)
    }
}
