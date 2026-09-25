package org.bitfennec.lime.utils

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher

/**
 * Extension functions for Context and system services.
 */

inline fun <reified T : Activity> Context.startActivity(setupIntent: Intent.() -> Unit = {}) {
    startActivity(Intent(this, T::class.java).apply(setupIntent))
}

val Context.audioManager: AudioManager
    get() = getSystemService<AudioManager>()!!

val Context.clipboardManager: ClipboardManager
    get() = getSystemService<ClipboardManager>()!!

val Context.inputMethodManager: InputMethodManager
    get() = getSystemService<InputMethodManager>()!!

val Context.notificationManager: NotificationManager
    get() = getSystemService<NotificationManager>()!!

val Context.vibrator: Vibrator
    get() = getSystemService<Vibrator>()!!

fun getSecureSettings(name: String): String? {
    return Settings.Secure.getString(Launcher.instance.context.contentResolver, name)
}

fun ContentResolver.queryFileName(uri: Uri): String? = query(uri, null, null, null, null)?.use {
    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    it.moveToFirst()
    it.getString(index)
}

val PackageInfo.versionCodeCompat: Long
    get() = longVersionCode

fun Configuration.isDarkMode(): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

fun Context.toast(string: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, string, duration).show()
}

fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resId, duration).show()
}

fun Context.toast(t: Throwable, duration: Int = Toast.LENGTH_SHORT) {
    toast(t.localizedMessage ?: t.stackTraceToString(), duration)
}

suspend fun <T> Context.toast(result: Result<T>, duration: Int = Toast.LENGTH_SHORT) {
    withContext(Dispatchers.Main.immediate) {
        result
            .onSuccess { toast(R.string.done, duration) }
            .onFailure { toast(it, duration) }
    }
}
