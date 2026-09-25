package org.bitfennec.lime.utils

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavDeepLinkBuilder
import org.bitfennec.lime.R
import org.bitfennec.lime.ui.activity.SettingsActivity
import kotlin.system.exitProcess

object AppUtil {

    fun launchSettings(context: Context) {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).send()
        } catch (e: Exception) {
            context.startActivity(intent)
        }
    }

    private fun launchMainToDest(context: Context, @IdRes dest: Int, arguments: Bundle? = null) {
        val targetIntent: Intent? = NavDeepLinkBuilder(context)
            .setComponentName(SettingsActivity::class.java)
            .setGraph(R.navigation.settings_nav)
            .setDestination(dest)
            .setArguments(arguments)
            .createTaskStackBuilder()
            .editIntentAt(0)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        if (targetIntent != null) {
            try {
                PendingIntent.getActivity(context, dest, targetIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).send()
            } catch (e: Exception) {
                context.startActivity(targetIntent)
            }
        }
    }

    fun launchSettingsToHandwriting(context: Context) =
        launchMainToDest(context, R.id.handwritingSettingsFragment)

    fun launchSettingsToPrefix(context: Context, arguments: Bundle? = null) =
        launchMainToDest(context, R.id.sidebarSymbolFragment, arguments)

    fun exit() {
        exitProcess(0)
    }

    @SuppressLint("NotificationPermission")
    fun showRestartNotification(ctx: Context) {
        val channelId = "app-restart"
        val channel = NotificationChannel(channelId, ctx.getText(R.string.restart_channel), NotificationManager.IMPORTANCE_HIGH).apply { description = channelId }
        ctx.notificationManager.createNotificationChannel(channel)
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(R.drawable.ic_lime_launcher_transparent)
                .setContentTitle(ctx.getText(R.string.app_name))
                .setContentText(ctx.getText(R.string.restart_notify_msg))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build()
                .let { ctx.notificationManager.notify(0xdead, it) }
        }
    }
}
