package org.bitfennec.lime.inputmethod.voice

import kotlinx.coroutines.runBlocking
import org.bitfennec.lime.inputmethod.ImeDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AsrWorkerSchedulingTest {

    private fun awaitWorkerIdle() {
        runBlocking(ImeDispatchers.asrDispatcher) {}
    }

    @Test
    fun fastPreviewsResumeAtNextPollingTick() {
        val pollingIntervalNanos = TimeUnit.MILLISECONDS.toNanos(500)
        for (decodeDurationMs in listOf(60L, 100L, 190L)) {
            val clock = AtomicLong(0)
            val calls = AtomicInteger(0)
            val interim = ArrayList<String>()
            val worker = AsrWorker(
                onInterimResult = { interim.add(it) },
                onFinalResult = {},
                transcribe = {
                    val call = calls.incrementAndGet()
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(decodeDurationMs))
                    "preview $call"
                },
                clockNanos = clock::get,
            )

            repeat(3) { index ->
                clock.set(index * pollingIntervalNanos)
                assertTrue(worker.canSubmitPreview())
                worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE * (index + 1)))
                awaitWorkerIdle()
                assertFalse(worker.canSubmitPreview())
            }

            assertEquals(3, calls.get())
            assertEquals(listOf("preview 1", "preview 2", "preview 3"), interim)
        }
    }

    @Test
    fun slowerPreviewSkipsPollingTickUntilDecodeDurationCooldownEnds() {
        val clock = AtomicLong(0)
        val calls = AtomicInteger(0)
        val worker = AsrWorker(
            onInterimResult = {},
            onFinalResult = {},
            transcribe = {
                calls.incrementAndGet()
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(300))
                "preview"
            },
            clockNanos = clock::get,
        )

        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE))
        awaitWorkerIdle()
        clock.set(TimeUnit.MILLISECONDS.toNanos(500))
        assertFalse(worker.canSubmitPreview())
        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE * 2))
        awaitWorkerIdle()
        assertEquals(1, calls.get())

        clock.set(TimeUnit.MILLISECONDS.toNanos(1_000))
        assertTrue(worker.canSubmitPreview())
        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE * 2))
        awaitWorkerIdle()
        assertEquals(2, calls.get())
    }

    @Test
    fun busyPreviewStillDeliversAndCooldownTracksDecodeDuration() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = AtomicLong(0)
        val calls = AtomicInteger(0)
        val interim = ArrayList<String>()
        val worker = AsrWorker(
            onInterimResult = { interim.add(it) },
            onFinalResult = {},
            transcribe = {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    started.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(800))
                "preview $call"
            },
            clockNanos = clock::get,
        )

        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        try {
            repeat(5) {
                worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE * 2))
            }
            assertFalse(worker.canSubmitPreview())
        } finally {
            release.countDown()
        }
        awaitWorkerIdle()

        assertEquals(listOf("preview 1"), interim)
        assertEquals(1, calls.get())
        assertFalse(worker.canSubmitPreview())
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(799))
        assertFalse(worker.canSubmitPreview())
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1))
        assertTrue(worker.canSubmitPreview())

        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE * 2))
        awaitWorkerIdle()
        assertEquals(listOf("preview 1", "preview 2"), interim)
    }

    @Test
    fun repeatedIdenticalSnapshotDoesNotDecodeAgain() {
        val clock = AtomicLong(0)
        val calls = AtomicInteger(0)
        val worker = AsrWorker(
            onInterimResult = {},
            onFinalResult = {},
            transcribe = {
                calls.incrementAndGet()
                "preview"
            },
            clockNanos = clock::get,
        )
        val samples = FloatArray(AudioRecordHelper.SAMPLE_RATE)
        worker.submitPreview(samples)
        awaitWorkerIdle()
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500))

        worker.submitPreview(samples.copyOf())
        awaitWorkerIdle()
        assertEquals(1, calls.get())

        val changedSamples = samples.copyOf().apply { this[lastIndex] = 0.1f }
        worker.submitPreview(changedSamples)
        awaitWorkerIdle()
        assertEquals(2, calls.get())
    }

    @Test
    fun finishWithoutRemainderInvalidatesRunningPreview() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val interim = ArrayList<String>()
        val calls = AtomicInteger(0)
        val worker = AsrWorker(
            onInterimResult = { interim.add(it) },
            onFinalResult = {},
            transcribe = {
                calls.incrementAndGet()
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                "obsolete"
            },
        )

        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        try {
            worker.finishSession(null) { finished.countDown() }
            worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE * 2))
        } finally {
            release.countDown()
        }

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        awaitWorkerIdle()
        assertTrue(interim.isEmpty())
        assertEquals(1, calls.get())
        assertFalse(worker.canSubmitPreview())
    }

    @Test
    fun pendingFinalsRunInOrderWithoutAdditionalPreviews() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val decoded = ArrayList<Int>()
        val interim = ArrayList<String>()
        val finals = ArrayList<String>()
        val worker = AsrWorker(
            onInterimResult = { interim.add(it) },
            onFinalResult = { finals.add(it) },
            transcribe = { samples ->
                val marker = samples[0].toInt()
                decoded.add(marker)
                if (marker == 1) {
                    started.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
                "result $marker"
            },
        )

        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE) { 1f })
        assertTrue(started.await(2, TimeUnit.SECONDS))
        try {
            worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE) { 4f })
            worker.submitFinal(FloatArray(AudioRecordHelper.SAMPLE_RATE) { 2f })
            worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE) { 5f })
            worker.submitFinal(FloatArray(AudioRecordHelper.SAMPLE_RATE) { 3f })
            worker.finishSession(null) { finished.countDown() }
        } finally {
            release.countDown()
        }

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        awaitWorkerIdle()
        assertEquals(listOf(1, 2, 3), decoded)
        assertEquals(listOf("result 2", "result 3"), finals)
        assertTrue(interim.isEmpty())
    }

    @Test
    fun shortFinalIsPaddedAtDecodeBoundaryWithoutChangingInput() {
        for (finishSession in listOf(false, true)) {
            val samples = FloatArray(AudioRecordHelper.SAMPLE_RATE / 10) { 0.1f }
            val decoded = AtomicReference<FloatArray>()
            val finals = ArrayList<String>()
            val worker = AsrWorker(
                onInterimResult = {},
                onFinalResult = { finals.add(it) },
                transcribe = {
                    decoded.set(it)
                    "short word"
                },
            )
            if (finishSession) {
                worker.finishSession(samples) {}
            } else {
                worker.submitFinal(samples)
            }
            awaitWorkerIdle()

            assertEquals(6_400, decoded.get().size)
            assertTrue(decoded.get().take(samples.size).all { it == 0.1f })
            assertTrue(decoded.get().drop(samples.size).all { it == 0f })
            assertTrue(samples.all { it == 0.1f })
            assertEquals(listOf("short word"), finals)
        }
    }

    @Test
    fun resetDuringPreviewDoesNotReleaseNewPreviewSlot() {
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newStarted = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val interim = ArrayList<String>()
        val worker = AsrWorker(
            onInterimResult = { interim.add(it) },
            onFinalResult = {},
            transcribe = { samples ->
                if (samples[0] == 1f) {
                    oldStarted.countDown()
                    assertTrue(releaseOld.await(2, TimeUnit.SECONDS))
                    "old"
                } else {
                    newStarted.countDown()
                    assertTrue(releaseNew.await(2, TimeUnit.SECONDS))
                    "new"
                }
            },
        )

        worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE) { 1f })
        assertTrue(oldStarted.await(2, TimeUnit.SECONDS))
        try {
            worker.reset()
            worker.submitPreview(FloatArray(AudioRecordHelper.SAMPLE_RATE) { 2f })
            releaseOld.countDown()
            assertTrue(newStarted.await(2, TimeUnit.SECONDS))
            assertFalse(worker.canSubmitPreview())
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
        }
        awaitWorkerIdle()
        assertEquals(listOf("new"), interim)
    }
}
