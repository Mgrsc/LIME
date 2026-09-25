package org.bitfennec.lime.utils

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class CrashHandlerTest {
    @Test
    fun concurrentCrashesAndStorageFailureReachOriginalHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val directory = Files.createTempDirectory("crash-handler-test").toFile()
        val received = ConcurrentLinkedQueue<Throwable>()
        val delegate = Thread.UncaughtExceptionHandler { _, error -> received.add(error) }
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        }
        try {
            Thread.setDefaultUncaughtExceptionHandler(delegate)
            CrashHandler.init(context)
            CrashHandler.init(context)
            val first = IllegalStateException("private editor text")
            val second = IllegalArgumentException("private cause")
            first.initCause(second)
            second.initCause(first)
            val start = CountDownLatch(1)
            val workers = List(16) {
                thread {
                    start.await()
                    CrashHandler.uncaughtException(Thread.currentThread(), first)
                }
            }
            start.countDown()
            workers.forEach { it.join(5000) }
            assertEquals(16, received.size)
            received.forEach { assertSame(first, it) }
            // Android JSON methods are mocked in local JVM tests; this checks file
            // isolation and delegation, not the serialized Android JSON payload.
            assertEquals(16, File(directory, "crash_logs").listFiles()?.size)

            File(directory, "crash_logs").deleteRecursively()
            File(directory, "crash_logs").writeText("blocked")
            val failure = IllegalStateException("original crash")
            CrashHandler.uncaughtException(Thread.currentThread(), failure)
            assertEquals(17, received.size)
            assertSame(failure, received.last())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            directory.deleteRecursively()
        }
    }
}
