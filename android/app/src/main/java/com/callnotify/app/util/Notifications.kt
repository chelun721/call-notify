package com.callnotify.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.callnotify.app.MainActivity
import com.callnotify.app.R
import com.callnotify.app.service.RelayService

object Notifications {

    const val CHANNEL_SERVICE = "call_notify_service"
    const val CHANNEL_GUARD = "call_notify_guard"
    const val SERVICE_ID = 1001
    const val GUARD_ID = 1002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_SERVICE)

        // IMPORTANCE_MIN + 锁屏隐藏 会让部分 ROM 认为"这个前台服务没有可见通知"，
        // 从而把进程放进冻结队列（实测会出现"进程被冻结 100+ 秒"）。
        // 用 LOW + 锁屏可见，通知不响不震，但系统会正经对待这个前台服务。
        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            "后台连接",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持与通知服务器的连接，收到通知后模拟来电"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setSound(null, null)
            enableVibration(false)
        }

        if (existing == null) {
            nm.createNotificationChannel(channel)
        } else if (existing.importance < NotificationManager.IMPORTANCE_LOW) {
            // 老版本装过 IMPORTANCE_MIN 的渠道，改不回去，只能删了重建
            nm.deleteNotificationChannel(CHANNEL_SERVICE)
            nm.createNotificationChannel(channel)
        }

        // 守护进程用单独的渠道，静默、低优先级，不打扰
        if (nm.getNotificationChannel(CHANNEL_GUARD) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_GUARD, "进程守护", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "保持后台连接不被系统回收"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    fun serviceNotification(context: Context, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, RelayService::class.java).setAction(RelayService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("来电通知")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)

        // Android 12+：立刻显示这条前台服务通知，别等系统的 10 秒延迟
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun update(context: Context, text: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(SERVICE_ID, serviceNotification(context, text))
        }
    }
}
