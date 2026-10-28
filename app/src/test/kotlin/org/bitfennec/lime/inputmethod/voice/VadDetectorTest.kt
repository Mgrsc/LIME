package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun testVadDetectsLowGainVoiceRecognitionSpeech() {
        var speechStartCount = 0
        var speechEndCount = 0
        val vad = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 10000L,
            silenceTimeoutMs = 15000L,
            minSpeechDurationMs = 150L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {
                    speechStartCount++
                }
                override fun onSpeechEnd() {
                    speechEndCount++
                }
                override fun onSilenceTimeout() {}
            }
        )

        val lowAmbientSilence = FloatArray(512) { 0.00015f }
        for (i in 0 until 15) vad.processFrame(lowAmbientSilence)
        assertEquals(VadDetector.State.SILENCE, vad.getState())

        val lowGainSpeech = FloatArray(512) { 0.00075f }
        for (i in 0 until 10) vad.processFrame(lowGainSpeech)
        assertEquals(VadDetector.State.IN_SPEECH, vad.getState())
        assertEquals(1, speechStartCount)

        for (i in 0 until 23) vad.processFrame(lowAmbientSilence)
        assertEquals(VadDetector.State.SILENCE, vad.getState())
        assertEquals(1, speechEndCount)
    }

    @Test
    fun testVadRecoversFromDigitalSilenceWithoutFalseSpeechLock() {
        val ambientLevels = floatArrayOf(0.00050f, 0.00070f, 0.00080f)
        for (ambient in ambientLevels) {
            var speechStartCount = 0
            var speechEndCount = 0
            val vad = VadDetector(
                minSilenceDurationMs = 700L,
                maxSpeechDurationMs = 10000L,
                silenceTimeoutMs = 30000L,
                minSpeechDurationMs = 200L,
                listener = object : VadDetector.Listener {
                    override fun onSpeechStart() {
                        speechStartCount++
                    }
                    override fun onSpeechEnd() {
                        speechEndCount++
                    }
                    override fun onSilenceTimeout() {}
                }
            )

            val digitalSilence = FloatArray(512) { 0.00001f }
            for (i in 0 until 40) vad.processFrame(digitalSilence)
            assertEquals(VadDetector.INITIAL_NOISE_FLOOR, vad.getNoiseFloor(), 1e-6f)

            val roomAmbient = FloatArray(512) { ambient }
            for (i in 0 until 400) vad.processFrame(roomAmbient)

            assertEquals("Ambient $ambient after digital silence should not trigger speech", 0, speechStartCount)
            assertEquals(0, speechEndCount)
            assertEquals(VadDetector.State.SILENCE, vad.getState())
        }
    }

    @Test
    fun testContinuousLowGainAndWhisperSpeechNotSwallowed() {
        val helper = AudioRecordHelper()

        fun runScenario(ambientShort: Short, speechShort: Short, speechFrames: Int): Int {
            val segmentedSentences = ArrayList<FloatArray>()
            helper.prepareCaptureSession(
                listener = object : AudioRecordHelper.AudioCaptureListener {
                    override fun onAmplitudeUpdate(amplitude: Float) {}
                    override fun onSpeechStart() {}
                    override fun onSentenceFinal(samples: FloatArray) {
                        segmentedSentences.add(samples)
                    }
                    override fun onSilenceTimeout() {}
                },
                notifyAsync = false,
            )

            val ambientFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { ambientShort }
            val speechFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { speechShort }

            for (i in 0 until 62) helper.feedFrameForTest(ambientFrame)
            for (i in 0 until speechFrames) helper.feedFrameForTest(speechFrame)

            val remaining = helper.extractRemainingForStop()
            val segmentedTotal = segmentedSentences.sumOf { it.size }
            return segmentedTotal + (remaining?.size ?: 0)
        }

        val speechFrames6s = 190
        val minExpectedSamples = 180 * AudioRecordHelper.FRAME_SIZE

        val totalSamplesVadWhisper = runScenario(ambientShort = 5, speechShort = 25, speechFrames = speechFrames6s)
        assertTrue(
            "Continuous 6s low-gain speech (0.00076) must not be swallowed: got $totalSamplesVadWhisper",
            totalSamplesVadWhisper >= minExpectedSamples,
        )

        val totalSamplesQuietSubVad = runScenario(ambientShort = 4, speechShort = 14, speechFrames = speechFrames6s)
        assertTrue(
            "Continuous 6s sub-VAD speech (0.00043) after 0.00012 ambient must not be swallowed: got $totalSamplesQuietSubVad",
            totalSamplesQuietSubVad >= minExpectedSamples,
        )

        val totalSamplesHigherAmbientSubVad = runScenario(ambientShort = 9, speechShort = 14, speechFrames = speechFrames6s)
        assertTrue(
            "Continuous 6s sub-VAD speech (0.00043) after 0.00028 ambient must not be swallowed: got $totalSamplesHigherAmbientSubVad",
            totalSamplesHigherAmbientSubVad >= minExpectedSamples,
        )
    }

    @Test
    fun testSentenceClosesInMediumAmbientAfterQuietStart() {
        var speechEndCount = 0
        val vad = VadDetector(
            minSilenceDurationMs = 700L,
            maxSpeechDurationMs = 10000L,
            silenceTimeoutMs = 30000L,
            minSpeechDurationMs = 200L,
            listener = object : VadDetector.Listener {
                override fun onSpeechStart() {}
                override fun onSpeechEnd() {
                    speechEndCount++
                }
                override fun onSilenceTimeout() {}
            }
        )

        // 1. Quiet baseline (RMS ~0.00012)
        val quietFrame = FloatArray(512) { 0.00012f }
        for (i in 0 until 50) vad.processFrame(quietFrame)

        // 2. Normal speech (RMS ~0.0030) for 1s (~31 frames)
        val speechFrame = FloatArray(512) { 0.0030f }
        for (i in 0 until 31) vad.processFrame(speechFrame)
        assertTrue(vad.isInSpeech())

        // 3. Ambient noise (RMS ~0.00045) arrives and speech stops (50 frames ~1.6s)
        val mediumAmbient = FloatArray(512) { 0.00045f }
        for (i in 0 until 50) vad.processFrame(mediumAmbient)

        // Utterance should close cleanly (~1.5s) instead of staying locked for 10s
        assertEquals(1, speechEndCount)
        assertEquals(VadDetector.State.SILENCE, vad.getState())
    }

    @Test
    fun testAudioRecordHelperNormalSentenceTailSilenceDoesNotSwitchToWhisperOrSubmitPostSplitSilence() {
        val helper = AudioRecordHelper()
        val segmentedSentences = ArrayList<FloatArray>()
        val modeChanges = ArrayList<AudioRecordHelper.VoiceCaptureMode>()

        helper.prepareCaptureSession(
            listener = object : AudioRecordHelper.AudioCaptureListener {
                override fun onAmplitudeUpdate(amplitude: Float) {}
                override fun onSpeechStart() {}
                override fun onSentenceFinal(samples: FloatArray) {
                    segmentedSentences.add(samples)
                }
                override fun onSilenceTimeout() {}
                override fun onCaptureModeChanged(mode: AudioRecordHelper.VoiceCaptureMode) {
                    modeChanges.add(mode)
                }
            },
            notifyAsync = false,
        )

        val silenceShortFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 5 }
        val normalSpeechShortFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 500 }

        for (i in 0 until 10) helper.feedFrameForTest(silenceShortFrame)
        for (i in 0 until 20) helper.feedFrameForTest(normalSpeechShortFrame)
        assertEquals(AudioRecordHelper.VoiceCaptureMode.STANDARD, helper.getCaptureMode())

        for (i in 0 until 25) helper.feedFrameForTest(silenceShortFrame)
        assertEquals(1, segmentedSentences.size)
        assertEquals(AudioRecordHelper.VoiceCaptureMode.STANDARD, helper.getCaptureMode())
        assertTrue(modeChanges.none { it == AudioRecordHelper.VoiceCaptureMode.WHISPER })

        for (i in 0 until 20) helper.feedFrameForTest(silenceShortFrame)
        assertNull("Post-split trailing silence must not be submitted on stop", helper.extractRemainingForStop())
    }

    @Test
    fun testLateVadOnsetKeepsEarlierAudibleAudioInSentence() {
        val helper = AudioRecordHelper()
        val sentences = ArrayList<FloatArray>()
        helper.prepareCaptureSession(
            listener = object : AudioRecordHelper.AudioCaptureListener {
                override fun onAmplitudeUpdate(amplitude: Float) {}
                override fun onSpeechStart() {}
                override fun onSentenceFinal(samples: FloatArray) {
                    sentences.add(samples)
                }
                override fun onSilenceTimeout() {}
            },
            notifyAsync = false,
        )

        val earlyAudibleFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 30 }
        val speechFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 500 }
        val silenceFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 5 }
        val earlyFrames = 40

        for (i in 0 until earlyFrames) helper.feedFrameForTest(earlyAudibleFrame)
        for (i in 0 until 15) helper.feedFrameForTest(speechFrame)
        for (i in 0 until 25) helper.feedFrameForTest(silenceFrame)

        assertEquals(1, sentences.size)
        assertTrue(
            "Sentence must keep the audible audio recorded before VAD onset: got ${sentences[0].size}",
            sentences[0].size >= earlyFrames * AudioRecordHelper.FRAME_SIZE,
        )
    }

    @Test
    fun testNormalEnergyLocksOutLaterWhisperDowngrade() {
        val helper = AudioRecordHelper()
        val modeChanges = ArrayList<AudioRecordHelper.VoiceCaptureMode>()

        helper.prepareCaptureSession(
            listener = object : AudioRecordHelper.AudioCaptureListener {
                override fun onAmplitudeUpdate(amplitude: Float) {}
                override fun onSpeechStart() {}
                override fun onSentenceFinal(samples: FloatArray) {}
                override fun onSilenceTimeout() {}
                override fun onCaptureModeChanged(mode: AudioRecordHelper.VoiceCaptureMode) {
                    modeChanges.add(mode)
                }
            },
            notifyAsync = false,
        )

        val silenceShortFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 5 }
        val normalSpeechShortFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 500 }
        val whisperShortFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 26 }

        for (i in 0 until 10) helper.feedFrameForTest(silenceShortFrame)
        for (i in 0 until 20) helper.feedFrameForTest(normalSpeechShortFrame)
        for (i in 0 until 25) helper.feedFrameForTest(silenceShortFrame)
        for (i in 0 until 15) helper.feedFrameForTest(whisperShortFrame)

        assertEquals(AudioRecordHelper.VoiceCaptureMode.STANDARD, helper.getCaptureMode())
        assertTrue(modeChanges.none { it == AudioRecordHelper.VoiceCaptureMode.WHISPER })
    }

    @Test
    fun testAudioRecordHelperWhisperDetectionAndSubVadSessionFallback() {
        val helper = AudioRecordHelper()
        val modeChanges = ArrayList<AudioRecordHelper.VoiceCaptureMode>()

        helper.prepareCaptureSession(
            listener = object : AudioRecordHelper.AudioCaptureListener {
                override fun onAmplitudeUpdate(amplitude: Float) {}
                override fun onSpeechStart() {}
                override fun onSentenceFinal(samples: FloatArray) {}
                override fun onSilenceTimeout() {}
                override fun onCaptureModeChanged(mode: AudioRecordHelper.VoiceCaptureMode) {
                    modeChanges.add(mode)
                }
            },
            notifyAsync = false,
        )

        val silenceShortFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 5 }
        val whisperShortFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 26 }

        for (i in 0 until 15) helper.feedFrameForTest(silenceShortFrame)
        for (i in 0 until 15) helper.feedFrameForTest(whisperShortFrame)

        assertEquals(AudioRecordHelper.VoiceCaptureMode.WHISPER, helper.getCaptureMode())
        assertTrue(modeChanges.contains(AudioRecordHelper.VoiceCaptureMode.WHISPER))
        val remaining = helper.extractRemainingForStop()
        assertNotNull("Whisper utterance should be extracted on stop", remaining)

        helper.prepareCaptureSession(
            listener = object : AudioRecordHelper.AudioCaptureListener {
                override fun onAmplitudeUpdate(amplitude: Float) {}
                override fun onSpeechStart() {}
                override fun onSentenceFinal(samples: FloatArray) {}
                override fun onSilenceTimeout() {}
            },
            notifyAsync = false,
        )
        for (i in 0 until 20) helper.feedFrameForTest(silenceShortFrame)
        assertNull("Pure ambient silence without audible speech must not be extracted", helper.extractRemainingForStop())
    }

    @Test
    fun testPostSentenceAudibleNoiseIsNotSubmittedOnStop() {
        val helper = AudioRecordHelper()
        val sentences = ArrayList<FloatArray>()
        helper.prepareCaptureSession(
            listener = object : AudioRecordHelper.AudioCaptureListener {
                override fun onAmplitudeUpdate(amplitude: Float) {}
                override fun onSpeechStart() {}
                override fun onSentenceFinal(samples: FloatArray) {
                    sentences.add(samples)
                }
                override fun onSilenceTimeout() {}
            },
            notifyAsync = false,
        )

        val silenceFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 5 }
        val speechFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 500 }
        val audibleNoiseFrame = ShortArray(AudioRecordHelper.FRAME_SIZE) { 12 }

        for (i in 0 until 8) helper.feedFrameForTest(silenceFrame)
        for (i in 0 until 20) helper.feedFrameForTest(speechFrame)
        for (i in 0 until 25) helper.feedFrameForTest(silenceFrame)
        assertEquals(1, sentences.size)

        for (i in 0 until 20) helper.feedFrameForTest(audibleNoiseFrame)
        assertNull(helper.extractRemainingForStop())
    }
}
