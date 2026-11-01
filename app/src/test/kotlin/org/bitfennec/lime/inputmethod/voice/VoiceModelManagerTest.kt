package org.bitfennec.lime.inputmethod.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitfennec.lime.core.runtime.AiBlobStore
import org.bitfennec.lime.core.runtime.AiPackageInstaller
import org.bitfennec.lime.core.runtime.AiPackageSpec
import org.bitfennec.lime.inputmethod.ImeDispatchers
import org.bitfennec.lime.prefs.AppPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceModelManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var preferences: SharedPreferences
    private var originalAppPrefs: AppPrefs? = null
    private val retiredPackageId = "voice-funasr-nano"
    private val context: Context
        get() = object : ContextWrapper(null) {
            override fun getFilesDir(): File = temporaryFolder.root
            override fun getApplicationContext(): Context = this
        }

    @Before
    fun prepare() {
        preferences = InMemorySharedPreferences().apply {
            edit().putInt("voice_model", 1).apply()
        }
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            originalAppPrefs = get(null) as AppPrefs?
            set(null, null)
        }
        AppPrefs.init(preferences)
        VoiceModelManager.invalidateCache()
        VoiceRecognitionEngine.release()
    }

    @After
    fun restore() {
        VoiceRecognitionEngine.release()
        VoiceModelManager.invalidateCache()
        for (packageId in listOf("voice", "handwriting")) {
            AiPackageInstaller.updateStatus(packageId, AiPackageInstaller.PackageStatus.Missing)
        }
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, originalAppPrefs)
        }
    }

    private fun retiredModelFile(): File {
        val directory = AiBlobStore.getPackageVersionDir(context, retiredPackageId, "retained-version")
        assertTrue(directory.mkdirs())
        AiBlobStore.getPackageCurrentFile(context, retiredPackageId).writeText("retained-version")
        return File(directory, AiBlobStore.MANIFEST_FILE).apply { writeText("retained") }
    }

    private fun installSenseVoiceFixture() {
        for (pkg in AiPackageSpec.resolveDependencies("voice")) {
            for (artifact in pkg.artifacts) {
                val blob = AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName)
                blob.parentFile!!.mkdirs()
                RandomAccessFile(blob, "rw").use { it.setLength(artifact.expectedBytes) }
            }
            val directory = AiBlobStore.getPackageVersionDir(context, pkg.id, pkg.version)
            assertTrue(directory.mkdirs())
            File(directory, AiBlobStore.MANIFEST_FILE).writeText(
                pkg.artifacts.joinToString("\n") { "${it.fileName}=${it.sha256}" },
            )
            AiBlobStore.getPackageCurrentFile(context, pkg.id).writeText(pkg.version)
        }
    }

    @Test
    fun retiredPreferenceAndFilesDoNotMakeSenseVoiceReadyOrTriggerCleanup() {
        val retained = retiredModelFile()
        assertFalse(VoiceModelManager.isModelReady(context))

        installSenseVoiceFixture()

        assertTrue(VoiceModelManager.isModelReady(context))
        assertEquals(1, preferences.getInt("voice_model", 0))
        assertEquals("retained", retained.readText())
        assertEquals("retained-version", AiBlobStore.getActiveVersion(context, retiredPackageId))
    }

    @Test
    fun activeRecordingLeaseRejectsDeletion() = runBlocking {
        VoiceRecognitionEngine.acquireLease()
        try {
            assertFalse(VoiceModelManager.deleteModel(context))
            assertTrue(VoiceRecognitionEngine.hasActiveLease())
        } finally {
            VoiceRecognitionEngine.releaseLease()
        }
    }

    @Test
    fun deletionWaitsForNativeWorkAndKeepsHandwritingRuntimeAndRetiredModel() = runBlocking {
        fun blob(artifact: AiPackageSpec.Artifact): File =
            AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName).apply {
                parentFile!!.mkdirs()
                writeText("fixture")
            }
        val removed = blob(AiPackageSpec.voiceModelArtifact)
        val handwriting = blob(AiPackageSpec.hwModelArtifact)
        val runtime = blob(AiPackageSpec.ortArtifact)
        val retired = retiredModelFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nativeWork = launch(ImeDispatchers.asrDispatcher) {
            started.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            VoiceModelManager.deleteModel(context)
        }
        try {
            assertFalse(deletion.isCompleted)
            assertTrue(removed.exists())
        } finally {
            release.countDown()
        }
        assertTrue(withTimeout(2_000) { deletion.await() })
        nativeWork.join()
        assertFalse(removed.exists())
        assertTrue(handwriting.exists())
        assertTrue(runtime.exists())
        assertEquals("retained", retired.readText())
    }

    @Test
    fun queuedDeletionRejectsRecordingThatStartedWhileWaiting() = runBlocking {
        val artifact = AiPackageSpec.voiceModelArtifact
        val retained = AiBlobStore.getBlobFile(context, artifact.sha256, artifact.fileName)
        retained.parentFile!!.mkdirs()
        retained.writeText("fixture")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nativeWork = launch(ImeDispatchers.asrDispatcher) {
            started.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            VoiceModelManager.deleteModel(context)
        }

        VoiceRecognitionEngine.acquireLease()
        try {
            release.countDown()
            assertFalse(withTimeout(2_000) { deletion.await() })
            assertTrue(retained.exists())
        } finally {
            release.countDown()
            VoiceRecognitionEngine.releaseLease()
        }
        nativeWork.join()
    }

    @Test
    fun cancellationKeepsHandwritingDownloadAndRetiredModelFiles() {
        val retired = retiredModelFile()
        val downloading = AiPackageInstaller.PackageStatus.Downloading("model", 1, 100, 0, 1)
        AiPackageInstaller.updateStatus("voice", downloading)
        AiPackageInstaller.updateStatus("handwriting", downloading)
        assertTrue(VoiceModelManager.isDownloading())

        VoiceModelManager.cancelDownload(context)

        assertFalse(VoiceModelManager.isDownloading())
        assertEquals(AiPackageInstaller.PackageStatus.Missing, VoiceModelManager.getStatusFlow().value)
        assertEquals(downloading, AiPackageInstaller.getStatusFlow("handwriting").value)
        assertEquals("retained", retired.readText())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { pending[key!!] = this }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) target.clear()
                for ((key, value) in pending) {
                    if (value === this) target.remove(key) else target[key] = value
                }
            }
        }
    }
}
