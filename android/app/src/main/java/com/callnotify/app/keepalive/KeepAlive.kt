package com.callnotify.app.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.callnotify.app.MainActivity
import com.callnotify.app.core.AppState
import com.callnotify.app.core.Prefs

/**
 * 保活唤醒。
 *
 * 三种唤醒手段，从强到弱：
 *   1. **AlarmClock**（`setAlarmClock`）：不需要任何权限，永远精确，Doze 也唤醒，
 *      并且是 Android 12+ 允许后台启动前台服务的白名单来源之一。
 *      代价是状态栏会显示一个闹钟图标——对"值班告警"这种用途其实是好事（等于"已布防"指示）。
 *   2. **精确闹钟**（`setExactAndAllowWhileIdle`）：需要 SCHEDULE_EXACT_ALARM，
 *      Doze 下系统把它限制在约 9 分钟一次。
 *   3. **persisted 周期 Job**：15 分钟，进程被杀、手机重启后仍在，最后一道保险。
 *
 * 用户设置里可以关掉 (1)，退化成 (2)+(3)。
 */
object KeepAlive {

    private const val ACTION_TICK = "com.callnotify.app.action.KEEPALIVE_TICK"
    private const val REQUEST_TICK = 1001
    private const val REQUEST_RESURRECT_A = 1002
    private const val REQUEST_RESURRECT_B = 1003
    private const val REQUEST_RESURRECT_C = 1004
    private const val REQUEST_SHOW = 1005
    private const val JOB_ID = 2001

    /** 心跳间隔（走 AlarmClock 时精确生效；走精确闹钟在 Doze 下会被系统拉长） */
    private const val TICK_INTERVAL_MS = 5 * 60 * 1000L
    private const val INEXACT_INTERVAL_MS = 15 * 60 * 1000L

    fun scheduleTicks(context: Context) {
        scheduleNextTick(context)
        schedulePersistedJob(context)
    }

    fun scheduleNextTick(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = Prefs(context)
        val pi = pending(context, REQUEST_TICK, ACTION_TICK)

        if (prefs.aggressiveWake) {
            val triggerAt = System.currentTimeMillis() + TICK_INTERVAL_MS
            val show = PendingIntent.getActivity(
                context,
                REQUEST_SHOW,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val ok = runCatching {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
            }.isSuccess
            if (ok) return
            AppState.log("AlarmClock 设置失败，退回精确闹钟", 2)
        }

        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        val interval = if (exactAllowed) TICK_INTERVAL_MS else INEXACT_INTERVAL_MS
        val triggerAt = SystemClock.elapsedRealtime() + interval
        runCatching {
            am.cancel(pi)
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }.onFailure { AppState.log("安排保活闹钟失败：${it.message}", 2) }
    }

    /** 划掉 App 后尽快拉起：三次机会，覆盖"进程没死透 / 系统限流 / 首次被拦" */
    fun scheduleResurrect(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        listOf(
            REQUEST_RESURRECT_A to 300L,
            REQUEST_RESURRECT_B to 1_500L,
            REQUEST_RESURRECT_C to 5_000L,
        ).forEach { (code, delay) ->
            val pi = pending(context, code, ACTION_TICK)
            val triggerAt = SystemClock.elapsedRealtime() + delay
            runCatching {
                if (exactAllowed) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            }
        }
        AppState.log("已安排 0.3s / 1.5s / 5s 三次自动拉起")
    }

    /**
     * JobScheduler 兜底：persisted + periodic，进程被杀、手机重启后仍存在，
     * 是"闹钟被 ROM 拦掉"时唯一还能生效的一层。不用 expedited（有配额，会被降级）。
     */
    fun schedulePersistedJob(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val existing = runCatching { js.getPendingJob(JOB_ID) }.getOrNull()
        if (existing != null) return
        val component = ComponentName(context, KeepAliveJobService::class.java)
        val job = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_LINEAR)
            .build()
        val result = runCatching { js.schedule(job) }.getOrDefault(-1)
        AppState.log(if (result == JobScheduler.RESULT_SUCCESS) "已注册系统保活任务" else "注册系统保活任务失败（$result）")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        listOf(REQUEST_TICK, REQUEST_RESURRECT_A, REQUEST_RESURRECT_B, REQUEST_RESURRECT_C).forEach { code ->
            runCatching { am?.cancel(pending(context, code, ACTION_TICK)) }
        }
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }

    /** 手机是否允许我们设精确闹钟（Android 12+ 可被用户关掉） */
    fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun pending(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
