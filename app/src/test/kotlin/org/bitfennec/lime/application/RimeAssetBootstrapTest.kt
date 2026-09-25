package org.bitfennec.lime.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RimeAssetBootstrapTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun installationRejectsDamageAndRecoversWithoutReplacingUserData() {
        val directory = temporary.newFolder("rime")
        val source = File("src/main/assets/rime")
        val text = File("src/main/assets/${RimeAssetBootstrap.MANIFEST_ASSET}").readText()
        val manifest = RimeAssetBootstrap.parseManifest(text)
        val userFile = File(directory, "user.yaml").apply { writeText("user-owned") }
        var copies = 0
        val install = {
            copies++
            manifest.keys.forEach { path ->
                val destination = File(directory, path)
                destination.parentFile!!.mkdirs()
                File(source, path).copyTo(destination, overwrite = true)
            }
            true
        }
        val marker = File(directory, RimeAssetBootstrap.BUNDLE_MARKER)
        RimeAssetBootstrap.resetForTesting()
        try {
            assertEquals(RimeAssetBootstrap.State.FullReady, RimeAssetBootstrap.ensureBundle(directory, text, install))
            val originalMarker = marker.readText()
            assertEquals("user-owned", userFile.readText())
            assertEquals(RimeAssetBootstrap.State.FullReady, RimeAssetBootstrap.ensureBundle(directory, text, install))
            assertEquals(1, copies)

            // A new process must detect equal-sized corruption, even with a matching marker.
            val table = File(directory, "build/pinyin.table.bin")
            java.io.RandomAccessFile(table, "rw").use { it.writeByte(0) }
            RimeAssetBootstrap.resetForTesting()
            assertEquals(RimeAssetBootstrap.State.Failed, RimeAssetBootstrap.ensureBundle(directory, text) { false })
            assertFalse(marker.exists())
            assertEquals("user-owned", userFile.readText())
            assertEquals(RimeAssetBootstrap.State.FullReady, RimeAssetBootstrap.ensureBundle(directory, text, install))
            assertEquals(originalMarker, marker.readText())

            // Copy completion alone cannot publish ready when a converter dependency is missing.
            File(directory, "opencc/STPhrases.ocd2").delete()
            assertEquals(RimeAssetBootstrap.State.Failed, RimeAssetBootstrap.ensureBundle(directory, text) { true })
            assertFalse(marker.exists())
            assertEquals(RimeAssetBootstrap.State.FullReady, RimeAssetBootstrap.ensureBundle(directory, text, install))

            // Interrupted copies and invalid manifests stay failed and are retryable.
            marker.delete()
            assertEquals(RimeAssetBootstrap.State.Failed, RimeAssetBootstrap.ensureBundle(directory, text) {
                File(directory, "build/english.table.bin").writeBytes(byteArrayOf(1))
                throw java.io.IOException("Injected copy failure")
            })
            assertFalse(marker.exists())
            assertEquals(RimeAssetBootstrap.State.FullReady, RimeAssetBootstrap.ensureBundle(directory, text, install))
            assertEquals(RimeAssetBootstrap.State.Failed, RimeAssetBootstrap.ensureBundle(directory, "{}", install))
            assertFalse(marker.exists())
            assertEquals(RimeAssetBootstrap.State.FullReady, RimeAssetBootstrap.ensureBundle(directory, text, install))
            assertTrue(runCatching {
                RimeAssetBootstrap.parseManifest(text.replace("opencc/s2t.json", "../s2t.json"))
            }.isFailure)
            assertEquals("user-owned", userFile.readText())
        } finally {
            RimeAssetBootstrap.resetForTesting()
        }
    }
}
