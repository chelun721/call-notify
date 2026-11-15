package com.callnotify.app.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import com.callnotify.app.core.AppState
import com.callnotify.app.core.Prefs
import com.callnotify.app.service.RelayService

/**
 * JobScheduler 兜底任务：定期检查前台服务是否还活着，不活就拉起来。
 *
 * 和闹钟的区别：persisted 任务在进程被杀、手机重启后依然存在，
 * 是国产 ROM 拦闹钟时唯一还能生效的一层。
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = Prefs(this)
        if (!prefs.serviceEnabled || !prefs.registered) {
            return false
        }
        AppState.log("系统任务唤醒：检查后台连接")
        RelayService.start(this)
        // 顺手把精确闹钟链条接上（进程重启后闹钟可能已经丢了）
        KeepAlive.scheduleNextTick(this)
        // 不保留 wakelock：拉起服务是一次性动作
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
