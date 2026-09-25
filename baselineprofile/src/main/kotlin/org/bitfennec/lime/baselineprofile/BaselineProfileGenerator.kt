package org.bitfennec.lime.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        showKeyboard()
    }

    @Test
    fun pinyinCandidate() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = false,
    ) {
        showKeyboard()
        type("ni")
        val candidate = device.wait(
            Until.findObject(
                By.res(TARGET_PACKAGE, "gv_candidates_bar_item").text(Pattern.compile("\\S+")),
            ),
            CANDIDATE_TIMEOUT_MS,
        )
        checkNotNull(candidate) { "candidate not shown" }
        candidate.click()
    }

    private fun MacrobenchmarkScope.showKeyboard() {
        val component = android.content.ComponentName(TARGET_PACKAGE, IME_CLASS).flattenToShortString()
        val enabled = device.executeShellCommand("ime enable $component").trim()
        val selected = device.executeShellCommand("ime set $component").trim()
        check(!enabled.contains("Error", ignoreCase = true) && !enabled.contains("Unknown", ignoreCase = true)) { enabled }
        check(!selected.contains("Error", ignoreCase = true) && !selected.contains("Unknown", ignoreCase = true)) { selected }

        val hostPackage = instrumentation.context.packageName
        device.executeShellCommand("am start -n $hostPackage/$hostPackage.ImeHostActivity")
        val hostField = device.wait(
            Until.findObject(By.res(hostPackage, "host_field")),
            KEYBOARD_TIMEOUT_MS,
        )
        checkNotNull(hostField) { "host_field not found" }
        hostField.click()
        val keyboard = device.wait(
            Until.findObject(By.res(TARGET_PACKAGE, "skb_input_keyboard_view")),
            KEYBOARD_TIMEOUT_MS,
        )
        checkNotNull(keyboard) { "keyboard not shown" }
    }

    private companion object {
        const val TARGET_PACKAGE = "org.bitfennec.lime"
        const val IME_CLASS = "org.bitfennec.lime.service.ImeService"
        const val KEYBOARD_TIMEOUT_MS = 15_000L
        const val CANDIDATE_TIMEOUT_MS = 20_000L
    }
}
