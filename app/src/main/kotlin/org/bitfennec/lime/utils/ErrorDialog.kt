package org.bitfennec.lime.utils

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import org.bitfennec.lime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Context.showErrorDialog(
    message: String,
    @StringRes titleRes: Int = R.string.operation_failed
) {
    withContext(Dispatchers.Main.immediate) {
        AlertDialog.Builder(this@showErrorDialog)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .show()
    }
}

suspend fun Context.showErrorDialog(
    t: Throwable,
    @StringRes titleRes: Int = R.string.operation_failed
) {
    showErrorDialog(t.localizedMessage ?: t.stackTraceToString(), titleRes)
}

suspend fun Context.showErrorDialog(
    @StringRes resId: Int,
    @StringRes titleRes: Int = R.string.operation_failed,
    vararg formatArgs: Any?
) {
    showErrorDialog(getString(resId, *formatArgs), titleRes)
}

suspend fun Context.importErrorDialog(t: Throwable) {
    showErrorDialog(t, R.string.import_error)
}

suspend fun Context.importErrorDialog(@StringRes resId: Int, vararg formatArgs: Any?) {
    showErrorDialog(getString(resId, *formatArgs), R.string.import_error)
}
