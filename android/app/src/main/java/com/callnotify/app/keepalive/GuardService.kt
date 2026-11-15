package com.callnotify.app.keepalive

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callnotify.app.R
import com.callnotify.app.core.AppState
import com.callnotify.app.core.Prefs
import com.callnotify.app.service.RelayService
import com.callnotify.app.util.Notifications

/**
 * 守护进程（运行在 :guard 独立进程里）。
 *
 * 机制：本进程 bind 主进程的 RelayService，主进程也 bind 本进程，两边都监听
 * `onServiceDisconnected`。任意一方被杀，另一方立刻把它拉起来（1 秒后重试）。
 *
 * 诚实说明：Android 8+ 有后台启动限制，厂商 ROM 的“一键清理”通常按 uid 把两个进程
 * 一起杀，所以这层**不是万能的**；它的价值在于“系统自发回收其中一个进程”时能立刻互补。
 * 真正 100% 可靠的唤醒只有厂商推送（小米/华为/OPPO/vivo/荣耀），那需要另开一条链路。
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val binder = Binder()
    private var boundToRelay = false

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundSafely()
        bindToRelay()
        AppState.log("守护进程已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { unbindService(relayConnection) }
        // 被系统回收时，若保活仍开启则 1 秒后自启
        if (Prefs(this).serviceEnabled && Prefs(this).registered && Prefs(this).guardEnabled) {
            handler.postDelayed({ start(this) }, 1000)
        }
        super.onDestroy()
    }

    private fun bindToRelay() {
        if (boundToRelay) return
        val intent = Intent(this, RelayService::class.java)
        boundToRelay = runCatching {
            bindService(intent, relayConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (boundToRelay) AppState.log("守护进程已绑定主服务")
    }

    private val relayConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) {
            boundToRelay = false
            AppState.log("守护：主服务已断开，1 秒后拉起", 2)
            handler.postDelayed({
                RelayService.start(this@GuardService)
                bindToRelay()
            }, 1_000)
        }
    }

    private fun startForegroundSafely() {
        val notification: Notification = NotificationCompat.Builder(this, Notifications.CHANNEL_GUARD)
            .setContentTitle("来电通知 · 守护中")
            .setContentText("保持连接不被系统回收")
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifications.GUARD_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.GUARD_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(Notifications.GUARD_ID, notification)
            }
        }.onFailure { Log.e(TAG, "guard startForeground 失败", it) }
    }

    companion object {
        private const val TAG = "GuardService"

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { AppState.log("启动守护进程失败：${it.message}", 2) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GuardService::class.java)) }
        }
    }
}
