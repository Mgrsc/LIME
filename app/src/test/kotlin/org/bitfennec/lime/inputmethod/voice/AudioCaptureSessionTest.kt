package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioCaptureSessionTest {

    private class CaptureSession(onFinal: (FloatArray) -> Unit = {}) {
        val helper = AudioRecordHelper().apply { forceStandard = true }
        val sentences = ArrayList<FloatArray>()

        init {
            helper.prepareCaptureSession(
                listener = object : AudioRecordHelper.AudioCaptureListener {
                    override fun onAmplitudeUpdate(amplitude: Float) {}
                    override fun onSpeechStart() {}
                    override fun onSentenceFinal(samples: FloatArray) {
                        sentences.add(samples)
                        onFinal(samples)
                    }
                    override fun onSilenceTimeout() {}
                },
            )
        }

        fun feed(amplitude: Short, frames: Int) {
            val frame = ShortArray(AudioRecordHelper.FRAME_SIZE) { amplitude }
            repeat(frames) { helper.feedFrameForTest(frame) }
        }

        fun finish(): List<FloatArray> = sentences.toList() +
            listOfNotNull(helper.extractRemainingForStop())
    }

    @Test
    fun shortSpeechIsPreservedWhenReleasedImmediately() {
        val session = CaptureSession()
        session.feed(4, 2)
        session.feed(160, 5)

        val remaining = session.helper.extractRemainingForStop()

        assertNotNull(remaining)
        assertEquals(5 * AudioRecordHelper.FRAME_SIZE, remaining!!.count { it == 160 / 32768f })
        assertTrue(remaining.size < AudioRecordHelper.SAMPLE_RATE * 400 / 1000)
    }

    @Test
    fun isolatedTransientIsNotSubmittedOnStop() {
        val session = CaptureSession()
        session.feed(4, 20)
        session.feed(160, 1)

        assertNull(session.helper.extractRemainingForStop())
    }

    @Test
    fun quietUtteranceAfterCompletedSentenceIsPreserved() {
        val session = CaptureSession()
        session.feed(4, 62)
        session.feed(160, 31)
        session.feed(4, 25)
        assertEquals(1, session.sentences.size)

        session.feed(14, 31)
        val remaining = session.helper.extractRemainingForStop()

        assertNotNull(remaining)
        assertEquals(31 * AudioRecordHelper.FRAME_SIZE, remaining!!.count { it == 14 / 32768f })
    }

    @Test
    fun longQuietUtteranceIsSegmentedWithoutDroppingOrRepeatingSamples() {
        val session = CaptureSession()
        session.feed(4, 62)
        session.feed(14, 625)

        val delivered = session.finish()

        assertTrue(session.sentences.isNotEmpty())
        assertEquals(
            625 * AudioRecordHelper.FRAME_SIZE,
            delivered.sumOf { samples -> samples.count { it == 14 / 32768f } },
        )
        assertTrue(delivered.all { it.size <= AudioRecordHelper.SAMPLE_RATE * 15 })
    }

    @Test
    fun quietUtterancesSeparatedBySilenceAreDeliveredIndependently() {
        val session = CaptureSession()
        session.feed(4, 62)
        session.feed(14, 31)
        session.feed(4, 25)
        session.feed(15, 31)

        val delivered = session.finish()

        assertEquals(2, delivered.size)
        assertEquals(31 * AudioRecordHelper.FRAME_SIZE, delivered[0].count { it == 14 / 32768f })
        assertEquals(0, delivered[0].count { it == 15 / 32768f })
        assertEquals(31 * AudioRecordHelper.FRAME_SIZE, delivered[1].count { it == 15 / 32768f })
    }

    @Test
    fun shortContinuationAfterForcedQuietSplitIsPreserved() {
        val session = CaptureSession()
        session.feed(4, 62)
        session.feed(14, 305)

        val delivered = session.finish()

        assertTrue(session.sentences.isNotEmpty())
        assertEquals(
            305 * AudioRecordHelper.FRAME_SIZE,
            delivered.sumOf { samples -> samples.count { it == 14 / 32768f } },
        )
        assertTrue(delivered.last().size < AudioRecordHelper.SAMPLE_RATE * 400 / 1000)
    }

    @Test
    fun forcedQuietSplitThenVadOnsetDoesNotDropOrRepeatSamples() {
        val session = CaptureSession()
        session.feed(4, 62)
        session.feed(14, 315)
        session.feed(160, 31)
        session.feed(4, 25)

        val delivered = session.finish()

        assertEquals(2, delivered.size)
        assertEquals(
            315 * AudioRecordHelper.FRAME_SIZE,
            delivered.sumOf { samples -> samples.count { it == 14 / 32768f } },
        )
        assertEquals(
            31 * AudioRecordHelper.FRAME_SIZE,
            delivered.sumOf { samples -> samples.count { it == 160 / 32768f } },
        )
    }

    @Test
    fun quietEndpointDoesNotResubmitTrailingSilence() {
        val session = CaptureSession()
        session.feed(4, 62)
        session.feed(14, 31)
        session.feed(4, 50)

        assertEquals(1, session.sentences.size)
        assertNull(session.helper.extractRemainingForStop())
    }

    @Test
    fun discardedTransientDoesNotBlockFollowingShortSpeech() {
        val session = CaptureSession()
        session.feed(4, 62)
        session.feed(160, 1)
        session.feed(4, 25)
        assertTrue(session.sentences.isEmpty())

        session.feed(14, 5)
        val remaining = session.helper.extractRemainingForStop()

        assertNotNull(remaining)
        assertEquals(5 * AudioRecordHelper.FRAME_SIZE, remaining!!.count { it == 14 / 32768f })
        assertEquals(0, remaining.count { it == 160 / 32768f })
    }

    @Test
    fun forcedQuietSplitAndShortStopTailReachWorkerInOrder() {
        val decoded = ArrayList<FloatArray>()
        val finals = ArrayList<String>()
        val finished = CountDownLatch(1)
        val worker = AsrWorker(
            onInterimResult = {},
            onFinalResult = { finals.add(it) },
            transcribe = {
                decoded.add(it)
                "part ${decoded.size}"
            },
        )
        val session = CaptureSession(worker::submitFinal)
        session.feed(4, 62)
        session.feed(14, 305)

        worker.finishSession(session.helper.extractRemainingForStop()) { finished.countDown() }

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("part 1", "part 2"), finals)
        assertEquals(6_400, decoded.last().size)
        assertEquals(
            305 * AudioRecordHelper.FRAME_SIZE,
            decoded.sumOf { samples -> samples.count { it == 14 / 32768f } },
        )
    }
}
