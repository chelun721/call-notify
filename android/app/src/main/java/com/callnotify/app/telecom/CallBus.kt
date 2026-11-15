package com.callnotify.app.telecom

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 一次来电的处理状态，用于回执给服务端 */
data class CallEvent(val id: String, val status: String, val note: String? = null)

object CallBus {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    fun emit(event: CallEvent) {
        _events.tryEmit(event)
    }
}
