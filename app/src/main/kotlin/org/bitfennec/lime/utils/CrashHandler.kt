package org.bitfennec.lime.utils

import android.content.Context
import android.os.Build
import android.os.Process
import org.bitfennec.lime.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.system.exitProcess

/** Global uncaught exception logger; delegates termination to the platform handler. */
object CrashHandler : Thread.UncaughtExceptionHandler {
    private var outputDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        if (Thread.getDefaultUncaughtExceptionHandler() === this) return
        outputDir = File(context.filesDir, "crash_logs")
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashInfoToFile(throwable)
        } catch (_: Throwable) {
            // Crash reporting must not prevent the original exception reaching Android.
        } finally {
            val handler = defaultHandler
            if (handler != null) {
                handler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }

    private fun saveCrashInfoToFile(throwable: Throwable) {
        val directory = checkNotNull(outputDir)
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "Failed to create crash directory" }
        val errors = JSONArray()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = throwable
        while (cause != null && seen.add(cause)) {
            val current = cause
            // Exception messages can contain editor contents or other private data.
            errors.put(JSONObject().apply {
                put("type", current.javaClass.name)
                put("frames", JSONArray(current.stackTrace.map { it.toString() }))
            })
            cause = cause.cause
        }
        val event = JSONObject().apply {
            put("timestamp", Instant.now().toString())
            put("level", "fatal")
            put("event", "app.crash")
            put("service", "lime")
            put("thread_id", Process.myTid())
            put("version_name", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("exceptions", errors)
        }
        val file = File.createTempFile("crash-${System.currentTimeMillis()}-", ".log", directory)
        file.writeText(event.toString() + "\n")
    }
}
