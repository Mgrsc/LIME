package org.bitfennec.lime.core.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class HandwritingDownloadCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    @OptIn(InternalForInheritanceCoroutinesApi::class)
    fun repeatedDownloadCommandKeepsTheExistingCallerAndSharedInstallation() = runBlocking {
        val serviceStarts = AtomicInteger()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temporaryFolder.root
            override fun startForegroundService(service: Intent): ComponentName {
                serviceStarts.incrementAndGet()
                return ComponentName("download-command-fixture", "DownloadService")
            }
            override fun startService(service: Intent): ComponentName =
                ComponentName("download-command-fixture", "DownloadService")
        }
        val packageJobsField = AiPackageInstaller::class.java.getDeclaredField("activePackageJobs").apply { isAccessible = true }
        val callerField = AiDownloadManager::class.java.getDeclaredField("activeHwJob").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val packageJobs = packageJobsField.get(AiPackageInstaller) as MutableMap<String, Deferred<Boolean>>
        val result = CompletableDeferred<Boolean>()
        val awaited = CompletableDeferred<Unit>()
        val installation = object : Deferred<Boolean> by result {
            override suspend fun await(): Boolean {
                awaited.complete(Unit)
                return result.await()
            }
        }
        packageJobs["handwriting"] = installation
        try {
            AiDownloadManager.downloadHandwriting(context)
            val firstCaller = callerField.get(AiDownloadManager) as Job
            AiDownloadManager.downloadHandwriting(context)

            assertSame(firstCaller, callerField.get(AiDownloadManager))
            assertTrue(firstCaller.isActive)
            assertSame(installation, packageJobs["handwriting"])
            assertEquals(1, serviceStarts.get())
            withTimeout(5_000) { awaited.await() }
            result.complete(true)
            withTimeout(5_000) { firstCaller.join() }
            assertFalse(firstCaller.isCancelled)
        } finally {
            AiDownloadManager.cancelDownload(context)
            installation.cancelAndJoin()
            packageJobs.remove("handwriting", installation)
        }
    }
}
