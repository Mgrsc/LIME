package org.bitfennec.lime.inputmethod.voice

import android.content.Context
import android.os.Process
import org.bitfennec.lime.BuildConfig
import org.bitfennec.lime.core.runtime.AiPackageSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal object VoiceLatencyTrace {
    enum class Event(val value: String) {
        SESSION_STARTED("voice.session_started"),
        SESSION_CANCELLED("voice.session_cancelled"),
        STOP_REQUESTED("voice.stop_requested"),
        CAPTURE_STOPPED("voice.capture_stopped"),
        STOP_HANDOFF("voice.stop_handoff"),
        TASK_QUEUED("voice.task_queued"),
        TASK_STARTED("voice.task_started"),
        TASK_COMPLETED("voice.task_completed"),
        ENGINE_INIT_STARTED("voice.engine_init_started"),
        ENGINE_INIT_COMPLETED("voice.engine_init_completed"),
        TRANSCRIBE_STARTED("voice.transcribe_started"),
        DECODE_STARTED("voice.decode_started"),
        DECODE_COMPLETED("voice.decode_completed"),
        TRANSCRIBE_COMPLETED("voice.transcribe_completed"),
        RESULT_RECEIVED("voice.result_received"),
        PREVIEW_UPDATED("voice.preview_updated"),
        RESULT_HANDLING_STARTED("voice.result_handling_started"),
        COMMIT_COMPLETED("voice.commit_completed"),
        SESSION_FINISHED("voice.session_finished"),
    }

    enum class Status(val value: String) {
        READY("ready"),
        PREPARING("preparing"),
        SUCCESS("success"),
        EMPTY("empty"),
        FAILED("failed"),
        DROPPED("dropped"),
        CANCELLED("cancelled"),
        NOT_READY("not_ready"),
    }

    enum class Kind(val value: String) {
        PREVIEW("preview"),
        FINAL("final"),
        FINISH("finish"),
    }

    private const val MAX_PENDING_EVENTS = 128
    private const val WRITER_IDLE_TIMEOUT_SECONDS = 30L
    private val traceCounter = AtomicLong()
    private val eventCounter = AtomicLong()
    private val droppedEvents = AtomicLong()

    @Volatile
    private var store: VoiceLatencyTraceStore? = null

    private val executor by lazy {
        ThreadPoolExecutor(
            1, 1, WRITER_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_PENDING_EVENTS),
            { task ->
                Thread(task, "voice-latency-writer").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            },
            { _, _ -> droppedEvents.incrementAndGet() },
        ).apply { allowCoreThreadTimeOut(true) }
    }

    fun startSession(context: Context, ready: Boolean): Long {
        if (!BuildConfig.DEBUG) return 0L
        try {
            synchronized(this) {
                if (store == null) {
                    store = VoiceLatencyTraceStore(File(context.applicationContext.noBackupFilesDir, "voice-diagnostics"))
                }
            }
        } catch (_: Exception) {
            return 0L
        }
        val traceId = traceCounter.incrementAndGet()
        record(
            Event.SESSION_STARTED, traceId,
            status = if (ready) Status.READY else Status.PREPARING,
        )
        return traceId
    }

    fun record(
        event: Event,
        traceId: Long = 0L,
        taskId: Long = 0L,
        kind: Kind? = null,
        status: Status? = null,
        sampleCount: Int? = null,
        durationMs: Long? = null,
        queueMs: Long? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val target = store ?: return
        val entry = VoiceLatencyRecord(
            event, traceId, taskId, kind, status, sampleCount, durationMs, queueMs,
            processId = Process.myPid(),
        )
        val sequence = eventCounter.incrementAndGet()
        try {
            executor.execute {
                try {
                    target.append(entry, sequence, droppedEvents.get())
                } catch (_: Exception) {
                    droppedEvents.incrementAndGet()
                }
            }
        } catch (_: Exception) {
            droppedEvents.incrementAndGet()
        }
    }
}

internal data class VoiceLatencyRecord(
    val event: VoiceLatencyTrace.Event,
    val traceId: Long,
    val taskId: Long = 0L,
    val kind: VoiceLatencyTrace.Kind? = null,
    val status: VoiceLatencyTrace.Status? = null,
    val sampleCount: Int? = null,
    val durationMs: Long? = null,
    val queueMs: Long? = null,
    val processId: Int = 0,
    val timestampMillis: Long = System.currentTimeMillis(),
    val elapsedNanos: Long = System.nanoTime(),
) {
    fun toJson(sequence: Long, droppedEvents: Long): String = buildString {
        append("{\"timestamp\":\"").append(Instant.ofEpochMilli(timestampMillis))
        append("\",\"level\":\"debug\",\"service\":\"lime.ime.voice\",\"event\":\"")
        append(event.value).append('"')
        append(",\"process_id\":").append(processId)
        append(",\"trace_id\":").append(traceId)
        append(",\"task_id\":").append(taskId)
        append(",\"event_seq\":").append(sequence)
        append(",\"elapsed_ms\":").append(TimeUnit.NANOSECONDS.toMillis(elapsedNanos))
        append(",\"dropped_events\":").append(droppedEvents)
        kind?.let { append(",\"kind\":\"").append(it.value).append('"') }
        status?.let { append(",\"result\":\"").append(it.value).append('"') }
        sampleCount?.let { append(",\"sample_count\":").append(it) }
        durationMs?.let { append(",\"duration_ms\":").append(it) }
        queueMs?.let { append(",\"queue_ms\":").append(it) }
        append(",\"model_id\":\"").append(AiPackageSpec.voicePackage.id).append('"')
        append('}')
    }
}

internal class VoiceLatencyTraceStore(
    private val directory: File,
    private val maxFileBytes: Int = 256 * 1024,
) {
    @Synchronized
    fun append(entry: VoiceLatencyRecord, sequence: Long, droppedEvents: Long) {
        val bytes = (entry.toJson(sequence, droppedEvents) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFileBytes)
        check(directory.isDirectory || directory.mkdirs())
        val current = File(directory, "voice-latency.jsonl")
        if (current.length() + bytes.size > maxFileBytes) {
            Files.move(
                current.toPath(), File(directory, "voice-latency.previous.jsonl").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        current.appendBytes(bytes)
    }
}
