package com.callnotify.app.telecom

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.util.Log
import com.callnotify.app.core.AppState
import com.callnotify.app.core.Prefs
import com.callnotify.app.voice.SpeechPlayer

/**
 * 一条模拟来电。
 *
 * 响铃由系统负责（托管连接由 Telecom 播放铃声），
 * 接听后由本类把通知内容用 TTS 念出来，念完自动挂断。
 */
class FakeCallConnection(
    private val appContext: Context,
    private val extras: Bundle,
    private val callerNumber: String,
) : Connection() {

    private val notifyId: String = extras.getString(CallExtras.NOTIFY_ID).orEmpty()
    private val callerName: String = extras.getString(CallExtras.CALLER_NAME).orEmpty()
    private val speakText: String = extras.getString(CallExtras.TEXT).orEmpty()
    private val ringSeconds: Int = extras.getInt(CallExtras.RING_SECONDS, 45)
    private val speakTimes: Int = extras.getInt(CallExtras.SPEAK_TIMES, 1).coerceIn(1, 5)

    private val prefs = Prefs(appContext)
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val speech = SpeechPlayer(appContext)
    private val handler = Handler(Looper.getMainLooper())

    private val desiredRoute: Int =
        if (prefs.speakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE

    private var answered = false
    private var finished = false
    private var routeConfirmed = false
    private var speechStarted = false
    private var currentRoute: Int = CallAudioState.ROUTE_EARPIECE

    private val ringTimeout = Runnable {
        if (!answered && !finished) {
            Log.i(TAG, "响铃超时，自动挂断")
            finish(DisconnectCause.MISSED, "missed", "响铃超时无人接听")
        }
    }

    /**
     * 朗读兜底：TTS 引擎卡死（部分 ROM 上没有中文引擎会一直不回调）时，
     * 不能让通话永远挂在那里，超时直接结束。
     */
    private val speechWatchdog = Runnable {
        if (!finished) {
            Log.w(TAG, "朗读超时，强制结束通话")
            finish(DisconnectCause.LOCAL, "completed", "朗读超时")
        }
    }

    init {
        val displayName = callerName.ifBlank { callerNumber }
        setAddress(
            Uri.fromParts(PhoneAccount.SCHEME_TEL, callerNumber, null),
            TelecomManager.PRESENTATION_ALLOWED
        )
        setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED)
        setConnectionCapabilities(CAPABILITY_MUTE)
        setAudioModeIsVoip(true)
        setInitializing()
        setInitialized()
        setRinging()

        if (ringSeconds > 0) {
            handler.postDelayed(ringTimeout, ringSeconds * 1000L)
        }

        // 响铃期间把 TTS 引擎预热好，接听后第一句不用再等引擎初始化
        speech.warmUp()

        CallBus.emit(CallEvent(notifyId, "ringing"))
    }

    override fun onAnswer(videoState: Int) {
        if (answered || finished) return
        answered = true
        handler.removeCallbacks(ringTimeout)

        setActive()

        // 接听瞬间就切路由：免提/听筒要立刻生效，不能等。
        applyRoute()
        runCatching { audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION }

        CallBus.emit(CallEvent(notifyId, "answered"))
        log("已接听，音频路由 → ${routeLabel(desiredRoute)}，等待路由确认后朗读")

        // 系统回 onCallAudioStateChanged 确认路由后就立刻开口；
        // 700ms 内没等到确认（部分 ROM 不回调），就按当前状态直接开始念。
        handler.postDelayed(startSpeechFallback, 700)
    }

    private val startSpeechFallback = Runnable {
        if (!routeConfirmed) {
            // 系统没给确认，再推一次路由（幂等，且此时还没开始朗读，不会跟用户抢）
            applyRoute()
        }
        startSpeech()
    }

    private fun startSpeech() {
        if (speechStarted || finished) return
        speechStarted = true
        speak()
    }

    /**
     * 路由只由 Telecom 决定（setAudioRoute），这样系统通话界面里的免提开关、
     * 我们设置里的「接听走扬声器」才是同一个真相，不会出现反向。
     */
    private fun applyRoute() {
        val ok = runCatching { setAudioRoute(desiredRoute) }.isSuccess
        currentRoute = desiredRoute
        log("请求音频路由 → ${routeLabel(desiredRoute)}" + if (ok) "" else "（请求失败）")
    }

    override fun onCallAudioStateChanged(callAudioState: CallAudioState) {
        // 系统的真实路由（用户点免提/听筒也会走这里）
        routeConfirmed = true
        currentRoute = callAudioState.route
        AppState.log("系统音频路由：${routeLabel(callAudioState.route)}")

        // 路由已经切到我们要的那条，立刻开始朗读，不等固定延时
        if (answered && callAudioState.route == desiredRoute) startSpeech()
    }

    private fun routeLabel(route: Int): String = when (route) {
        CallAudioState.ROUTE_EARPIECE -> "听筒"
        CallAudioState.ROUTE_SPEAKER -> "扬声器"
        CallAudioState.ROUTE_BLUETOOTH -> "蓝牙"
        CallAudioState.ROUTE_WIRED_HEADSET -> "耳机"
        else -> "未知($route)"
    }

    // 系统侧拒接有三个回调入口，只重写 onReject() 会漏掉另外两个（Android 8.0+ 用得多）
    override fun onReject() {
        finish(DisconnectCause.REJECTED, "rejected", "对方拒接")
    }

    override fun onReject(rejectReason: Int) {
        finish(DisconnectCause.REJECTED, "rejected", "对方拒接")
    }

    override fun onReject(replyMessage: String?) {
        finish(DisconnectCause.REJECTED, "rejected", "对方拒接")
    }

    override fun onDisconnect() {
        if (answered) {
            finish(DisconnectCause.LOCAL, "completed", "通话已结束")
        } else {
            // 没人接就被挂断，本质是未接来电，不应该记成 completed
            finish(DisconnectCause.MISSED, "missed", "未接听即挂断")
        }
    }

    override fun onAbort() {
        finish(DisconnectCause.CANCELED, "missed", "来电被取消")
    }

    private fun speak() {
        val text = speakText.ifBlank {
            if (callerName.isNotBlank()) "收到一条来自 $callerName 的通知" else "您有一条新的通知"
        }

        handler.postDelayed(speechWatchdog, watchdogMs(text))

        speech.speak(
            text = text,
            times = speakTimes,
            onDone = {
                handler.removeCallbacks(speechWatchdog)
                log("朗读完成")
                if (prefs.autoHangup) {
                    handler.postDelayed({ finish(DisconnectCause.LOCAL, "completed") }, 800)
                }
            },
            onError = { message ->
                handler.removeCallbacks(speechWatchdog)
                log("语音播放失败：$message")
                CallBus.emit(CallEvent(notifyId, "failed", message))
                // 播放失败也保留通话，用户至少能看到屏幕上的来电
            }
        )
    }

    /** 按文本长度估算合理的朗读上限：每秒约 6 个字，再加建立引擎的时间 */
    private fun watchdogMs(text: String): Long {
        val estimate = text.length / 6L * 1000L * speakTimes
        return (estimate + 25_000L).coerceIn(45_000L, 300_000L)
    }

    private fun finish(cause: Int, status: String, note: String? = null) {
        if (finished) return
        finished = true

        handler.removeCallbacksAndMessages(null)
        speech.release()
        runCatching { audioManager?.mode = AudioManager.MODE_NORMAL }

        val disconnectCause = DisconnectCause(
            cause,
            null,
            null,
            "call_notify_ended",
            ToneGenerator.TONE_PROP_PROMPT
        )
        runCatching { setDisconnected(disconnectCause) }
        runCatching { destroy() }

        CallBus.emit(CallEvent(notifyId, status, note))
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        AppState.log(message)
    }

    private companion object {
        const val TAG = "FakeCallConnection"
    }
}
