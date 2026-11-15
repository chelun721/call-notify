package com.callnotify.app.net

import org.json.JSONObject

/** 服务端下发的一条来电通知 */
data class NotificationPayload(
    val id: String,
    val callerName: String,
    val callerNumber: String,
    val text: String,
    val delaySeconds: Int,
    val ringSeconds: Int,
    val speakTimes: Int,
) {
    companion object {
        fun from(json: JSONObject) = NotificationPayload(
            id = json.optString("id"),
            callerName = json.optString("callerName", "未知来电"),
            callerNumber = json.optString("callerNumber", "10086"),
            text = json.optString("text", ""),
            delaySeconds = json.optInt("delaySeconds", 0).coerceIn(0, 3600),
            ringSeconds = json.optInt("ringSeconds", 45).coerceIn(0, 300),
            speakTimes = json.optInt("speakTimes", 1).coerceIn(1, 5),
        )
    }
}
