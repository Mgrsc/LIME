package org.bitfennec.lime.inputmethod.voice.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.bitfennec.lime.R
import org.bitfennec.lime.keyboard.KeyboardManager
import org.bitfennec.lime.inputmethod.voice.VoiceModelManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight permission proxy Activity (zero framework overhead, launches system permission dialog directly).
 */
class VoicePermissionActivity : Activity() {

    private data class PendingPermissionRequest(
        val permissions: Set<String>,
        val callback: (Boolean) -> Unit
    )

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 2001
        const val EXTRA_PERMISSIONS = "extra_permissions"
        private val pendingRequests = CopyOnWriteArrayList<PendingPermissionRequest>()

        fun hasRecordAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun hasPermission(context: Context): Boolean = hasRecordAudioPermission(context)

        fun requestPermission(context: Context, onResult: ((granted: Boolean) -> Unit)? = null) {
            requestCustomPermissions(context, arrayOf(Manifest.permission.RECORD_AUDIO), onResult)
        }

        fun requestNotificationPermission(context: Context, onResult: ((granted: Boolean) -> Unit)? = null) {
            if (hasNotificationPermission(context)) {
                onResult?.invoke(true)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestCustomPermissions(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS), onResult)
            } else {
                onResult?.invoke(true)
            }
        }

        fun requestCustomPermissions(
            context: Context,
            permissions: Array<String>,
            onResult: ((granted: Boolean) -> Unit)? = null
        ) {
            val ungranted = permissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (ungranted.isEmpty()) {
                onResult?.invoke(true)
                return
            }

            onResult?.let {
                pendingRequests.add(PendingPermissionRequest(ungranted.toSet(), it))
            }

            val intent = Intent(context, VoicePermissionActivity::class.java).apply {
                putExtra(EXTRA_PERMISSIONS, ungranted.toTypedArray())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            context.startActivity(intent)
        }
    }

    private var currentRequestedPerms: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val requestedPerms = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
            ?: arrayOf(Manifest.permission.RECORD_AUDIO)
        currentRequestedPerms = requestedPerms.toSet()

        val ungranted = requestedPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (ungranted.isEmpty()) {
            notifySuccess(requestedPerms)
            return
        }

        requestPermissions(ungranted, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val permMap = permissions.indices.associate { permissions[it] to (grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED) }

            dispatchPendingRequests(permMap)

            if (permissions.contains(Manifest.permission.RECORD_AUDIO)) {
                val audioGranted = permMap[Manifest.permission.RECORD_AUDIO] == true
                if (!audioGranted) {
                    val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    if (permanentlyDenied) {
                        showSettingsDialog()
                    } else {
                        Toast.makeText(this, getString(R.string.voice_record_permission_denied), Toast.LENGTH_SHORT).show()
                        finishWithNoAnimation()
                    }
                    return
                }
            }

            // On audio permission granted, show download dialog if model is not yet ready
            if (permissions.contains(Manifest.permission.RECORD_AUDIO) && !VoiceModelManager.isModelReady(this)) {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.VOICE_DOWNLOAD)
            }

            finishWithNoAnimation()
        } else {
            failPendingRequests(currentRequestedPerms)
            finishWithNoAnimation()
        }
    }

    private fun dispatchPendingRequests(permMap: Map<String, Boolean>) {
        val iterator = pendingRequests.iterator()
        while (iterator.hasNext()) {
            val req = iterator.next()
            if (req.permissions.all { permMap.containsKey(it) }) {
                pendingRequests.remove(req)
                val allGranted = req.permissions.all { permMap[it] == true }
                req.callback(allGranted)
            }
        }
    }

    private fun failPendingRequests(permFilter: Set<String>) {
        val iterator = pendingRequests.iterator()
        while (iterator.hasNext()) {
            val req = iterator.next()
            if (req.permissions.all { permFilter.contains(it) }) {
                pendingRequests.remove(req)
                req.callback(false)
            }
        }
    }

    private fun notifySuccess(requestedPerms: Array<out String>) {
        val permSet = requestedPerms.toSet()
        val iterator = pendingRequests.iterator()
        while (iterator.hasNext()) {
            val req = iterator.next()
            if (req.permissions.all { permSet.contains(it) }) {
                pendingRequests.remove(req)
                req.callback(true)
            }
        }

        if (permSet.contains(Manifest.permission.RECORD_AUDIO) && !VoiceModelManager.isModelReady(this)) {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.VOICE_DOWNLOAD)
        }

        finishWithNoAnimation()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_permission_dialog_title)
            .setMessage(R.string.voice_permission_dialog_message)
            .setPositiveButton(R.string.voice_permission_dialog_go_to_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
                finishWithNoAnimation()
            }
            .setNegativeButton(R.string.voice_btn_cancel) { _, _ ->
                finishWithNoAnimation()
            }
            .setCancelable(false)
            .show()
    }

    private fun finishWithNoAnimation() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && currentRequestedPerms.isNotEmpty()) {
            failPendingRequests(currentRequestedPerms)
        }
    }
}
