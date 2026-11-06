package org.bitfennec.lime.ui.fragment

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.bitfennec.lime.R
import org.bitfennec.lime.core.runtime.AiDownloadManager
import org.bitfennec.lime.core.runtime.AiPackageInstaller
import org.bitfennec.lime.core.runtime.AiPackageInstaller.PackageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandwritingDownloadLifecycleTest {
    @Test
    fun destroyedViewReleasesDialogAndIgnoresLaterProgressSuccessAndFailure() = withDialog { fixture ->
        fixture.fragment.onDestroyView()

        assertNull(dialogField.get(fixture.fragment))
        fixture.render(downloading)
        fixture.render(PackageStatus.Ready)
        fixture.render(PackageStatus.Failed("Download failed"))
    }

    @Test
    fun backgroundDismissalPreservesDownloadAndIgnoresRepeatedTerminalStates() = withDialog { fixture ->
        val previous = AiDownloadManager.getStatusFlow().value
        try {
            AiPackageInstaller.updateStatus("handwriting", downloading)
            dismissMethod.invoke(fixture.fragment)

            assertNull(dialogField.get(fixture.fragment))
            assertEquals(downloading, AiDownloadManager.getStatusFlow().value)
            fixture.render(PackageStatus.Ready)
            fixture.render(PackageStatus.Ready)
            fixture.render(PackageStatus.Failed("Download failed"))
        } finally {
            AiPackageInstaller.updateStatus("handwriting", previous)
        }
    }

    @Test
    fun newlyCreatedDialogRendersTheCurrentDownloadSnapshot() = withDialog { fixture ->
        fixture.render(downloading)

        assertEquals(37, fixture.progress.progress)
        assertEquals("Connecting to runtime", fixture.status.text.toString())
        assertEquals(View.GONE, fixture.speed.visibility)
    }

    private data class Fixture(
        val fragment: HandwritingSettingsFragment,
        val progress: ProgressBar,
        val status: TextView,
        val speed: TextView,
    ) {
        fun render(state: PackageStatus) {
            renderMethod.invoke(fragment, state)
        }
    }

    private fun withDialog(action: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_AppTheme)
            val fragment = HandwritingSettingsFragment()
            val dialog = MaterialAlertDialogBuilder(context).create()
            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            val status = TextView(context)
            val speed = TextView(context)
            val constructor = dialogField.type.declaredConstructors.single().apply { isAccessible = true }
            dialogField.set(fragment, constructor.newInstance(dialog, progress, status, speed))
            try {
                assertNull(fragment.context)
                action(Fixture(fragment, progress, status, speed))
            } finally {
                fragment.onDestroyView()
            }
        }
    }

    private companion object {
        val dialogField = HandwritingSettingsFragment::class.java.getDeclaredField("downloadDialog").apply { isAccessible = true }
        val renderMethod = HandwritingSettingsFragment::class.java.getDeclaredMethod("updateDownloadDialog", PackageStatus::class.java)
            .apply { isAccessible = true }
        val dismissMethod = HandwritingSettingsFragment::class.java.getDeclaredMethod("dismissDownloadDialog")
            .apply { isAccessible = true }
        val downloading = PackageStatus.Downloading(
            artifactName = "Runtime archive",
            bytesRead = 37,
            totalBytes = 100,
            speedBps = 1_024,
            percent = 37,
            stageDesc = "Connecting to runtime",
        )
    }
}
