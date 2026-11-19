package org.bitfennec.lime.ui.fragment

import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bitfennec.lime.R
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.ui.activity.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @Test
    fun repeatedAndCrossClicksKeepTheFirstDestination() = withSettings { scenario ->
        scenario.onActivity { activity ->
            val host = activity.navigationHost()
            val root = host.homeView()

            assertTrue(root.findViewById<View>(R.id.item_about_settings).performClick())
            entries.forEach { (viewId, _) ->
                assertTrue(root.findViewById<View>(viewId).performClick())
                assertEquals(R.id.aboutFragment, host.navController.currentDestination?.id)
            }
        }
    }

    @Test
    fun everyEntryCanNavigateAgainAfterReturningHome() = withSettings { scenario ->
        entries.forEach { (viewId, destinationId) ->
            scenario.onActivity { activity ->
                val host = activity.navigationHost()
                assertTrue(host.homeView().findViewById<View>(viewId).performClick())
                assertEquals(destinationId, host.navController.currentDestination?.id)
            }
            waitForIdle()
            scenario.onActivity { activity ->
                val host = activity.navigationHost()
                assertTrue(host.navController.popBackStack())
                assertEquals(R.id.settingsFragment, host.navController.currentDestination?.id)
            }
            waitForIdle()
        }
    }

    @Test
    fun pausedHomeIgnoresClicksAndAcceptsThemAfterResume() = withSettings { scenario ->
        lateinit var root: View
        scenario.onActivity { activity -> root = activity.navigationHost().homeView() }

        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.onActivity { activity ->
            assertTrue(root.findViewById<View>(R.id.item_about_settings).performClick())
            assertEquals(R.id.settingsFragment, activity.navigationHost().navController.currentDestination?.id)
        }

        scenario.moveToState(Lifecycle.State.RESUMED)
        waitForIdle()
        scenario.onActivity { activity ->
            val host = activity.navigationHost()
            assertTrue(host.homeView().findViewById<View>(R.id.item_about_settings).performClick())
            assertEquals(R.id.aboutFragment, host.navController.currentDestination?.id)
        }
    }

    @Test
    fun oldHomeViewIgnoresClicksAfterActivityRecreation() = withSettings { scenario ->
        lateinit var oldRoot: View
        scenario.onActivity { activity -> oldRoot = activity.navigationHost().homeView() }

        scenario.recreate()
        waitForIdle()
        scenario.onActivity { activity ->
            val host = activity.navigationHost()
            assertTrue(oldRoot.findViewById<View>(R.id.item_about_settings).performClick())
            assertEquals(R.id.settingsFragment, host.navController.currentDestination?.id)
            assertTrue(host.homeView().findViewById<View>(R.id.item_about_settings).performClick())
            assertEquals(R.id.aboutFragment, host.navController.currentDestination?.id)
        }
    }

    private fun withSettings(action: (ActivityScenario<SettingsActivity>) -> Unit) {
        val preference = AppPrefs.getInstance().internal.privacyPolicySure
        val previous = preference.getValue()
        preference.setValue(true)
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val intent = Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
                waitForIdle()
                action(scenario)
            }
        } finally {
            preference.setValue(previous)
        }
    }

    private fun SettingsActivity.navigationHost(): NavHostFragment =
        supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    private fun NavHostFragment.homeView(): View {
        childFragmentManager.executePendingTransactions()
        val fragment = childFragmentManager.primaryNavigationFragment as ImeSettingsFragment
        assertEquals(Lifecycle.State.RESUMED, fragment.viewLifecycleOwner.lifecycle.currentState)
        return fragment.requireView()
    }

    private fun waitForIdle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private companion object {
        val entries = listOf(
            R.id.item_input_settings to R.id.inputSettingsFragment,
            R.id.item_voice_settings to R.id.voiceSettingsFragment,
            R.id.item_handwriting_settings to R.id.handwritingSettingsFragment,
            R.id.item_theme_settings to R.id.themeFragment,
            R.id.item_clipboard_settings to R.id.clipboardSettingsFragment,
            R.id.item_data_settings to R.id.otherSettingsFragment,
            R.id.item_about_settings to R.id.aboutFragment,
        )
    }
}
