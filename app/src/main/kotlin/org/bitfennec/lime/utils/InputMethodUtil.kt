package org.bitfennec.lime.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.service.ImeService

object InputMethodUtil {

    val serviceName: String = ImeService::class.java.name

    val componentName: String =
        ComponentName(Launcher.instance.context, ImeService::class.java).flattenToShortString()

    fun isEnabled(): Boolean {
        return Launcher.instance.context.inputMethodManager.enabledInputMethodList.any {
            it.packageName == Launcher.instance.context.packageName && it.serviceName == serviceName
        }
    }

    fun isSelected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Launcher.instance.context.inputMethodManager.currentInputMethodInfo?.let {
                it.packageName == Launcher.instance.context.packageName && it.serviceName == serviceName
            } ?: false
        } else {
            getSecureSettings(Settings.Secure.DEFAULT_INPUT_METHOD) == componentName
        }
    }

    fun startSettingsActivity(context: Context) =
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    fun showPicker() = Launcher.instance.context.inputMethodManager.showInputMethodPicker()
}