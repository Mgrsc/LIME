
package org.bitfennec.lime.data.theme

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.prefs.WeakHashSet
import org.bitfennec.lime.utils.isDarkMode
import org.bitfennec.lime.view.preference.ManagedPreference

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    val BuiltinThemes = listOf(
        ThemePreset.MonetLight,
        ThemePreset.MonetDark,
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.AMOLEDBlack,
        ThemePreset.Monokai,
    )

    val DefaultTheme = ThemePreset.MaterialLight

    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes()

    fun getTheme(name: String) =
        customThemes.find { it.name == name } ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + BuiltinThemes

    fun refreshThemes() {
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes())
        activeTheme = evaluateActiveTheme()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        val theme = _activeTheme
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onChangeListeners.forEach { it.onThemeChange(theme) }
        } else {
            Handler(Looper.getMainLooper()).post {
                onChangeListeners.forEach { it.onThemeChange(theme) }
            }
        }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        ThemeFilesManager.saveThemeFiles(theme)
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        if (activeTheme.name == theme.name) {
            _activeTheme = theme
            fireChange()
        }
    }

    fun deleteTheme(name: String) {
        customThemes.find { it.name == name }?.also {
            ThemeFilesManager.deleteThemeFiles(it)
            customThemes.remove(it)
        }
        if (prefs.normalModeTheme.getValue().name == name) {
            prefs.normalModeTheme.setValue(DefaultTheme)
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
    }

    fun setNormalModeTheme(theme: Theme) {
        prefs.normalModeTheme.setValue(theme)
        if (!prefs.followSystemDayNightTheme.getValue()) {
            activeTheme = theme
        }
    }

    fun setLightModeTheme(theme: Theme) {
        prefs.lightModeTheme.setValue(theme)
        if (prefs.followSystemDayNightTheme.getValue() && !isDarkMode) {
            activeTheme = theme
        }
    }

    fun setDarkModeTheme(theme: Theme) {
        prefs.darkModeTheme.setValue(theme)
        if (prefs.followSystemDayNightTheme.getValue() && isDarkMode) {
            activeTheme = theme
        }
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }.getValue()
    }

    @Keep
    private val onThemePrefsChange = ManagedPreference.OnChangeListener<Any> { key, _ ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            activeTheme = evaluateActiveTheme()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.managedPreferences.values.forEach {
            it.registerOnChangeListener(onThemePrefsChange)
        }
        _activeTheme = evaluateActiveTheme()
    }

    fun onSystemDarkModeChange(isDark: Boolean) {
        isDarkMode = isDark
        activeTheme = evaluateActiveTheme()
    }

}