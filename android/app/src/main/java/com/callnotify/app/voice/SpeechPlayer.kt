package com.callnotify.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 用系统 TTS 朗读通知内容。
 *
 * 音频属性使用 USAGE_VOICE_COMMUNICATION，让声音走通话声道，
 * 这样接听后听起来和真人说话一致（与 Phony 的做法相同）。
 */
class SpeechPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending = false
    private var text: String = ""
    private var total = 1
    private var spoken = 0
    private var onDone: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var released = false

    /**
     * 预热引擎。
     *
     * TTS 首次初始化要几百毫秒（加载引擎、选语言、建音频通道）。
     * 在响铃阶段就把引擎拉起来，接听后第一句就不用再等这段时间。
     */
    fun warmUp() {
        if (released || ready || tts != null) return
        initEngine()
    }

    fun speak(text: String, times: Int, onDone: () -> Unit, onError: (String) -> Unit) {
        this.text = text
        this.total = times.coerceIn(1, 5)
        this.spoken = 0
        this.onDone = onDone
        this.onError = onError
        this.pending = true

        if (ready) startUtterance() else initEngine()
    }

    private fun initEngine() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (!released) {
                if (status != TextToSpeech.SUCCESS) {
                    fail(
                        "语音引擎初始化失败（status=$status，系统里可见的引擎：${installedEngines(appContext)}）。" +
                            "去系统设置里装一个中文语音引擎，或点下方「语音引擎设置」指定一个"
                    )
                } else {
                    val engine = tts
                    if (engine == null) {
                        fail("语音引擎初始化失败")
                    } else {
                        ready = true
                        configure(engine)
                        if (pending) startUtterance()
                    }
                }
            }
        }
    }

    private fun configure(engine: TextToSpeech) {
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }

        val result = runCatching { engine.setLanguage(Locale.CHINA) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            runCatching { engine.setLanguage(Locale.getDefault()) }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                spoken++
                if (spoken < total) {
                    startUtterance()
                } else {
                    handler.post { onDone?.invoke() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { fail("语音播放失败") }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { fail("语音播放失败（$errorCode）") }
            }
        })
    }

    private fun startUtterance() {
        if (released || !pending) return
        val engine = tts ?: return
        val code = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call_notify_$spoken")
        }.getOrDefault(TextToSpeech.ERROR)
        if (code == TextToSpeech.ERROR) fail("语音引擎拒绝了朗读请求")
    }

    private fun fail(message: String) {
        if (released) return
        Log.e(TAG, message)
        pending = false
        val callback = onError
        onError = null
        onDone = null
        callback?.invoke(message)
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        onDone = null
        onError = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "SpeechPlayer"
    }
}

/**
 * 列出系统里能看到的 TTS 引擎。
 *
 * 注意：targetSdk 30+ 必须在 Manifest 的 <queries> 里声明
 * android.intent.action.TTS_SERVICE，否则这里永远是"无"。
 */
fun installedEngines(context: Context): String {
    val pm = context.packageManager
    val found = runCatching {
        pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
    }.getOrElse { return "查询失败：${it.message}" }
    if (found.isEmpty()) return "无"
    return found.joinToString("、") { it.serviceInfo.packageName }
}

/** Android 的 TTS 设置页（可以在这里换引擎、下载离线语音包） */
fun openTtsSettings(context: Context) {
    val candidates = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        val ok = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
}
