package com.callnotify.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.callnotify.app.core.AppState
import com.callnotify.app.core.ConnState
import com.callnotify.app.core.DeviceApi
import com.callnotify.app.core.Prefs
import com.callnotify.app.core.SetupAssistant
import com.callnotify.app.keepalive.GuardService
import com.callnotify.app.keepalive.KeepAlive
import com.callnotify.app.net.NotificationPayload
import com.callnotify.app.net.RelayClient
import com.callnotify.app.telecom.CallAccounts
import com.callnotify.app.telecom.CallBus
import com.callnotify.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：维持 WebSocket 长连接，收到通知后触发模拟来电。
 */
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var client: RelayClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ackJob: Job? = null
    private var statusJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var authRetryJob: Job? = null
    private var guardBound = false
    private var screenReceiver: BroadcastReceiver? = null
    private var screenOffWakelock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var stoppedByUser = false
    private var lastOnlineAt = 0L

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundSafely("正在连接服务器…")

        statusJob = scope.launch {
            AppState.conn.collect { state ->
                val text = when (state) {
                    ConnState.IDLE -> "已停止"
                    ConnState.CONNECTING -> AppState.detail.value.ifBlank { "正在连接…" }
                    ConnState.ONLINE -> "已连接，等待通知"
                    ConnState.ERROR -> AppState.detail.value.ifBlank { "连接异常，重试中" }
                }
                Notifications.update(this@RelayService, text)
            }
        }

        startWatchdog()
        registerNetworkCallback()
        registerScreenReceiver()
        KeepAlive.scheduleTicks(this)
        maybeStartGuard()
    }

    /**
     * 自愈看门狗。
     *
     * 掉线恢复的两个数字：
     *   - 每 8 秒检查一次（原来 15 秒）；
     *   - 超过 35 秒没回到 ONLINE 就整体重建连接（原来 60 秒）。
     * 配合 RelayClient 自己的 0.5s 起退避，绝大多数情况走不到这里，
     * 它是给"client 内部状态卡住"用的最后一道。
     */
    private fun startWatchdog() {
        lastOnlineAt = SystemClock.elapsedRealtime()
        var lastTick = SystemClock.elapsedRealtime()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(6_000)
                val now = SystemClock.elapsedRealtime()

                // 理论上只过了 6 秒，实际过去很久 = 进程被 ROM 冻结过。
                // 这不是网络问题，日志里必须区分出来，否则会误判成"连接慢"。
                val frozenMs = now - lastTick - 6_000
                if (frozenMs > 10_000) {
                    AppState.log("进程被系统冻结约 ${frozenMs / 1000}s（后台保活被中断）", 2)
                }
                lastTick = now

                val state = AppState.conn.value
                val reallyOnline = client?.isOnline() == true
                if (state == ConnState.ONLINE && reallyOnline) {
                    lastOnlineAt = now
                    continue
                }
                if (state == ConnState.IDLE) continue
                if (now - lastOnlineAt > 20_000) {
                    AppState.log("连接超过 20 秒未恢复，重建连接", 2)
                    ackJob?.cancel()
                    ackJob = null
                    client?.close()
                    client = null
                    holdWakeLockBriefly()
                    startRelay(Prefs(this@RelayService))
                    lastOnlineAt = now
                }
            }
        }
    }

    /** 网络切换（WiFi ↔ 5G、断网恢复）时立刻重连；连接健康时不折腾 */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AppState.log("网络已恢复")
                holdWakeLockBriefly()
                client?.reconnectNow("网络恢复")
            }

            override fun onLost(network: Network) {
                AppState.log("网络断开，等待系统恢复", 2)
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { AppState.log("注册网络回调失败：${it.message}", 2) }
    }

    /** 重连期间短暂持有唤醒锁，避免息屏下刚发出的重连被系统冻住 */
    private fun holdWakeLockBriefly() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            if (wakeLock?.isHeld == true) return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallNotify:reconnect").apply {
                setReferenceCounted(false)
                acquire(30_000L)
            }
        }
    }

    /**
     * 屏幕亮起 / 用户解锁时立刻补一次连接。
     *
     * 这是"最容易被忽略但最有效"的一条：手机被冻结一晚上，用户一拿起手机（亮屏/解锁）
     * 就把连接补上，感知上接近"一直在线"。
     */
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> enterScreenOffMode()
                    else -> {
                        exitScreenOffMode()
                        if (client?.isOnline() != true) {
                            AppState.log("屏幕亮起，立即重连")
                            holdWakeLockBriefly()
                            client?.reconnectNow("屏幕亮起")
                        }
                    }
                }
            }
        }
        runCatching { registerReceiver(screenReceiver, filter) }
    }

    /**
     * 锁屏保活：屏幕熄灭后持有 CPU 唤醒锁 + 高性能 Wi-Fi 锁。
     *
     * 原理：Doze 会在息屏静止一段时间后切断网络、冻结 CPU，长连接必然断。
     * 持有 PARTIAL_WAKE_LOCK 让 CPU 不挂起，WifiLock(HIGH_PERF) 让 Wi-Fi 无线不休眠，
     * 两把锁一起才能真的"锁屏也在线"——代价是耗电明显上升，所以做成可选开关。
     */
    private fun enterScreenOffMode() {
        val prefs = Prefs(this)
        if (!prefs.lockScreenKeepAlive) return
        if (!SetupAssistant.batteryOptimizationIgnored(this)) {
            AppState.log("警告：未加入电池优化白名单，锁屏后系统仍可能切断网络", 2)
        }
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            if (screenOffWakelock?.isHeld != true) {
                screenOffWakelock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "CallNotify:screenoff"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            if (wifiLock?.isHeld != true && wm != null) {
                wifiLock = wm.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CallNotify:wifi"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            AppState.log("锁屏保活开启：CPU 与 Wi-Fi 保持唤醒")
        }.onFailure { AppState.log("锁屏保活失败：${it.message}", 2) }
    }

    private fun exitScreenOffMode() {
        runCatching { screenOffWakelock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        screenOffWakelock = null
        wifiLock = null
    }

    private val guardConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) {
            guardBound = false
            if (!Prefs(this@RelayService).guardEnabled) return
            AppState.log("守护进程被回收，正在拉起", 2)
            GuardService.start(this@RelayService)
            bindToGuard()
        }
    }

    /** 启动守护进程（:guard 独立进程），两边互相 bind 互相拉起 */
    private fun maybeStartGuard() {
        val prefs = Prefs(this)
        if (!prefs.guardEnabled) return
        GuardService.start(this)
        bindToGuard()
    }

    private fun bindToGuard() {
        if (guardBound) return
        val intent = Intent(this, GuardService::class.java)
        guardBound = runCatching {
            bindService(intent, guardConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stoppedByUser = true
            GuardService.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = Prefs(this)
        if (!prefs.registered) {
            AppState.log("尚未注册设备，后台服务退出", 2)
            stopSelf()
            return START_NOT_STICKY
        }

        CallAccounts.ensure(this)
        startRelay(prefs)
        return START_STICKY
    }

    private val binder = android.os.Binder()

    // 返回非空 Binder：守护进程靠 bindService 的 onServiceDisconnected 感知本服务死亡
    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 用户从"最近任务"里划掉 App 时会被调用（进程随时可能被杀）。
     *
     * 这里做三件事，越快恢复越好：
     *   1. 立刻确认前台服务还活着（重新贴一次通知，防止被当成空进程回收）；
     *   2. 若连接已死，立刻重建；
     *   3. 安排 0.3s / 1.5s / 5s 三次拉起的闹钟——进程真被杀了就靠它们。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppState.log("App 被从最近任务划掉，立即自恢复")
        val prefs = Prefs(this)
        if (prefs.serviceEnabled && prefs.registered) {
            runCatching { startForegroundSafely("正在恢复连接…") }
            if (client?.isOnline() != true) {
                client?.close()
                client = null
                ackJob?.cancel()
                ackJob = null
                startRelay(prefs)
            }
            KeepAlive.scheduleResurrect(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val shouldRestart = Prefs(this).serviceEnabled && Prefs(this).registered && !stoppedByUser
        releaseWakeLock()
        ackJob?.cancel()
        statusJob?.cancel()
        watchdogJob?.cancel()
        authRetryJob?.cancel()
        exitScreenOffMode()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        if (guardBound) {
            runCatching { unbindService(guardConnection) }
            guardBound = false
        }
        if (!shouldRestart) GuardService.stop(this)
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        client?.close()
        client = null
        scope.cancel()

        // 被系统回收时立刻安排自启（"自杀式重启"）。用户主动停止时不重启。
        if (shouldRestart) {
            AppState.log("主服务被回收，1 秒后自启", 2)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(this@RelayService)
            }, 1_000)
        }
        super.onDestroy()
    }

    private fun startRelay(prefs: Prefs) {
        if (client != null) return

        val relay = RelayClient(
            serverUrl = prefs.serverUrl,
            deviceId = prefs.deviceId,
            apiKey = prefs.apiKey,
            scope = scope,
            onNotify = { handleNotify(it) },
            onAuthFailed = { handleAuthFailed() },
            fastHeartbeat = prefs.guardEnabled,
        )
        client = relay
        relay.connect()

        // 把来电的每一步状态回执给服务端
        ackJob = scope.launch {
            CallBus.events.collect { event ->
                relay.ack(event.id, event.status, event.note)
            }
        }
    }

    /**
     * 服务端拒绝密钥（401/403）。
     *
     * **不再自动重新注册**——那会让密钥悄悄变掉，用户写在服务端脚本里的 X-API-Key 全部失效
     * （凌晨被换 key 就是这么来的）。现在的策略：
     *   1. 把状态标记成"密钥被拒绝"，界面提示用户；
     *   2. 每 60 秒用**同一把密钥**重试一次，服务端恢复后自动连上，不需要人干预；
     *   3. 只有用户在界面上主动确认，才会重新注册或轮换密钥。
     */
    private fun handleAuthFailed() {
        AppState.setAuthFailed(true)
        AppState.log("服务端拒绝了当前密钥；为保持密钥固定，不会自动重新注册", 2)

        authRetryJob?.cancel()
        authRetryJob = scope.launch {
            while (isActive) {
                delay(60_000)
                if (client?.isOnline() == true) break
                AppState.log("用原密钥重试连接")
                client?.reconnectNow("密钥重试", force = true)
            }
        }
    }

    private fun handleNotify(payload: NotificationPayload) {
        val relay = client ?: return

        scope.launch {
            AppState.log("收到通知：${payload.callerName}")
            relay.ack(payload.id, "delivered")

            if (payload.delaySeconds > 0) {
                AppState.log("${payload.delaySeconds} 秒后响铃")
                holdWakeLock(payload.delaySeconds * 1000L + 120_000L)
                delay(payload.delaySeconds * 1000L)
            }

            val result = CallAccounts.trigger(this@RelayService, payload)
            if (!result.ok) {
                val reason = result.reason ?: "未知原因"
                AppState.log("发起来电失败：$reason", 2)
                relay.ack(payload.id, "failed", reason)
            }
        }
    }

    private fun startForegroundSafely(text: String) {
        val notification = Notifications.serviceNotification(this, text)
        runCatching {
            when {
                // Android 14+：dataSync 有 6 小时/24 小时上限，长期常驻要用 specialUse
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(
                        Notifications.SERVICE_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(
                        Notifications.SERVICE_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                else -> startForeground(Notifications.SERVICE_ID, notification)
            }
        }.onFailure { Log.e(TAG, "startForeground 失败", it) }
    }

    private fun holdWakeLock(timeoutMs: Long) {
        releaseWakeLock()
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallNotify:dispatch").apply {
            setReferenceCounted(false)
            runCatching { acquire(timeoutMs) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RelayService"
        const val ACTION_STOP = "com.callnotify.app.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, RelayService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppState.log("启动后台服务失败：${it.message}", 2) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RelayService::class.java)) }
        }
    }
}
