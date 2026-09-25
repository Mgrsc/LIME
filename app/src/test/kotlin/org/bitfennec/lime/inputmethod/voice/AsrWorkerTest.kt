package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsrWorkerTest {

    @Test
    fun testSubmitPreviewAndKeyActivityCancellation() {
        val interimResults = ArrayList<String>()
        val finalResults = ArrayList<String>()

        val worker = AsrWorker(
            onInterimResult = { interimResults.add(it) },
            onFinalResult = { finalResults.add(it) }
        )

        // Simulate key press interruption
        worker.onUserKeyActivity()

        // Audio sample (< 400ms) is ignored
        val shortSamples = FloatArray(100)
        worker.submitPreview(shortSamples)
        assertTrue(interimResults.isEmpty())

        worker.reset()
    }

    @Test
    fun testAsrWorkerLifecycle() {
        val worker = AsrWorker(
            onInterimResult = {},
            onFinalResult = {}
        )
        // Verify multiple resets are idempotent and safe
        worker.reset()
        worker.reset()
        worker.onUserKeyActivity()
    }

    @Test
    fun testFinishSessionCallback() {
        val latch = CountDownLatch(1)
        val worker = AsrWorker(
            onInterimResult = {},
            onFinalResult = {}
        )
        worker.finishSession(null) {
            latch.countDown()
        }
        val finished = latch.await(2, TimeUnit.SECONDS)
        assertTrue(finished)
    }

    @Test
    fun testResetDropsInFlightFinal() {
        val started = CountDownLatch(1)
        val transcribeDone = CountDownLatch(1)
        val finals = ArrayList<String>()
        val worker = AsrWorker(
            onInterimResult = {},
            onFinalResult = { finals.add(it) },
            transcribe = {
                started.countDown()
                try {
                    Thread.sleep(150)
                } finally {
                    transcribeDone.countDown()
                }
                "should_not_commit"
            },
        )
        worker.submitFinal(FloatArray(AudioRecordHelper.SAMPLE_RATE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        worker.reset()
        assertTrue(transcribeDone.await(2, TimeUnit.SECONDS))
        val delivered = CountDownLatch(1)
        // Give the ASR coroutine time to pass the epoch check and invoke onFinalResult if it wrongly would.
        delivered.await(200, TimeUnit.MILLISECONDS)
        assertTrue("In-flight FINAL after reset must not be delivered", finals.isEmpty())
    }

    @Test
    fun testFinishSessionResetDuringTranscribeStillFinishesWithoutCommit() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val finals = ArrayList<String>()
        val worker = AsrWorker(
            onInterimResult = {},
            onFinalResult = { finals.add(it) },
            transcribe = {
                started.countDown()
                Thread.sleep(150)
                "should_not_commit"
            },
        )
        worker.finishSession(FloatArray(AudioRecordHelper.SAMPLE_RATE)) {
            finished.countDown()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        worker.reset()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(finals.isEmpty())
    }
}
