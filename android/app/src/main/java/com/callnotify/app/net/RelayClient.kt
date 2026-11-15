package com.callnotify.app.net

import com.callnotify.app.core.AppState
import com.callnotify.app.core.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 与服务端之间的 WebSocket 长连接。
 *
 * v1.8 重点：把"发现断线"的时间压到 20 秒以内。
 *   1. 协议 ping 10s（OkHttp 自己会在一个 ping 周期内没收到 pong 时判失败）；
 *   2. 应用层心跳 10s 一发，20s 没任何数据就主动重建（专治"连着但收不到东西"的假死）；
 *   3. 退避 0.3s 起，抖动 ±20%，上限 10s；
 *   4. 连接超时 6s；
 *   5. 断线期间的 ack 进内存队列，重连后按序补发（以前是直接丢）。
 */
class RelayClient(
    private val serverUrl: String,
    private val deviceId: String,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val onNotify: (NotificationPayload) -> Unit,
    private val onAuthFailed: () -> Unit = {},
) {

    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var retryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var attempt = 0

    @Volatile
    private var lastRecvAt = 0L

    @Volatile
    private var offlineSince = 0L

    @Volatile
    private var online = false

    @Volatile
    private var closed = false

    /** 断线期间没发出去的 ack，重连后补发 */
    private val ackQueue = ArrayDeque<String>()

    fun isOnline(): Boolean = online

    fun connect(reason: String = "首次连接") {
        if (closed || !scope.isActive) return
        AppState.setConn(ConnState.CONNECTING, "正在连接服务器…")
        if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
        AppState.log("连接中（$reason）")
        val request = Request.Builder().url(wsUrl()).build()
        socket = http.newWebSocket(request, listener)
    }

    /** 上报通知处理状态；发不出去就排队，重连后补发 */
    fun ack(id: String, status: String, note: String? = null) {
        if (id.isBlank()) return
        val payload = JSONObject()
            .put("type", "ack")
            .put("id", id)
            .put("status", status)
        if (!note.isNullOrBlank()) payload.put("note", note)
        enqueueOrSend(payload.toString())
    }

    private fun enqueueOrSend(text: String) {
        val sent = online && (runCatching { socket?.send(text) }.getOrNull() ?: false)
        if (sent) return
        synchronized(ackQueue) {
            ackQueue.addLast(text)
            while (ackQueue.size > 50) ackQueue.removeFirst()
        }
        AppState.log("状态回执暂存（断线中，重连后补发）")
    }

    private fun flushAcks() {
        val pending = synchronized(ackQueue) {
            val copy = ackQueue.toList()
            ackQueue.clear()
            copy
        }
        if (pending.isEmpty()) return
        var ok = 0
        for (text in pending) {
            if (runCatching { socket?.send(text) }.getOrNull() ?: false) ok++ else break
        }
        AppState.log("补发 ${ok}/${pending.size} 条状态回执")
    }

    fun close() {
        closed = true
        retryJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { socket?.close(1000, "client closing") }
        socket = null
        online = false
        AppState.setConn(ConnState.IDLE, "已停止")
    }

    /** 立即重连；已在线时不动，避免网络抖动踢掉健康连接 */
    fun reconnectNow(reason: String, force: Boolean = false) {
        if (closed) return
        if (online && !force) {
            AppState.log("网络变化但连接健康，忽略重连（$reason）")
            return
        }
        retryJob?.cancel()
        attempt = 0
        runCatching { socket?.cancel() }
        socket = null
        connect(reason)
    }

    private fun wsUrl(): String {
        val base = serverUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/')
        return "$base/ws?deviceId=${enc(deviceId)}&apiKey=${enc(apiKey)}"
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** 退避：0.3s 起，最长 10s，带抖动 */
    private fun nextDelayMs(): Long {
        val table = longArrayOf(300, 600, 1_000, 2_000, 3_000, 5_000, 8_000, 10_000)
        val base = table[attempt.coerceAtMost(table.lastIndex)]
        attempt++
        val jitter = (base * 0.2 * (Random.nextDouble() * 2 - 1)).toLong()
        return (base + jitter).coerceAtLeast(200)
    }

    private fun scheduleReconnect() {
        if (closed || !scope.isActive) return
        retryJob?.cancel()
        val waitMs = nextDelayMs()
        AppState.setConn(ConnState.CONNECTING, "${waitMs} 毫秒后重连…")
        retryJob = scope.launch {
            delay(waitMs)
            if (!online && !closed) connect("第 $attempt 次重连")
        }
    }

    /**
     * 应用层心跳：10 秒一发，20 秒收不到任何数据就判死。
     *
     * 这是解决"划掉后 30~90 秒才恢复"的关键——以前阈值 40 秒，
     * 光等检测就耗掉大半时间；现在最坏 20 秒，通常 10 秒内就能发现。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastRecvAt = System.currentTimeMillis()
        heartbeatJob = scope.launch {
            while (isActive && !closed) {
                delay(10_000)
                if (closed) break
                val silentFor = System.currentTimeMillis() - lastRecvAt
                if (silentFor > 20_000) {
                    AppState.log("连接假死（${silentFor / 1000}s 无数据），主动重连", 2)
                    online = false
                    runCatching { socket?.cancel() }
                    socket = null
                    if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
                    connect("心跳超时")
                    break
                }
                runCatching { socket?.send("""{"type":"ping"}""") }
            }
        }
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return   // 旧连接的回调，忽略
            attempt = 0
            online = true
            lastRecvAt = System.currentTimeMillis()
            val downMs = if (offlineSince > 0) System.currentTimeMillis() - offlineSince else 0
            offlineSince = 0
            AppState.setConn(ConnState.ONLINE, "已连接，等待通知")
            AppState.log(if (downMs > 0) "已连接服务器（断线 ${downMs}ms 后恢复）" else "已连接服务器")
            startHeartbeat()
            flushAcks()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            lastRecvAt = System.currentTimeMillis()
            runCatching {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "notify" -> onNotify(NotificationPayload.from(json))
                    "ready" -> AppState.log("服务端握手完成")
                    "pong" -> Unit
                }
            }.onFailure {
                AppState.log("消息解析失败：${it.message}", 2)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // cancel() 掉的老 socket 也会回调 onFailure（socket closed），
            // 不拦住的话会把刚建立的新连接状态改回 ERROR，并多排一次重连
            if (webSocket !== socket) return
            online = false
            if (closed) return

            val code = response?.code
            if (code == 401 || code == 403) {
                AppState.setConn(ConnState.ERROR, "密钥被服务端拒绝（HTTP $code）")
                AppState.log("连接被拒绝：密钥无效（HTTP $code）", 2)
                onAuthFailed()
                return
            }

            if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
            AppState.setConn(ConnState.ERROR, t.message ?: "连接异常")
            AppState.log("连接断开：${t.message ?: t.javaClass.simpleName}", 2)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            online = false
            if (closed) return
            if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
            AppState.setConn(ConnState.ERROR, "连接已关闭（$code）")
            scheduleReconnect()
        }
    }
}
