package com.callnotify.app.core

import android.content.Context
import android.os.Build
import com.callnotify.app.BuildConfig

/**
 * 本地配置：服务器地址、设备身份、来电行为。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("call_notify", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(K_SERVER, "").orEmpty()
            .ifBlank { BuildConfig.DEFAULT_SERVER } // 开箱即用：没填过就用内置服务器
        set(value) = sp.edit().putString(K_SERVER, normalizeServer(value)).apply()

    var deviceId: String
        get() = sp.getString(K_DEVICE_ID, "").orEmpty()
        set(value) { sp.edit().putString(K_DEVICE_ID, value).apply() }

    var apiKey: String
        get() = sp.getString(K_API_KEY, "").orEmpty()
        set(value) { sp.edit().putString(K_API_KEY, value).apply() }

    var deviceName: String
        get() = sp.getString(K_DEVICE_NAME, "").orEmpty().ifBlank { defaultName() }
        set(value) { sp.edit().putString(K_DEVICE_NAME, value).apply() }

    /** 朗读次数 */
    var speakTimes: Int
        get() = sp.getInt(K_SPEAK_TIMES, 1)
        set(value) { sp.edit().putInt(K_SPEAK_TIMES, value.coerceIn(1, 5)).apply() }

    /** 无人接听多少秒后自动挂断 */
    var ringSeconds: Int
        get() = sp.getInt(K_RING_SECONDS, 45)
        set(value) { sp.edit().putInt(K_RING_SECONDS, value.coerceIn(10, 300)).apply() }

    /** 接听后是否走扬声器 */
    var speakerOn: Boolean
        get() = sp.getBoolean(K_SPEAKER, true)
        set(value) { sp.edit().putBoolean(K_SPEAKER, value).apply() }

    /** 朗读完自动挂断 */
    var autoHangup: Boolean
        get() = sp.getBoolean(K_AUTO_HANGUP, true)
        set(value) { sp.edit().putBoolean(K_AUTO_HANGUP, value).apply() }

    /** 是否保持后台连接 */
    var serviceEnabled: Boolean
        get() = sp.getBoolean(K_SERVICE, true)
        set(value) { sp.edit().putBoolean(K_SERVICE, value).apply() }

    /** 是否已经引导过「自启动」设置（系统不提供查询接口，只能记标记） */
    var autoStartAck: Boolean
        get() = sp.getBoolean(K_AUTOSTART_ACK, false)
        set(value) { sp.edit().putBoolean(K_AUTOSTART_ACK, value).apply() }

    /** 是否已经跑过一次「一键自动配置」，避免每次开 App 都去点设置页 */
    var autoConfigDone: Boolean
        get() = sp.getBoolean(K_AUTOCONFIG_DONE, false)
        set(value) { sp.edit().putBoolean(K_AUTOCONFIG_DONE, value).apply() }

    /**
     * 锁屏保活：屏幕熄灭后持有 CPU 唤醒锁 + 高性能 Wi-Fi 锁，
     * 让系统不进入 Doze 深度休眠，长连接才能一直活着。
     * 代价是耗电明显上升，所以单独做成开关。
     */
    var lockScreenKeepAlive: Boolean
        get() = sp.getBoolean(K_LOCKSCREEN, true)
        set(value) { sp.edit().putBoolean(K_LOCKSCREEN, value).apply() }

    /** 极限保活：多进程守护 + 5 秒级心跳（默认开，掉线通知软件优先保证在线） */
    var guardEnabled: Boolean
        get() = sp.getBoolean(K_GUARD, true)
        set(value) { sp.edit().putBoolean(K_GUARD, value).apply() }

    /**
     * 激进保活：用 AlarmClock 做心跳。
     * 不需要任何权限、永远精确、Doze 也唤醒，是纯手机方案里最稳的一种；
     * 代价是状态栏会常驻一个闹钟图标。
     */
    var aggressiveWake: Boolean
        get() = sp.getBoolean(K_AGGRESSIVE_WAKE, true)
        set(value) { sp.edit().putBoolean(K_AGGRESSIVE_WAKE, value).apply() }

    /** 当前密钥是在哪个服务器上注册的；换服务器后要重新注册，不能拿旧密钥去连 */
    var identityServer: String
        get() = sp.getString(K_IDENTITY_SERVER, "").orEmpty()
        set(value) { sp.edit().putString(K_IDENTITY_SERVER, value).apply() }

    val registered: Boolean
        get() = serverUrl.isNotBlank() && deviceId.isNotBlank() && apiKey.isNotBlank()

    fun saveIdentity(server: String, id: String, key: String) {
        sp.edit()
            .putString(K_SERVER, normalizeServer(server))
            .putString(K_DEVICE_ID, id)
            .putString(K_API_KEY, key)
            .putString(K_IDENTITY_SERVER, normalizeServer(server))
            .apply()
    }

    fun clearIdentity() {
        sp.edit().remove(K_DEVICE_ID).remove(K_API_KEY).apply()
    }

    companion object {
        private const val FILE = "call_notify"
        private const val K_SERVER = "server_url"
        private const val K_IDENTITY_SERVER = "identity_server"
        private const val K_DEVICE_ID = "device_id"
        private const val K_API_KEY = "api_key"
        private const val K_DEVICE_NAME = "device_name"
        private const val K_SPEAK_TIMES = "speak_times"
        private const val K_RING_SECONDS = "ring_seconds"
        private const val K_SPEAKER = "speaker_on"
        private const val K_AUTO_HANGUP = "auto_hangup"
        private const val K_SERVICE = "service_enabled"
        private const val K_AUTOSTART_ACK = "autostart_ack"
        private const val K_AUTOCONFIG_DONE = "autoconfig_done"
        private const val K_AGGRESSIVE_WAKE = "aggressive_wake"
        private const val K_GUARD = "guard_enabled"
        private const val K_LOCKSCREEN = "lockscreen_keepalive"

        fun defaultName(): String = (Build.MODEL ?: "Android").take(24)

        /** 补全协议、去掉结尾斜杠，允许用户只填 "192.168.1.10:8787" */
        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s.trimEnd('/')
        }
    }
}
