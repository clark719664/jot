package com.jot.app.capture

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class VoiceEvent {
    data class Utterance(val text: String) : VoiceEvent()
    data class Failure(val message: String) : VoiceEvent()
    object Wake : VoiceEvent()
}

data class VoiceEngineState(
    val listening: Boolean = false,
    val capturing: Boolean = false,
    val speaking: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

object VoiceSignals {
    private val engineState = MutableStateFlow(VoiceEngineState())
    private val engineEvents = MutableSharedFlow<VoiceEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val state = engineState.asStateFlow()
    val events = engineEvents.asSharedFlow()

    internal fun update(state: VoiceEngineState) {
        engineState.value = state
    }

    internal fun emit(event: VoiceEvent) {
        engineEvents.tryEmit(event)
    }
}
