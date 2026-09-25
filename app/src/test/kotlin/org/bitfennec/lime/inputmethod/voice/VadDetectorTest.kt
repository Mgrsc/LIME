package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadDetectorTest {

    @Test
    fun testVadStateTransitionsAndEndpoints() {
        var speechStartCount = 0
        var speechEndCount = 0
        var silenceTimeoutCount = 0

        val vad = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 3000L,
            silenceTimeoutMs = 5000L,
            minSpeechDurationMs = 100L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {
                    speechStartCount++
                }

                override fun onSpeechEnd() {
                    speechEndCount++
                }

                override fun onSilenceTimeout() {
                    silenceTimeoutCount++
                }
            }
        )

        val silenceFrame = FloatArray(512) { 0.001f }
        val loudSpeechFrame = FloatArray(512) { 0.25f }

        // 1. Silence frames maintain SILENCE
        for (i in 0 until 10) {
            vad.processFrame(silenceFrame)
        }
        assertEquals(VadDetector.State.SILENCE, vad.getState())
        assertEquals(0, speechStartCount)

        // 2. 6 consecutive speech frames trigger onSpeechStart (~192ms)
        for (i in 0 until 6) {
            vad.processFrame(loudSpeechFrame)
        }
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
        assertEquals(1, speechStartCount)
        assertTrue(vad.isInSpeech())

        // 3. Sustained speech (stays IN_SPEECH)
        for (i in 0 until 5) {
            vad.processFrame(loudSpeechFrame)
        }
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
        assertEquals(0, speechEndCount)

        // 4. Silence exceeding 700ms (~22 frames)
        for (i in 0 until 24) {
            vad.processFrame(silenceFrame)
        }
        assertEquals(VadDetector.State.SILENCE, vad.getState())
        assertEquals(1, speechEndCount)
        assertFalse(vad.isInSpeech())

        // 5. Resume speech and test forceSpeechEnd
        for (i in 0 until 6) {
            vad.processFrame(loudSpeechFrame)
        }
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
        assertEquals(2, speechStartCount)

        val forced = vad.forceSpeechEnd()
        assertTrue(forced)
        assertEquals(VadDetector.State.SILENCE, vad.getState())
        assertEquals(2, speechEndCount)
    }

    @Test
    fun testVadMaxSpeechDurationForcedSplit() {
        var speechEndCount = 0

        val vad = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 1000L, // 1s forced segmentation
            silenceTimeoutMs = 10000L,
            minSpeechDurationMs = 100L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {}
                override fun onSpeechEnd() {
                    speechEndCount++
                }
                override fun onSilenceTimeout() {}
            }
        )

        val loudSpeechFrame = FloatArray(512) { 0.3f }

        // 6 frames enter speech state (~192ms)
        for (i in 0 until 6) {
            vad.processFrame(loudSpeechFrame)
        }
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())

        // Sustained speech reaches 1000ms
        for (i in 0 until 26) {
            vad.processFrame(loudSpeechFrame)
        }

        // Forced endpoint emits onSpeechEnd, transitions to SILENCE
        assertEquals(1, speechEndCount)
        assertEquals(VadDetector.State.SILENCE, vad.getState())

        // 6 consecutive speech frames resume next utterance
        for (i in 0 until 6) {
            vad.processFrame(loudSpeechFrame)
        }
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
    }

    @Test
    fun testVadNoiseFloorTracking() {
        val vad = VadDetector(
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {}
                override fun onSpeechEnd() {}
                override fun onSilenceTimeout() {}
            }
        )
        assertTrue(vad.getNoiseFloor() > 0f)
        val silenceFrame = FloatArray(512) { 0.002f }
        for (i in 0 until 20) {
            vad.processFrame(silenceFrame)
        }
        assertTrue(vad.getNoiseFloor() < 0.01f)
    }

    @Test
    fun testVadCurrentTailSilenceAndMultiSentenceIsolation() {
        var speechEndCount = 0
        val vad = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 10000L,
            silenceTimeoutMs = 15000L,
            minSpeechDurationMs = 100L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {}
                override fun onSpeechEnd() {
                    speechEndCount++
                }
                override fun onSilenceTimeout() {}
            }
        )

        val loudFrame = FloatArray(512) { 0.3f }
        val silentFrame = FloatArray(512) { 0.001f }

        // Initial silence state
        assertEquals(0L, vad.getCurrentTailSilenceMs())
        assertEquals(0L, vad.getLastSentenceTailSilenceMs())

        // Utterance 1: 6 frames enter speech state (~192ms)
        for (i in 0 until 6) vad.processFrame(loudFrame)
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
        assertEquals(0L, vad.getCurrentTailSilenceMs())

        // Utterance 1: 22 silence frames (~704ms) trigger endpoint
        for (i in 0 until 22) vad.processFrame(silentFrame)
        assertEquals(1, speechEndCount)
        assertEquals(VadDetector.State.SILENCE, vad.getState())
        assertTrue("Sentence 1 tail silence should be >= 700ms", vad.getLastSentenceTailSilenceMs() >= 700L)
        assertEquals("When in SILENCE state, current tail silence must be 0", 0L, vad.getCurrentTailSilenceMs())

        // Utterance 2: user begins next utterance (6 frames onset)
        for (i in 0 until 6) vad.processFrame(loudFrame)
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
        // Utterance 1 tail silence preserved in lastSentenceTailSilenceMs while current utterance resets to 0
        assertTrue(vad.getLastSentenceTailSilenceMs() >= 700L)
        assertEquals(0L, vad.getCurrentTailSilenceMs())

        // Utterance 2 brief pause of 2 frames (64ms)
        vad.processFrame(silentFrame)
        vad.processFrame(silentFrame)
        assertEquals(64L, vad.getCurrentTailSilenceMs())

        // Utterance 2 speech resumes, silence counter resets
        vad.processFrame(loudFrame)
        assertEquals(0L, vad.getCurrentTailSilenceMs())
    }

    @Test
    fun testVadDetectsQuietSpeechAndWhisper() {
        var speechStartCount = 0
        val vad = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 10000L,
            silenceTimeoutMs = 15000L,
            minSpeechDurationMs = 100L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {
                    speechStartCount++
                }
                override fun onSpeechEnd() {}
                override fun onSilenceTimeout() {}
            }
        )

        // Ambient noise floor tracking (RMS ~0.0010) -> threshold = coerceIn(2.2 * noise, 0.0016, 0.010) = 0.0022
        val ambientNoiseFrame = FloatArray(512) { 0.001f }
        for (i in 0 until 15) vad.processFrame(ambientNoiseFrame)
        assertEquals(VadDetector.State.SILENCE, vad.getState())
        assertEquals(0, speechStartCount)

        // Quiet speech / whisper frame (RMS 0.0040f >= threshold 0.0022f)
        val quietSpeechFrame = FloatArray(512) { 0.0040f }
        for (i in 0 until 6) vad.processFrame(quietSpeechFrame)

        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
        assertEquals(1, speechStartCount)
    }
}
