package org.bitfennec.lime.inputmethod.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoiceLatencyTraceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun entry(taskId: Long = 1L) = VoiceLatencyRecord(
        event = VoiceLatencyTrace.Event.TASK_COMPLETED,
        traceId = 7L,
        taskId = taskId,
        kind = VoiceLatencyTrace.Kind.FINAL,
        status = VoiceLatencyTrace.Status.SUCCESS,
        sampleCount = 16_000,
        durationMs = 321L,
        queueMs = 45L,
        processId = 123,
        timestampMillis = 0L,
        elapsedNanos = 1_234_567_890L,
    )

    @Test
    fun serializesOnlyTypedMetadataWithUtcAndMonotonicTime() {
        assertEquals(
            "{\"timestamp\":\"1970-01-01T00:00:00Z\",\"level\":\"debug\",\"service\":\"lime.ime.voice\"," +
                "\"event\":\"voice.task_completed\",\"process_id\":123,\"trace_id\":7,\"task_id\":1," +
                "\"event_seq\":2,\"elapsed_ms\":1234,\"dropped_events\":3,\"kind\":\"final\"," +
                "\"result\":\"success\",\"sample_count\":16000,\"duration_ms\":321,\"queue_ms\":45," +
                "\"model_id\":\"voice\"}",
            entry().toJson(sequence = 2L, droppedEvents = 3L),
        )
    }

    @Test
    fun absentMeasurementsStayAbsent() {
        val json = VoiceLatencyRecord(
            VoiceLatencyTrace.Event.STOP_REQUESTED, traceId = 9L,
        ).toJson(sequence = 1L, droppedEvents = 0L)
        for (field in listOf("sample_count", "duration_ms", "queue_ms", "kind", "result")) {
            assertFalse(json.contains("\"$field\""))
        }
    }

    @Test
    fun appendsCompleteRecordsAcrossStoreRecreation() {
        val directory = File(temporaryFolder.root, "diagnostics")
        VoiceLatencyTraceStore(directory).append(entry(), 1L, 0L)
        VoiceLatencyTraceStore(directory).append(entry(2L), 2L, 0L)
        assertEquals(
            listOf(entry().toJson(1L, 0L), entry(2L).toJson(2L, 0L)),
            File(directory, "voice-latency.jsonl").readLines(),
        )
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun rotatesOnlyTwoFilesWithoutExceedingByteLimit() {
        val directory = temporaryFolder.newFolder()
        val lineBytes = (entry().toJson(1L, 0L) + "\n").toByteArray(Charsets.UTF_8).size
        val store = VoiceLatencyTraceStore(directory, maxFileBytes = lineBytes)
        for (sequence in 1L..4L) store.append(entry(), sequence, 0L)
        assertEquals(2, directory.listFiles()!!.size)
        assertEquals(entry().toJson(4L, 0L) + "\n", File(directory, "voice-latency.jsonl").readText())
        assertEquals(entry().toJson(3L, 0L) + "\n", File(directory, "voice-latency.previous.jsonl").readText())
        assertTrue(directory.listFiles()!!.all { it.length() <= lineBytes })
    }

    @Test
    fun oversizedRecordAndUnavailableDirectoryFailBeforeWriting() {
        val directory = File(temporaryFolder.root, "oversized")
        assertThrows(IllegalArgumentException::class.java) {
            VoiceLatencyTraceStore(directory, maxFileBytes = 1).append(entry(), 1L, 0L)
        }
        assertFalse(directory.exists())
        val unavailable = temporaryFolder.newFile()
        assertThrows(IllegalStateException::class.java) {
            VoiceLatencyTraceStore(unavailable).append(entry(), 1L, 0L)
        }
        assertEquals(0L, unavailable.length())
    }

    @Test
    fun concurrentAppendsKeepRecordsWhole() {
        val directory = temporaryFolder.newFolder()
        val store = VoiceLatencyTraceStore(directory)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1L..80L).map { sequence ->
                Callable { store.append(entry(sequence), sequence, 0L) }
            }
            executor.invokeAll(tasks, 5L, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        val actual = File(directory, "voice-latency.jsonl").readLines()
        val expected = (1L..80L).map { entry(it).toJson(it, 0L) }.toSet()
        assertEquals(80, actual.size)
        assertEquals(expected, actual.toSet())
    }
}
