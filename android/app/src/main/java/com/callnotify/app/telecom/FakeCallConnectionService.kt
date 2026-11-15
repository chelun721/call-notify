package com.callnotify.app.telecom

import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * 只负责把 Telecom 的来电请求变成一条 Connection。
 * 来电界面由系统电话应用负责展示，这里不做任何 UI。
 */
class FakeCallConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        if (request == null) return null

        val extras = callExtrasOf(request)
        val number = request.address?.schemeSpecificPart
            ?: extras.getString(CallExtras.CALLER_NUMBER)
            ?: "10086"

        Log.i(TAG, "建立来电连接：$number")
        return FakeCallConnection(this, extras, number)
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        val id = request?.let { callExtrasOf(it).getString(CallExtras.NOTIFY_ID) }.orEmpty()
        Log.e(TAG, "系统拒绝建立来电连接")
        CallBus.emit(CallEvent(id, "failed", "系统拒绝建立来电连接"))
    }

    /**
     * 取出来电附加字段。
     *
     * AOSP 会把 addNewIncomingCall 的 Bundle 原样交给 ConnectionService，
     * 我们的业务字段在 EXTRA_INCOMING_CALL_EXTRAS 这一层里；
     * 但部分厂商 ROM 会把它拍平到最外层，所以两处都认。
     */
    private fun callExtrasOf(request: ConnectionRequest): Bundle {
        val outer = runCatching { request.extras }.getOrNull() ?: return Bundle()
        val nested = runCatching {
            outer.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        }.getOrNull()

        if (nested != null && nested.containsKey(CallExtras.TEXT)) return nested
        if (outer.containsKey(CallExtras.TEXT) || outer.containsKey(CallExtras.CALLER_NAME)) return outer
        return nested ?: outer
    }

    private companion object {
        const val TAG = "FakeCallService"
    }
}
