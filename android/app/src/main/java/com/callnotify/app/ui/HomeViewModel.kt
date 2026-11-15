package com.callnotify.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.callnotify.app.autoconfig.AutoConfig
import com.callnotify.app.core.AppState
import com.callnotify.app.core.DeviceApi
import com.callnotify.app.core.Prefs
import com.callnotify.app.core.SetupAssistant
import com.callnotify.app.keepalive.KeepAlive
import com.callnotify.app.net.NotificationPayload
import com.callnotify.app.service.RelayService
import com.callnotify.app.telecom.CallAccounts
import com.callnotify.app.voice.SpeechPlayer
import com.callnotify.app.voice.installedEngines
import com.callnotify.app.voice.openTtsSettings
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val speech = SpeechPlayer(app)

    var serverInput by mutableStateOf(prefs.serverUrl)
    var deviceId by mutableStateOf(prefs.deviceId)
    var apiKey by mutableStateOf(prefs.apiKey)
    var deviceName by mutableStateOf(prefs.deviceName)

    var speakTimes by mutableStateOf(prefs.speakTimes)
    var ringSeconds by mutableStateOf(prefs.ringSeconds)
    var speakerOn by mutableStateOf(prefs.speakerOn)
    var autoHangup by mutableStateOf(prefs.autoHangup)
    var serviceEnabled by mutableStateOf(prefs.serviceEnabled)

    var busy by mutableStateOf(false)
    var accountEnabled by mutableStateOf(CallAccounts.isEnabled(app))
    var accountDetail by mutableStateOf("")
    var ttsEngines by mutableStateOf(installedEngines(app))
    var aggressiveWake by mutableStateOf(prefs.aggressiveWake)
    var lockScreenKeepAlive by mutableStateOf(prefs.lockScreenKeepAlive)
    var guardEnabled by mutableStateOf(prefs.guardEnabled)
    var exactAlarmAllowed by mutableStateOf(KeepAlive.exactAlarmAllowed(app))

    private var bootstrapped = false

    val registered: Boolean
        get() = deviceId.isNotBlank() && apiKey.isNotBlank()

    fun onServerChanged(value: String) {
        serverInput = value
    }

    fun refreshAccount() {
        val app = getApplication<Application>()
        accountEnabled = CallAccounts.ensure(app)
        accountDetail = CallAccounts.status(app).detail
    }

    /** 手动重试注册通话账户，并把系统的原话显示出来 */
    fun reRegisterAccount() {
        val app = getApplication<Application>()
        val message = CallAccounts.register(app)
        AppState.log(message)
        toast(message)
        refreshAccount()
    }

    /** 身份可能在后台被改过（例如服务端不认密钥时自动重新注册），回到前台同步一次 */
    fun refreshIdentity() {
        deviceId = prefs.deviceId
        apiKey = prefs.apiKey
        serverInput = prefs.serverUrl
    }

    /**
     * 开箱即用：首次启动直接用内置服务器自动注册并连接，用户不用填任何东西。
     * 只有在没有内置服务器（BuildConfig.DEFAULT_SERVER 为空）时才需要手填。
     */
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true

        val server = Prefs.normalizeServer(prefs.serverUrl)
        if (server.isBlank()) {
            AppState.log("没有内置服务器，请手动填写地址")
            return
        }
        syncService() // 已注册过就直接连
        if (registered) return

        AppState.log("正在使用内置服务器自动注册：$server")
        register(server, silent = true)
    }

    /** 注册设备（首次或换服务器） */
    private fun register(server: String, silent: Boolean = false) {
        busy = true
        viewModelScope.launch {
            val result = DeviceApi.register(server, deviceName)
            busy = false
            result.onSuccess { credential ->
                prefs.saveIdentity(server, credential.deviceId, credential.apiKey)
                prefs.deviceName = deviceName
                serverInput = server
                deviceId = credential.deviceId
                apiKey = credential.apiKey
                AppState.log("设备注册成功：${credential.deviceId}")
                startService()
                toast(if (silent) "已自动就绪，可以直接用「模拟来电」验证" else "注册成功，密钥已生成")
            }.onFailure {
                AppState.log("注册失败：${it.message}", 2)
                if (!silent) toast("注册失败：${it.message}")
            }
        }
    }

    /** 保存服务器地址；首次使用或换了服务器时向服务端注册本机并获取密钥 */
    fun saveAndConnect() {
        val server = Prefs.normalizeServer(serverInput)
        if (server.isBlank()) {
            toast("请先填写服务器地址")
            return
        }

        // 密钥只对注册时的那个服务器有效；地址变了就必须重新注册
        val sameServer = registered && prefs.identityServer == server
        if (sameServer) {
            prefs.serverUrl = server
            serverInput = server
            startService()
            toast("已保存，正在连接")
            return
        }

        if (registered) AppState.log("服务器已更换，重新注册设备")
        register(server)
    }

    /**
     * 更换密钥（需用户确认后调用）。
     *
     * 只做"就地轮换"：deviceId 不变，只换 apiKey，服务端按 deviceId 记的配置不作废。
     * 轮换成功后自动重连。
     */
    fun rotateKey() {
        val server = Prefs.normalizeServer(prefs.serverUrl)
        if (server.isBlank() || deviceId.isBlank() || apiKey.isBlank()) {
            toast("还没有可轮换的密钥")
            return
        }
        busy = true
        viewModelScope.launch {
            val result = DeviceApi.rotateKey(server, deviceId, apiKey)
            busy = false
            result.onSuccess { cred ->
                prefs.saveIdentity(server, cred.deviceId, cred.apiKey)
                deviceId = cred.deviceId
                apiKey = cred.apiKey
                AppState.setAuthFailed(false)
                AppState.log("密钥已更换（设备 ID 不变）")
                RelayService.stop(getApplication())
                startService()
                toast("密钥已更换，请同步更新服务端脚本里的 X-API-Key")
            }.onFailure {
                AppState.log("更换密钥失败：${it.message}", 2)
                toast("更换失败：${it.message}")
            }
        }
    }

    /** 用户确认后重新注册一台新设备（会换 deviceId + key） */
    fun reRegister() {
        val server = Prefs.normalizeServer(prefs.serverUrl)
        if (server.isBlank()) {
            toast("请先填写服务器地址")
            return
        }
        prefs.clearIdentity()
        deviceId = ""
        apiKey = ""
        AppState.setAuthFailed(false)
        register(server)
    }

    fun startService() {
        prefs.serviceEnabled = true
        serviceEnabled = true
        RelayService.start(getApplication())
    }

    fun stopService() {
        prefs.serviceEnabled = false
        serviceEnabled = false
        RelayService.stop(getApplication())
    }

    /**
     * 回到前台时补一次服务拉起。
     * 系统清理后台、或用户在设置里还原权限后，连接不会自己回来，
     * 这里保证「打开 App 就能重新连上」，而不是只能靠开机广播。
     */
    fun syncService() {
        if (!registered || !prefs.serviceEnabled) return
        serviceEnabled = true
        RelayService.start(getApplication())
    }

    fun updateSpeakTimes(value: Int) {
        speakTimes = value.coerceIn(1, 5)
        prefs.speakTimes = speakTimes
    }

    fun updateRingSeconds(value: Int) {
        ringSeconds = value.coerceIn(10, 300)
        prefs.ringSeconds = ringSeconds
    }

    fun updateSpeakerOn(value: Boolean) {
        speakerOn = value
        prefs.speakerOn = value
    }

    fun updateAutoHangup(value: Boolean) {
        autoHangup = value
        prefs.autoHangup = value
    }

    /** 激进保活：AlarmClock 心跳（状态栏会显示一个闹钟图标） */
    fun updateAggressiveWake(value: Boolean) {
        aggressiveWake = value
        prefs.aggressiveWake = value
        val app = getApplication<Application>()
        if (value) {
            KeepAlive.scheduleNextTick(app)
            AppState.log("已开启 AlarmClock 心跳（状态栏会有闹钟图标）")
        } else {
            KeepAlive.cancel(app)
            KeepAlive.schedulePersistedJob(app)
            AppState.log("已关闭 AlarmClock 心跳，退回精确闹钟 + 系统任务")
        }
    }

    fun updateLockScreenKeepAlive(value: Boolean) {
        lockScreenKeepAlive = value
        prefs.lockScreenKeepAlive = value
        AppState.log(if (value) "锁屏保活已开启（耗电会增加）" else "锁屏保活已关闭")
        // 立刻生效：重新拉起服务，让它按新设置注册/释放锁
        RelayService.stop(getApplication())
        startService()
    }

    fun updateGuardEnabled(value: Boolean) {
        guardEnabled = value
        prefs.guardEnabled = value
        AppState.log(if (value) "极限保活已开启（守护进程 + 5 秒心跳）" else "极限保活已关闭")
        RelayService.stop(getApplication())
        startService()
    }

    fun refreshWakeState() {
        exactAlarmAllowed = KeepAlive.exactAlarmAllowed(getApplication())
        lockScreenKeepAlive = prefs.lockScreenKeepAlive
        guardEnabled = prefs.guardEnabled
    }

    /** 本机直接触发一次来电，用来验证权限与系统集成 */
    fun testCall() {
        val context = getApplication<Application>()
        if (!CallAccounts.ensure(context)) {
            toast("通话账户未启用，请点「通话账户」在系统设置里打开")
            return
        }
        val payload = NotificationPayload(
            id = "test-" + System.currentTimeMillis(),
            callerName = "测试来电",
            callerNumber = "13800138000",
            text = "这是一条测试通知。如果你听到这段话，说明来电和语音播报都正常。",
            delaySeconds = 0,
            ringSeconds = ringSeconds,
            speakTimes = speakTimes,
        )
        val result = CallAccounts.trigger(context, payload)
        if (result.ok) {
            toast("正在来电…")
        } else {
            AppState.log("发起来电失败：${result.reason}", 2)
            toast("发起来电失败：${result.reason}")
        }
    }

    fun testSpeech() {
        speech.speak(
            text = "语音测试正常，通知内容会像这样朗读出来。",
            times = 1,
            onDone = { },
            onError = { toast(it) }
        )
    }

    fun refreshTtsEngines() {
        ttsEngines = installedEngines(getApplication())
    }

    fun openTtsSettings() = openTtsSettings(getApplication())

    /** 无障碍自动配置是否已开启 */
    fun autoConfigReady(): Boolean = AutoConfig.isEnabled(getApplication())

    /**
     * 一键自动配置。
     *
     * 用户只需要：①打开无障碍开关 ②点这里。之后由 AutoConfigService 代点：
     * 电池优化弹窗的「允许」→ 通话账户页的开关 → 自启动页的开关，并自动返回。
     */
    fun autoConfigure() {
        val app = getApplication<Application>()
        if (!AutoConfig.isEnabled(app)) {
            AutoConfig.openAccessibilitySettings(app)
            toast("在无障碍里打开「来电通知」，回来再点一次")
            return
        }

        val handler = Handler(Looper.getMainLooper())
        AutoConfig.begin()
        toast("开始自动配置，请不要操作屏幕")
        AppState.log("一键自动配置：开始")

        // ① 电池优化：弹系统框，服务点「允许」
        AutoConfig.pendingTarget = AutoConfig.Target.BATTERY
        SetupAssistant.requestIgnoreBattery(app)

        // ② 通话账户：打开页面，服务打开开关并返回
        handler.postDelayed({
            AutoConfig.pendingTarget = AutoConfig.Target.ACCOUNT
            CallAccounts.openSettings(app)
        }, 4000)

        // ③ 自启动：打开 ROM 的自启动页，服务打开开关并返回
        handler.postDelayed({
            AutoConfig.pendingTarget = AutoConfig.Target.AUTOSTART
            SetupAssistant.openAutoStartSettings(app)
        }, 9000)

        handler.postDelayed({
            AutoConfig.end()
            refreshAccount()
            toast("自动配置结束，检查打勾情况")
        }, 15000)
    }

    override fun onCleared() {
        speech.release()
        super.onCleared()
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}
