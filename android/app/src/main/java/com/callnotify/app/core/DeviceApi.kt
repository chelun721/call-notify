package com.callnotify.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 与通信服务端交互的少量接口：注册设备、校验密钥。
 */
object DeviceApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Credential(val deviceId: String, val apiKey: String)

    suspend fun register(serverUrl: String, name: String): Result<Credential> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("name", name).toString().toRequestBody(JSON)
                val request = Request.Builder()
                    .url("${serverUrl.trimEnd('/')}/api/v1/devices")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException(errorOf(text, resp.code))
                    val json = JSONObject(text)
                    Credential(json.getString("deviceId"), json.getString("apiKey"))
                }
            }
        }

    /**
     * 就地轮换密钥：deviceId 不变，只换 apiKey。
     * 只有用户主动点「更换密钥」才会调用。
     */
    suspend fun rotateKey(serverUrl: String, deviceId: String, apiKey: String): Result<Credential> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${serverUrl.trimEnd('/')}/api/v1/devices/$deviceId/key")
                    .header("X-API-Key", apiKey)
                    .post("".toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException(errorOf(text, resp.code))
                    val json = JSONObject(text)
                    Credential(
                        json.optString("deviceId", deviceId),
                        json.getString("apiKey")
                    )
                }
            }
        }

    private fun errorOf(body: String, code: Int): String = runCatching {
        JSONObject(body).optString("error").ifBlank { "HTTP $code" }
    }.getOrDefault("HTTP $code")
}