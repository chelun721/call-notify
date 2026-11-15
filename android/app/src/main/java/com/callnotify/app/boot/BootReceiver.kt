package com.callnotify.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callnotify.app.core.AppState
import com.callnotify.app.core.Prefs
import com.callnotify.app.service.RelayService

/** 开机 / 应用更新后恢复后台连接 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Prefs(context)
        if (!prefs.serviceEnabled || !prefs.registered) return

        AppState.log("开机自启，恢复连接")
        RelayService.start(context)
    }
}
