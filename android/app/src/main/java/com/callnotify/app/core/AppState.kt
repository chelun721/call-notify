package com.callnotify.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { IDLE, CONNECTING, ONLINE, ERROR }

data class LogLine(val time: String, val text: String, val level: Int = 0)

/**
 * 进程内共享状态：连接状态 + 运行日志。
 * 前台服务写入，界面订阅。
 */
object AppState {

    private val _conn = MutableStateFlow(ConnState.IDLE)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    /**
     * 服务端拒绝了当前密钥。
     * 置 true 后界面会提示用户手动处理——**绝不自动重新注册**，
     * 否则密钥会悄悄变掉，用户的服务端脚本全部失效。
     */
    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setConn(state: ConnState, detail: String = "") {
        _conn.value = state
        _detail.value = detail
        if (state == ConnState.ONLINE) _authFailed.value = false
    }

    fun setAuthFailed(failed: Boolean) {
        _authFailed.value = failed
    }

    fun log(text: String, level: Int = 0) {
        val line = LogLine(timeFormat.format(Date()), text, level)
        _logs.value = (listOf(line) + _logs.value).take(80)
    }
}
