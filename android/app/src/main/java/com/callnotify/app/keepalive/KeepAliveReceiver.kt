package com.callnotify.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callnotify.app.core.AppState
import com.callnotify.app.core.Prefs
import com.callnotify.app.service.RelayService

/**
 * 保活闹钟的落地端：进程被系统清掉后，闹钟把前台服务重新拉起来。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.serviceEnabled || !prefs.registered) {
            KeepAlive.cancel(context)
            AppState.log("保活唤醒：服务已关闭或未注册，停止保活", 2)
            return
        }
        AppState.log("保活唤醒：恢复后台连接")
        RelayService.start(context)
        // 一次性闹钟要把下一环接上，否则保活链就断了
        KeepAlive.scheduleNextTick(context)
        KeepAlive.schedulePersistedJob(context)
    }
}
