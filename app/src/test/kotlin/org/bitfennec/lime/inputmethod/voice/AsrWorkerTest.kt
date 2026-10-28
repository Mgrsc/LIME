package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun testFinalFallbackToLastPreviewWhenFinalDecodeIsPunctuationOnly() {
        val interimLatch = CountDownLatch(1)
        val finalLatch = CountDownLatch(1)
        val finals = ArrayList<String>()
        val callCount = AtomicInteger(0)

        val worker = AsrWorker(
            onInterimResult = {
                interimLatch.countDown()
            },
            onFinalResult = {
                finals.add(it)
                finalLatch.countDown()
            },
            transcribe = {
                if (callCount.incrementAndGet() == 1) {
                    "<|zh|>你好世界。"
                } else {
                    "<|zh|>。"
                }
            },
        )

        val validSamples = FloatArray(AudioRecordHelper.SAMPLE_RATE)
        worker.submitPreview(validSamples)
        assertTrue(interimLatch.await(2, TimeUnit.SECONDS))

        worker.finishSession(validSamples) {}
        assertTrue(finalLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("你好世界"), finals)
    }

    @Test
    fun testPreviewFallbackRejectedWhenCoverageGapTooLargeOrCrossUtterance() {
        val interimLatch = CountDownLatch(1)
        val finalLatch = CountDownLatch(2)
        val finals = ArrayList<String>()
        val callCount = AtomicInteger(0)

        val worker = AsrWorker(
            onInterimResult = {
                interimLatch.countDown()
            },
            onFinalResult = {
                finals.add(it)
                finalLatch.countDown()
            },
            transcribe = {
                when (callCount.incrementAndGet()) {
                    1 -> "<|zh|>短预览"
                    else -> "<|zh|>。"
                }
            },
        )

        val shortPreviewSamples = FloatArray(AudioRecordHelper.SAMPLE_RATE)
        worker.submitPreview(shortPreviewSamples)
        assertTrue(interimLatch.await(2, TimeUnit.SECONDS))

        val longFinalSamples = FloatArray(AudioRecordHelper.SAMPLE_RATE * 3)
        worker.submitFinal(longFinalSamples)
        worker.submitFinal(shortPreviewSamples)

        assertTrue(finalLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("", ""), finals)
    }

    @Test
    fun testPreviewFallbackClearedOnSpeechStart() {
        val interimLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        val finals = ArrayList<String>()
        val callCount = AtomicInteger(0)

        val worker = AsrWorker(
            onInterimResult = { interimLatch.countDown() },
            onFinalResult = { finals.add(it) },
            transcribe = {
                if (callCount.incrementAndGet() == 1) "<|zh|>旧句子预览" else "<|zh|>。"
            },
        )

        val oneSecSamples = FloatArray(AudioRecordHelper.SAMPLE_RATE)
        worker.submitPreview(oneSecSamples)
        assertTrue(interimLatch.await(2, TimeUnit.SECONDS))

        worker.onSpeechStart()
        worker.finishSession(oneSecSamples) {
            finishedLatch.countDown()
        }
        assertTrue(finishedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(""), finals)
    }

    @Test
    fun testPreviewFallbackRejectedWhenPreviewLongerThanFinal() {
        val interimLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        val finals = ArrayList<String>()
        val callCount = AtomicInteger(0)

        val worker = AsrWorker(
            onInterimResult = { interimLatch.countDown() },
            onFinalResult = { finals.add(it) },
            transcribe = {
                if (callCount.incrementAndGet() == 1) "<|zh|>两秒预览内容" else "<|zh|>。"
            },
        )

        val oneSecSamples = FloatArray(AudioRecordHelper.SAMPLE_RATE)
        val twoSecSamples = FloatArray(AudioRecordHelper.SAMPLE_RATE * 2)

        worker.submitPreview(twoSecSamples)
        assertTrue(interimLatch.await(2, TimeUnit.SECONDS))

        worker.finishSession(oneSecSamples) {
            finishedLatch.countDown()
        }
        assertTrue(finishedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(""), finals)
    }

    @Test
    fun testPreviewFallbackAcceptsTailTrimmedFinal() {
        val interimLatch = CountDownLatch(1)
        val finalLatch = CountDownLatch(1)
        val finals = ArrayList<String>()
        val callCount = AtomicInteger(0)

        val worker = AsrWorker(
            onInterimResult = { interimLatch.countDown() },
            onFinalResult = {
                finals.add(it)
                finalLatch.countDown()
            },
            transcribe = {
                if (callCount.incrementAndGet() == 1) "<|zh|>你好你好" else "<|zh|>。"
            },
        )

        val trimmedFinal = FloatArray(AudioRecordHelper.SAMPLE_RATE)
        val untrimmedPreview = FloatArray(AudioRecordHelper.SAMPLE_RATE + 8_000)
        worker.submitPreview(untrimmedPreview)
        assertTrue(interimLatch.await(2, TimeUnit.SECONDS))
        worker.finishSession(trimmedFinal) {}
        assertTrue(finalLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("你好你好"), finals)
    }
}
